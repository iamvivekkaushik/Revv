package com.vivekkaushik.revv

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekkaushik.revv.apps.AppRepository
import com.vivekkaushik.revv.apps.IconProvider
import com.vivekkaushik.revv.apps.LauncherApp
import com.vivekkaushik.revv.media.MediaSessionMonitor
import com.vivekkaushik.revv.media.NowPlaying
import com.vivekkaushik.revv.nav.DeviceLocation
import com.vivekkaushik.revv.nav.NavState
import com.vivekkaushik.revv.nav.Navigator
import com.vivekkaushik.revv.nav.Place
import com.vivekkaushik.revv.obd.BleScanner
import com.vivekkaushik.revv.obd.BluetoothAccess
import com.vivekkaushik.revv.obd.ObdAdapter
import com.vivekkaushik.revv.obd.ObdLink
import com.vivekkaushik.revv.obd.ObdReadings
import com.vivekkaushik.revv.obd.ObdSession
import com.vivekkaushik.revv.obd.ObdStatus
import com.vivekkaushik.revv.settings.HmiSettings
import com.vivekkaushik.revv.settings.SettingsStore
import com.vivekkaushik.revv.system.FirstRun
import com.vivekkaushik.revv.system.HomeRole
import com.vivekkaushik.revv.ui.hmi.HmiApp
import com.vivekkaushik.revv.ui.hmi.ScreenState
import com.vivekkaushik.revv.ui.hmi.SystemState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val appRepository = AppRepository(application)
    private val media = MediaSessionMonitor(application)
    private val settingsStore = SettingsStore(application)
    private val firstRun = FirstRun(application)
    private val audio = application.getSystemService(AudioManager::class.java)
    private val bluetooth = application.getSystemService(BluetoothManager::class.java)?.adapter
    private val obd = ObdSession(application, viewModelScope)
    private val bleScanner = BleScanner(application)
    private val navigator = Navigator(application, viewModelScope)
    private val isDebugBuild = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    val icons: IconProvider = appRepository

    val apps: StateFlow<List<LauncherApp>> = appRepository.apps
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val nowPlaying: StateFlow<NowPlaying?> = media.nowPlaying

    val settings: StateFlow<HmiSettings> = settingsStore.settings

    private val _system = MutableStateFlow(readSystemState())
    val system: StateFlow<SystemState> = _system.asStateFlow()

    private val _screen = MutableStateFlow(ScreenState())
    val screen: StateFlow<ScreenState> = _screen.asStateFlow()

    val obdStatus: StateFlow<ObdStatus> = obd.status
    val obdReadings: StateFlow<ObdReadings?> = obd.readings

    private val pairedAdapters = MutableStateFlow<List<ObdAdapter>>(emptyList())

    /**
     * Adapters the user can pick: Wi-Fi, paired Bluetooth devices, Bluetooth LE devices found by the
     * last scan, and in debug builds a simulated one.
     */
    val adapterChoices: StateFlow<List<ObdAdapter>> = combine(pairedAdapters, bleScanner.found) { paired, scanned ->
        val pairedAddresses = paired.map { it.address }.toSet()
        listOf(ObdAdapter.WiFi) + paired + scanned.filter { it.address !in pairedAddresses } +
            listOfNotNull(ObdAdapter.Simulated.takeIf { isDebugBuild })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(ObdAdapter.WiFi))

    val bleScanning: StateFlow<Boolean> = bleScanner.scanning

    val navigation: StateFlow<NavState> = navigator.state
    val recentPlaces: StateFlow<List<Place>> = navigator.recents

    init {
        // Stay connected to whichever adapter is chosen, from launch onwards.
        viewModelScope.launch {
            settings.map { it.obdAdapter }.distinctUntilChanged().collect { adapter ->
                if (adapter == null) obd.stop() else obd.start(adapter)
            }
        }
        // The map's demo car runs with the demo drive: on, and no adapter to show the real car.
        viewModelScope.launch {
            settings.map { (it.demoDrive && it.obdAdapter == null) to it.isOn(SettingsStore.AVOID_TOLLS) }
                .distinctUntilChanged()
                .collect { (demo, avoidTolls) ->
                    navigator.setDemoAllowed(demo)
                    navigator.avoidTolls = avoidTolls
                }
        }
    }

    override fun onCleared() {
        bleScanner.stop()
        obd.stop()
        navigator.stop()
    }

    fun onStart() {
        refreshSystemState()
        media.start()
        navigator.start()
    }

    fun onStop() {
        media.stop()
        navigator.stop()
    }

    fun refreshSystemState() {
        _system.value = readSystemState()
        navigator.refreshAccess()
        // Bluetooth access may have been granted from Android's settings while Revv was away.
        val adapter = settings.value.obdAdapter
        if (adapter != null && obd.status.value.link == ObdLink.NeedsPermission && _system.value.hasBluetoothPermission) {
            obd.start(adapter)
        }
    }

    fun refreshAdapterChoices() {
        pairedAdapters.value = BluetoothAccess.pairedAdapters(getApplication())
    }

    fun scanForBleAdapters() = bleScanner.start()

    fun stopBleScan() = bleScanner.stop()

    fun chooseObdAdapter(adapter: ObdAdapter) = settingsStore.setObdAdapter(adapter)

    fun forgetObdAdapter() = settingsStore.setObdAdapter(null)

    fun onBluetoothPermissionResult() {
        refreshSystemState()
        refreshAdapterChoices()
    }

    fun onLocationPermissionResult() = refreshSystemState()

    fun searchPlaces(query: String) = navigator.search(query)
    fun clearPlaceSearch() = navigator.clearSearch()
    fun navigateTo(place: Place) = navigator.navigateTo(place)
    fun retryRoute() = navigator.retryRoute()
    fun endRoute() = navigator.endRoute()
    fun clearRecentPlaces() = navigator.clearRecents()
    fun advanceDemoDrive(seconds: Float, speedKmh: Int) = navigator.advanceDemo(seconds.toDouble(), speedKmh)

    /** True once per install, the first time Revv comes up without being the default home app. */
    fun shouldAskToBeHome(): Boolean = !HomeRole.isHeld(getApplication()) && firstRun.claim(ASK_TO_BE_HOME)

    fun open(app: HmiApp) = _screen.update { it.open(app) }

    fun back() = _screen.update { it.back() }

    /** [animate] false removes the open screen at once, for when Revv isn't on screen to show it. */
    fun goHome(animate: Boolean = true) = _screen.update { it.home(animate) }

    fun launch(app: LauncherApp, sourceBounds: Rect?, options: Bundle?) {
        if (!appRepository.launch(app, sourceBounds, options)) {
            val context = getApplication<Application>()
            Toast.makeText(context, context.getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppInfo(app: LauncherApp) = appRepository.openAppInfo(app)

    fun playPause() = media.playPause()
    fun skipToNext() = media.skipToNext()
    fun skipToPrevious() = media.skipToPrevious()
    fun openPlayer(): Boolean = media.openPlayer()

    fun toggleSetting(key: String) = settingsStore.toggle(key)

    /** Steps a level setting by one notch in [direction] (-1 or 1). */
    fun changeLevel(key: String, direction: Int) {
        if (key == SettingsStore.MEDIA_VOLUME) {
            val max = _system.value.mediaVolumeMax
            val step = (max / 10).coerceAtLeast(1)
            val volume = (_system.value.mediaVolume + direction * step).coerceIn(0, max)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
            refreshSystemState()
            return
        }
        val max = LEVEL_MAX[key] ?: return
        val step = if (max > 50) 10 else 3
        settingsStore.setLevel(key, (settings.value.level(key) + direction * step).coerceIn(0, max))
    }

    private fun readSystemState() = SystemState(
        hasMediaAccess = media.hasAccess(),
        isDefaultHome = HomeRole.isHeld(getApplication()),
        bluetoothOn = runCatching { bluetooth?.isEnabled == true }.getOrDefault(false),
        hasBluetoothPermission = BluetoothAccess.granted(getApplication()),
        canScanBle = BluetoothAccess.canScan(getApplication()),
        locationBlocksBleScan = BluetoothAccess.locationBlocksScanning(getApplication()),
        hasLocationPermission = DeviceLocation.permitted(getApplication()),
        locationOn = DeviceLocation.enabled(getApplication()),
        mediaVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        mediaVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
    )

    private companion object {
        const val ASK_TO_BE_HOME = "ask_to_be_home"
        val LEVEL_MAX = mapOf(SettingsStore.BRIGHTNESS to 100, SettingsStore.NAV_VOLUME to 30)
    }
}
