package com.vivekkaushik.revv.ui.hmi

import androidx.compose.ui.geometry.Rect
import com.vivekkaushik.revv.apps.LauncherApp
import com.vivekkaushik.revv.media.NowPlaying
import com.vivekkaushik.revv.nav.Place
import com.vivekkaushik.revv.obd.ObdAdapter
import com.vivekkaushik.revv.obd.ObdStatus
import com.vivekkaushik.revv.settings.HmiSettings

/** The HMI's own full-screen apps, opened from the dock or the Apps screen. */
enum class HmiApp(val title: String) {
    Phone("Phone"),
    Auto("Android Auto"),
    Maps("Navigation"),
    Vehicle("Vehicle"),
    Camera("Rear camera"),
    Apps("All apps"),
    Settings("Settings"),
    Radio("FM Radio"),
}

/**
 * Which app is open over the home screen, and the apps it was opened from (most recent last),
 * which back returns through. [resetCount] changes when the app should vanish without animating.
 */
data class ScreenState(
    val app: HmiApp? = null,
    val resetCount: Int = 0,
    val previous: List<HmiApp> = emptyList(),
) {
    /** [app] opened over this screen. An app already in the stack moves to the top rather than repeating. */
    fun open(app: HmiApp): ScreenState =
        if (app == this.app) this else copy(app = app, previous = (previous + listOfNotNull(this.app)) - app)

    /** One step back: the app this one was opened from, or home. */
    fun back(): ScreenState =
        previous.lastOrNull()?.let { copy(app = it, previous = previous.dropLast(1)) } ?: copy(app = null)

    /** Home, forgetting the stack. [animate] false makes the open app vanish at once. */
    fun home(animate: Boolean): ScreenState = when {
        app == null -> this
        animate -> copy(app = null, previous = emptyList())
        else -> ScreenState(resetCount = resetCount + 1)
    }
}

/** Device state Revv polls whenever it comes to the foreground. */
data class SystemState(
    val hasMediaAccess: Boolean,
    val isDefaultHome: Boolean,
    val bluetoothOn: Boolean,
    val hasBluetoothPermission: Boolean,
    val canScanBle: Boolean,
    /** Before Android 12, Bluetooth LE scans need location services on. */
    val locationBlocksBleScan: Boolean,
    val hasLocationPermission: Boolean,
    val locationOn: Boolean,
    val mediaVolume: Int,
    val mediaVolumeMax: Int,
)

/**
 * Everything the HMI draws except what changes every second or faster: OBD-II readings go straight
 * to the cluster and navigation straight to the maps.
 */
data class HmiUiState(
    val apps: List<LauncherApp>,
    val nowPlaying: NowPlaying?,
    val system: SystemState,
    val settings: HmiSettings,
    val screen: ScreenState,
    val obd: ObdStatus,
    val adapterChoices: List<ObdAdapter>,
    val bleScanning: Boolean,
    val recentPlaces: List<Place>,
)

/** Everything the HMI can ask for. Implemented by MainActivity. */
interface HmiActions {
    /** The ignition sequence has finished and the HMI is on screen. */
    fun onHmiReady()

    fun open(app: HmiApp)
    fun goHome()

    /** Returns to the app the open one was opened from, or home. */
    fun back()

    /** [source] is the tapped tile's bounds in window pixels, used for the launch animation. */
    fun launch(app: LauncherApp, source: Rect?)
    fun showAppInfo(app: LauncherApp)

    fun playPause()
    fun skipToNext()
    fun skipToPrevious()
    fun openPlayer()
    fun openMusic()

    fun dial(number: String)
    fun startProjection()
    fun openNavigationApp()
    fun openRadio()

    fun requestMediaAccess()
    fun requestDefaultHome()
    fun openSystemSettings()
    fun openBluetoothSettings()
    fun openWifiSettings()
    fun toggleSetting(key: String)
    fun changeLevel(key: String, direction: Int)

    fun refreshAdapterChoices()
    fun chooseObdAdapter(adapter: ObdAdapter)
    fun forgetObdAdapter()
    fun requestBluetoothPermission()
    fun requestBleScanPermission()
    fun scanForBleAdapters()
    fun stopBleScan()
    fun openLocationSettings()

    fun requestLocationPermission()
    fun searchPlaces(query: String)
    fun clearPlaceSearch()
    fun navigateTo(place: Place)
    fun retryRoute()
    fun endRoute()
    fun clearRecentPlaces()

    /** The demo cluster drove [seconds] at [speedKmh]; the demo car on the map follows suit. */
    fun advanceDemoDrive(seconds: Float, speedKmh: Int)
}
