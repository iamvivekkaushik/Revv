package com.vivekkaushik.revv

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
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
import com.vivekkaushik.revv.phone.CallRoute
import com.vivekkaushik.revv.phone.PhoneMonitor
import com.vivekkaushik.revv.phone.PhoneState
import com.vivekkaushik.revv.settings.HmiSettings
import com.vivekkaushik.revv.settings.SettingsStore
import com.vivekkaushik.revv.system.BrightnessScale
import com.vivekkaushik.revv.system.FirstRun
import com.vivekkaushik.revv.system.HomeRole
import com.vivekkaushik.revv.system.NightDimmer
import com.vivekkaushik.revv.system.NightMode
import com.vivekkaushik.revv.system.NightSchedule
import com.vivekkaushik.revv.system.ScreenBrightness
import com.vivekkaushik.revv.ui.hmi.HmiApp
import com.vivekkaushik.revv.ui.hmi.ScreenState
import com.vivekkaushik.revv.ui.hmi.SystemState
import com.vivekkaushik.revv.vehicle.CarSetup
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
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
    private val phoneMonitor = PhoneMonitor(application, viewModelScope)
    private val nightDimmer = NightDimmer(application)
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
    val obdLog: StateFlow<List<String>> = obd.adapterLog
    val learntGears: StateFlow<List<Float>> = obd.learntGears

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

    val phone: StateFlow<PhoneState> = phoneMonitor.state

    init {
        // First, so the adapter log knows whether to save before the adapter says anything.
        viewModelScope.launch {
            settings.map { it.isOn(SettingsStore.SAVE_OBD_LOG) }.distinctUntilChanged().collect(obd::saveLogs)
        }
        // The gear estimate follows the car set up in Settings.
        viewModelScope.launch {
            settings.map { it.car }.distinctUntilChanged().collect(obd::setCar)
        }
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
        // Sunset dimming goes by the sun where the car is, as GPS has it rather than the demo car.
        viewModelScope.launch {
            navigator.state.collect { state -> if (!state.demo) state.fix?.position?.let(nightDimmer::locate) }
        }
        // Dims the screen at sunset and brightens it at sunrise, looking again every few minutes in
        // case the car or the clock has moved meanwhile.
        viewModelScope.launch {
            while (true) {
                val schedule = updateNightDimming()
                val untilChange = schedule.until?.let { Duration.between(Instant.now(), it).plusSeconds(1) } ?: NIGHT_CHECK
                delay(untilChange.coerceIn(Duration.ofSeconds(1), NIGHT_CHECK).toMillis())
            }
        }
    }

    override fun onCleared() {
        bleScanner.stop()
        obd.stop()
        navigator.stop()
        phoneMonitor.stop()
    }

    fun onStart() {
        refreshSystemState()
        media.start()
        navigator.start()
        phoneMonitor.start()
    }

    fun onStop() {
        media.stop()
        navigator.stop()
        phoneMonitor.stop()
    }

    fun refreshSystemState() {
        // First, so the brightness read below is the one on screen.
        nightDimmer.update(sunsetDimming())
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

    fun updateCar(car: CarSetup) = settingsStore.setCar(car)

    fun relearnGears() = obd.relearnGears()

    fun clearTroubleCodes() = obd.clearTroubleCodes()

    fun onBluetoothPermissionResult() {
        refreshSystemState()
        refreshAdapterChoices()
        phoneMonitor.refresh()
    }

    fun onLocationPermissionResult() = refreshSystemState()

    fun readPhoneAgain() = phoneMonitor.readAgain()

    /** Calls [number] on the phone paired over Bluetooth, never on the head unit itself. */
    fun call(number: String) {
        val dialable = number.filter { it.isDigit() || it in "+*#" }
        if (dialable.isEmpty()) return
        val context = getApplication<Application>()
        val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        when (phoneMonitor.call(dialable, onFailed = toast)) {
            CallRoute.Phone -> toast(context.getString(R.string.calling_on, phone.value.link?.name.orEmpty()))
            CallRoute.HeadUnit -> dialWithHeadUnit(dialable)
            CallRoute.None -> toast(context.getString(R.string.no_phone_to_call))
        }
    }

    /** Hands [number] to the head unit's dialer, whose hands-free link calls on the phone. */
    private fun dialWithHeadUnit(number: String) {
        val context = getApplication<Application>()
        val dial = Intent(Intent.ACTION_DIAL, "tel:${android.net.Uri.encode(number)}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(dial)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_dialer, Toast.LENGTH_SHORT).show()
        }
    }

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

    /** Saves the chosen option of a list setting such as the date format. */
    fun setChoice(key: String, index: Int) {
        if (key == SettingsStore.TIME_FORMAT || key == SettingsStore.DATE_FORMAT) settingsStore.setLevel(key, index.coerceAtLeast(0))
    }

    fun setDisplaySize(percent: Int) {
        if (percent in SettingsStore.DISPLAY_SIZES) settingsStore.setLevel(SettingsStore.DISPLAY_SIZE, percent)
    }

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
        if (key == SettingsStore.BRIGHTNESS) {
            // From the brightness now, which Android's own slider may have moved since Revv looked.
            val context = getApplication<Application>()
            ScreenBrightness.set(context, BrightnessScale.step(ScreenBrightness.percent(context), direction))
            refreshSystemState()
            return
        }
        val max = LEVEL_MAX[key] ?: return
        val step = if (max > 50) 10 else 3
        settingsStore.setLevel(key, (settings.value.level(key) + direction * step).coerceIn(0, max))
    }

    fun setNightMode(mode: NightMode) {
        ScreenBrightness.setAdaptive(getApplication(), mode == NightMode.LightSensor)
        settingsStore.setToggle(SettingsStore.SUNSET_DIMMING, mode == NightMode.Sunset)
        refreshSystemState()
    }

    /** Whether Auto night mode is dimming by the sun: set to, and Android's light sensor not doing it instead. */
    private fun sunsetDimming(): Boolean = NightMode.of(
        lightSensor = ScreenBrightness.isAdaptive(getApplication()),
        sunset = settings.value.isOn(SettingsStore.SUNSET_DIMMING),
    ) == NightMode.Sunset

    /** Dims or brightens the screen if the sun has set or risen since Revv last looked. */
    private fun updateNightDimming(): NightSchedule {
        val schedule = nightDimmer.update(sunsetDimming())
        _system.update { it.copy(nightSchedule = schedule, brightness = ScreenBrightness.percent(getApplication())) }
        return schedule
    }

    private fun readSystemState() = SystemState(
        hasMediaAccess = media.hasAccess(),
        isDefaultHome = HomeRole.isHeld(getApplication()),
        bluetoothOn = runCatching { bluetooth?.isEnabled == true }.getOrDefault(false),
        hasBluetoothPermission = BluetoothAccess.granted(getApplication()),
        pairedDevices = BluetoothAccess.pairedCount(getApplication()),
        canScanBle = BluetoothAccess.canScan(getApplication()),
        locationBlocksBleScan = BluetoothAccess.locationBlocksScanning(getApplication()),
        hasLocationPermission = DeviceLocation.permitted(getApplication()),
        locationOn = DeviceLocation.enabled(getApplication()),
        mediaVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        mediaVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        brightness = ScreenBrightness.percent(getApplication()),
        canChangeBrightness = ScreenBrightness.canChange(getApplication()),
        autoBrightnessAvailable = ScreenBrightness.canAdapt(getApplication()),
        autoBrightness = ScreenBrightness.isAdaptive(getApplication()),
        nightSchedule = nightDimmer.schedule(),
    )

    private companion object {
        const val ASK_TO_BE_HOME = "ask_to_be_home"
        val LEVEL_MAX = mapOf(SettingsStore.NAV_VOLUME to 30)

        /** How often sunset dimming looks again between sunrise and sunset. */
        val NIGHT_CHECK: Duration = Duration.ofMinutes(5)
    }
}
