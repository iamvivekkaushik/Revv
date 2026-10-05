package com.vivekkaushik.revv

import android.Manifest
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vivekkaushik.revv.apps.LauncherApp
import com.vivekkaushik.revv.media.MediaListenerService
import com.vivekkaushik.revv.nav.Place
import com.vivekkaushik.revv.obd.BluetoothAccess
import com.vivekkaushik.revv.obd.ObdAdapter
import com.vivekkaushik.revv.system.ExternalApps
import com.vivekkaushik.revv.system.HomeRole
import com.vivekkaushik.revv.system.NightMode
import com.vivekkaushik.revv.ui.hmi.HmiActions
import com.vivekkaushik.revv.ui.hmi.HmiApp
import com.vivekkaushik.revv.ui.hmi.HmiRoot
import com.vivekkaushik.revv.ui.hmi.HmiUiState
import com.vivekkaushik.revv.ui.hmi.LocalIconProvider
import com.vivekkaushik.revv.vehicle.CarSetup

class MainActivity : ComponentActivity(), HmiActions {

    private val viewModel: LauncherViewModel by viewModels()

    /** True from onStop until the next onResume: Revv isn't on screen to animate anything. */
    private var offScreen = false

    /** When the current home-app request started, and whether the user asked for it with a tap. */
    private var homeRequestStartedAt = 0L
    private var homeRequestFromTap = false

    private val homeRoleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshSystemState()
        // An instant answer means Android skipped its dialog because "Don't ask again" was ticked
        // earlier. A tap should still lead somewhere, so fall back to the Home app settings page.
        val skipped = SystemClock.elapsedRealtime() - homeRequestStartedAt < DIALOG_SKIPPED_MILLIS
        if (homeRequestFromTap && skipped && !viewModel.system.value.isDefaultHome) {
            startFirstAvailable(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
    }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openAppSettingsIfDeniedForGood(listOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
        viewModel.onBluetoothPermissionResult()
    }

    private val bleScanPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.isNotEmpty() && results.values.all { it }
        if (!granted && results.isNotEmpty()) openAppSettingsIfDeniedForGood(results.keys)
        // The adapter picker starts scanning once it sees the permission.
        viewModel.onBluetoothPermissionResult()
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // Approximate location alone is enough to put the car on the map, if less precisely.
        if (results.isNotEmpty() && results.values.none { it }) openAppSettingsIfDeniedForGood(results.keys)
        viewModel.onLocationPermissionResult()
    }

    private val carPlayIdentityPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importCarPlayIdentity(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        hideSystemBars()
        // Pressing home while Revv is the home app returns to the home screen. Its icon in another
        // launcher (on a phone, say, coming back from Bluetooth settings) resumes where it was.
        addOnNewIntentListener { intent ->
            if (intent.hasCategory(Intent.CATEGORY_HOME)) viewModel.goHome(animate = !offScreen)
        }
        setContent {
            val apps by viewModel.apps.collectAsStateWithLifecycle()
            val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
            val system by viewModel.system.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val screen by viewModel.screen.collectAsStateWithLifecycle()
            val obdStatus by viewModel.obdStatus.collectAsStateWithLifecycle()
            val adapterChoices by viewModel.adapterChoices.collectAsStateWithLifecycle()
            val bleScanning by viewModel.bleScanning.collectAsStateWithLifecycle()
            val recentPlaces by viewModel.recentPlaces.collectAsStateWithLifecycle()
            val obdLog by viewModel.obdLog.collectAsStateWithLifecycle()
            val phone by viewModel.phone.collectAsStateWithLifecycle()
            val learntGears by viewModel.learntGears.collectAsStateWithLifecycle()
            val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()
            val carPlay by viewModel.carPlay.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalIconProvider provides viewModel.icons) {
                HmiRoot(
                    state = HmiUiState(
                        apps, nowPlaying, system, settings, screen, obdStatus, adapterChoices, bleScanning, recentPlaces, obdLog, phone,
                        learntGears, activeCall, carPlay = carPlay,
                    ),
                    obdReadings = viewModel.obdReadings,
                    navigation = viewModel.navigation,
                    actions = this@MainActivity,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onResume() {
        super.onResume()
        offScreen = false
        viewModel.refreshSystemState()
    }

    override fun onStop() {
        super.onStop()
        offScreen = true
        viewModel.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** The HMI draws its own status bar and dock; system bars come back with a swipe. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onHmiReady() {
        // Fresh install that isn't the home app yet: offer to become it, once.
        if (viewModel.shouldAskToBeHome()) askToBeHome(fromTap = false)
    }

    override fun open(app: HmiApp) = viewModel.open(app)
    override fun openMapsSearch() = viewModel.openMapsSearch()
    override fun mapsSearchShown() = viewModel.mapsSearchShown()
    override fun openCarPlaySettings() = viewModel.openCarPlaySettings()

    override fun setCarPlayFullScreen(on: Boolean) = viewModel.setCarPlayFullScreen(on)
    override fun carPlaySettingsShown() = viewModel.carPlaySettingsShown()

    override fun goHome() = viewModel.goHome()

    override fun back() = viewModel.back()

    override fun launch(app: LauncherApp, source: Rect?) {
        val decor = window.decorView
        val options = source?.let {
            ActivityOptions.makeScaleUpAnimation(decor, it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt())
                .toBundle()
        }
        val screenBounds = source?.let {
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            android.graphics.Rect(
                it.left.toInt() + origin[0],
                it.top.toInt() + origin[1],
                it.right.toInt() + origin[0],
                it.bottom.toInt() + origin[1],
            )
        }
        viewModel.launch(app, screenBounds, options)
    }

    override fun showAppInfo(app: LauncherApp) = viewModel.openAppInfo(app)

    override fun playPause() = viewModel.playPause()

    override fun skipToNext() = viewModel.skipToNext()

    override fun skipToPrevious() = viewModel.skipToPrevious()

    override fun seekTo(positionMs: Long) = viewModel.seekTo(positionMs)

    override fun openPlayer() {
        if (!viewModel.openPlayer()) openMusic()
    }

    override fun openMusic() {
        val music = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        if (!startFirstAvailable(music)) toast(R.string.no_music_app)
    }

    override fun call(number: String) = viewModel.call(number)

    override fun readPhoneAgain() = viewModel.readPhoneAgain()

    override fun startProjection() {
        val apps = viewModel.apps.value
        val chosen = viewModel.settings.value.projectionApp?.let { key -> apps.firstOrNull { it.key == key } }
        val projection = chosen ?: ExternalApps.findProjectionApp(apps)
        if (projection != null) launch(projection, null) else toast(R.string.no_projection_app)
    }

    override fun openNavigationApp() {
        val navigation = ExternalApps.navigationIntent(this)
        if (navigation == null || !startFirstAvailable(navigation)) toast(R.string.no_navigation_app)
    }

    override fun openRadio() {
        val radio = ExternalApps.findRadioApp(viewModel.apps.value)
        if (radio != null) launch(radio, null) else viewModel.open(HmiApp.Radio)
    }

    override fun requestMediaAccess() {
        val component = ComponentName(this, MediaListenerService::class.java).flattenToString()
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else {
            null
        }
        startFirstAvailable(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        // Android 13+ blocks this switch for sideloaded apps until restricted settings are allowed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.media_access_restricted_hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun requestBrightnessAccess() {
        // Revv's own switch, else the list of apps, for builds that lack the per-app page.
        val revv = "package:$packageName".toUri()
        startFirstAvailable(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, revv),
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, revv),
        )
    }

    override fun setNightMode(mode: NightMode) = viewModel.setNightMode(mode)

    override fun requestDefaultHome() = askToBeHome(fromTap = true)

    private fun askToBeHome(fromTap: Boolean) {
        homeRequestFromTap = fromTap
        homeRequestStartedAt = SystemClock.elapsedRealtime()
        try {
            homeRoleRequest.launch(HomeRole.requestIntent(this))
        } catch (e: ActivityNotFoundException) {
            if (fromTap) startFirstAvailable(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun openSystemSettings() {
        startFirstAvailable(Intent(Settings.ACTION_SETTINGS))
    }

    override fun openBluetoothSettings() {
        startFirstAvailable(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    override fun openWifiSettings() {
        val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else null
        startFirstAvailable(panel, Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    override fun toggleSetting(key: String) = viewModel.toggleSetting(key)
    override fun clearTroubleCodes() = viewModel.clearTroubleCodes()
    override fun setChoice(key: String, index: Int) = viewModel.setChoice(key, index)
    override fun setGoogleApiKey(key: String?) = viewModel.setGoogleApiKey(key)
    override fun setRearCameraId(id: String?) = viewModel.setRearCameraId(id)

    override fun importCarPlayIdentity() {
        // Some head units ship without a document picker; launching can throw before any result.
        runCatching { carPlayIdentityPicker.launch(arrayOf("*/*")) }
            .onFailure { Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show() }
    }
    override fun setProjectionApp(app: LauncherApp?) = viewModel.setProjectionApp(app)
    override fun setFuelWidgetApp(app: LauncherApp?) = viewModel.setFuelWidgetApp(app)
    override fun hangUp() = viewModel.hangUp()
    override fun setDisplaySize(percent: Int) = viewModel.setDisplaySize(percent)

    override fun changeLevel(key: String, direction: Int) = viewModel.changeLevel(key, direction)

    override fun refreshAdapterChoices() = viewModel.refreshAdapterChoices()

    override fun chooseObdAdapter(adapter: ObdAdapter) = viewModel.chooseObdAdapter(adapter)

    override fun forgetObdAdapter() = viewModel.forgetObdAdapter()

    override fun updateCar(car: CarSetup) = viewModel.updateCar(car)

    override fun relearnGears() = viewModel.relearnGears()

    override fun requestBleScanPermission() = bleScanPermission.launch(BluetoothAccess.scanPermissions())

    override fun scanForBleAdapters() = viewModel.scanForBleAdapters()

    override fun stopBleScan() = viewModel.stopBleScan()

    override fun openLocationSettings() {
        startFirstAvailable(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    override fun requestLocationPermission() {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun searchPlaces(query: String) = viewModel.searchPlaces(query)

    override fun clearPlaceSearch() = viewModel.clearPlaceSearch()

    override fun navigateTo(place: Place) = viewModel.navigateTo(place)

    override fun retryRoute() = viewModel.retryRoute()

    override fun endRoute() = viewModel.endRoute()

    override fun clearRecentPlaces() = viewModel.clearRecentPlaces()

    override fun advanceDemoDrive(seconds: Float, speedKmh: Int) = viewModel.advanceDemoDrive(seconds, speedKmh)

    override fun reportDemoEngine(rpm: Int, speedKmh: Int, gear: Int?, throttle: Int, engineLoad: Int) =
        viewModel.reportDemoEngine(rpm, speedKmh, gear, throttle, engineLoad)

    override fun previewEngineSound() = viewModel.previewEngineSound()

    override fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** Once Android stops showing a permission dialog, only Revv's settings page can grant it. */
    private fun openAppSettingsIfDeniedForGood(permissions: Collection<String>) {
        if (permissions.none(::shouldShowRequestPermissionRationale)) {
            startFirstAvailable(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    /** Starts the first intent something can handle. Head-unit builds often strip settings pages. */
    private fun startFirstAvailable(vararg intents: Intent?): Boolean {
        for (intent in intents.filterNotNull()) {
            try {
                startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            }
        }
        return false
    }

    private fun toast(@StringRes message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Nobody reads and dismisses the dialog this fast, so a quicker answer means it never showed. */
        const val DIALOG_SKIPPED_MILLIS = 1500L
    }
}
