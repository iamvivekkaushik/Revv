package com.vivekkaushik.revv.settings

import android.content.Context
import androidx.core.content.edit
import com.vivekkaushik.revv.apps.AppKey
import com.vivekkaushik.revv.engine.EngineLayout
import com.vivekkaushik.revv.engine.EngineSoundSettings
import com.vivekkaushik.revv.engine.ExhaustNote
import com.vivekkaushik.revv.obd.ObdAdapter
import com.vivekkaushik.revv.vehicle.CarColour
import com.vivekkaushik.revv.vehicle.CarSetup
import com.vivekkaushik.revv.vehicle.GearSource
import com.vivekkaushik.revv.vehicle.TyreSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Switches, levels, the chosen OBD-II adapter and the car from the Settings screen, persisted across restarts. */
data class HmiSettings(
    val toggles: Map<String, Boolean>,
    val levels: Map<String, Int>,
    val obdAdapter: ObdAdapter? = null,
    val car: CarSetup = CarSetup.SWIFT_VXI_2015,
    /** The app the home screen's fuel widget opens instead of showing the fuel range; null keeps the fuel range. */
    val fuelWidgetApp: AppKey? = null,
    /** The app Start projection opens; null picks Android Auto or the head unit's own projection app by name. */
    val projectionApp: AppKey? = null,
    /** The camera id the Rear Cam screen shows; null picks an external (USB) camera, else the back one. */
    val rearCameraId: String? = null,
    /** The driver's Google Maps Platform key, for Google place search; null when none was entered. */
    val googleApiKey: String? = null,
) {
    fun isOn(key: String): Boolean = toggles[key] ?: false
    fun level(key: String): Int = levels[key] ?: 0

    val demoDrive: Boolean get() = isOn(SettingsStore.DEMO_DRIVE)
    val reducedMotion: Boolean get() = isOn(SettingsStore.REDUCED_MOTION)
    val touchFeedback: Boolean get() = isOn(SettingsStore.TOUCH_FEEDBACK)

    /** How far the rear camera picture is turned: 0, 90, 180 or 270 degrees. */
    val rearCameraRotation: Int get() = (level(SettingsStore.CAMERA_ROTATION).coerceIn(0, 3)) * 90

    val menuSound: Boolean get() = isOn(SettingsStore.MENU_SOUND)
    val dialerSound: Boolean get() = isOn(SettingsStore.DIALER_SOUND)
    val keyboardSound: Boolean get() = isOn(SettingsStore.KEYBOARD_SOUND)

    /** Settings › Sound › Engine sound. */
    val engineSound: EngineSoundSettings
        get() = EngineSoundSettings(
            enabled = isOn(SettingsStore.ENGINE_SOUND),
            layout = EngineLayout.entries.getOrElse(level(SettingsStore.ENGINE_LAYOUT)) { EngineLayout.DEFAULT },
            note = ExhaustNote.entries.getOrElse(level(SettingsStore.ENGINE_EXHAUST)) { ExhaustNote.DEFAULT },
            volume = level(SettingsStore.ENGINE_VOLUME),
            bass = level(SettingsStore.ENGINE_BASS),
            crackle = isOn(SettingsStore.ENGINE_CRACKLE),
            surround = isOn(SettingsStore.ENGINE_SURROUND),
            smoothing = level(SettingsStore.ENGINE_SMOOTHING),
            startUp = isOn(SettingsStore.ENGINE_START),
        )

    val showFuelWidget: Boolean get() = isOn(SettingsStore.HOME_FUEL)
    val showPhoneCard: Boolean get() = isOn(SettingsStore.HOME_PHONE)
    val showMediaCard: Boolean get() = isOn(SettingsStore.HOME_MEDIA)

    /** Swiping the home media card scrubs through the track. */
    val mediaSwipeToSeek: Boolean get() = isOn(SettingsStore.HOME_MEDIA_SWIPE)
    val showMapPanel: Boolean get() = isOn(SettingsStore.HOME_MAP)

    /** 0 follows the system clock setting, 1 is 12-hour, 2 is 24-hour. */
    val timeFormat: Int get() = level(SettingsStore.TIME_FORMAT)

    /** Index into the date layouts offered in Settings; 0 is the design's "THU 01 OCT". */
    val dateFormat: Int get() = level(SettingsStore.DATE_FORMAT)

    /** Navigation's place search and routes: [SettingsStore.SEARCH_OSM] or [SettingsStore.SEARCH_GOOGLE]. */
    val placeSearch: Int get() = level(SettingsStore.PLACE_SEARCH)

    /** The key searches and routes go to Google with; null uses OpenStreetMap, as without a key. */
    val googleSearchKey: String? get() = googleApiKey?.takeIf { placeSearch == SettingsStore.SEARCH_GOOGLE }

    /** How big everything is drawn, in percent of the design size. */
    val displaySize: Int
        get() = (levels[SettingsStore.DISPLAY_SIZE] ?: SettingsStore.DISPLAY_SIZE_DEFAULT)
            .coerceIn(SettingsStore.DISPLAY_SIZES.first(), SettingsStore.DISPLAY_SIZES.last())
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<HmiSettings> = _settings.asStateFlow()

    fun toggle(key: String) = setToggle(key, !_settings.value.isOn(key))

    fun setToggle(key: String, on: Boolean) {
        prefs.edit { putBoolean(TOGGLE_PREFIX + key, on) }
        _settings.value = _settings.value.let { it.copy(toggles = it.toggles + (key to on)) }
    }

    fun setLevel(key: String, value: Int) {
        prefs.edit { putInt(LEVEL_PREFIX + key, value) }
        _settings.value = _settings.value.let { it.copy(levels = it.levels + (key to value)) }
    }

    fun setObdAdapter(adapter: ObdAdapter?) {
        prefs.edit {
            if (adapter == null) {
                remove(OBD_KIND)
                remove(OBD_ADDRESS)
                remove(OBD_NAME)
            } else {
                putString(OBD_KIND, adapter.kind.name)
                putString(OBD_ADDRESS, adapter.address)
                putString(OBD_NAME, adapter.name)
            }
        }
        _settings.value = _settings.value.copy(obdAdapter = adapter)
    }

    fun setFuelWidgetApp(app: AppKey?) {
        prefs.edit { if (app == null) remove(FUEL_WIDGET_APP) else putString(FUEL_WIDGET_APP, app.serialize()) }
        _settings.value = _settings.value.copy(fuelWidgetApp = app)
    }

    fun setGoogleApiKey(key: String?) {
        prefs.edit { if (key == null) remove(GOOGLE_API_KEY) else putString(GOOGLE_API_KEY, key) }
        _settings.value = _settings.value.copy(googleApiKey = key)
    }

    fun setRearCameraId(id: String?) {
        prefs.edit { if (id == null) remove(REAR_CAMERA) else putString(REAR_CAMERA, id) }
        _settings.value = _settings.value.copy(rearCameraId = id)
    }

    fun setProjectionApp(app: AppKey?) {
        prefs.edit { if (app == null) remove(PROJECTION_APP) else putString(PROJECTION_APP, app.serialize()) }
        _settings.value = _settings.value.copy(projectionApp = app)
    }

    fun setCar(car: CarSetup) {
        prefs.edit {
            putString(CAR_MAKE, car.make)
            putString(CAR_MODEL, car.model)
            putString(CAR_COLOUR, car.colour.name)
            putInt(CAR_GEARS, car.gears)
            putString(CAR_GEAR_SOURCE, car.gearSource.name)
            putString(CAR_TYRE, "${car.tyre.widthMm}/${car.tyre.aspectPercent}/${car.tyre.rimInches}")
            putString(CAR_RATIOS, car.ratios.joinToString(","))
            putFloat(CAR_FINAL_DRIVE, car.finalDrive)
        }
        _settings.value = _settings.value.copy(car = car)
    }

    /** The saved car, with the Swift's details standing in for anything never set or unreadable. */
    private fun readCar(): CarSetup {
        val default = CarSetup.SWIFT_VXI_2015
        val tyre = prefs.getString(CAR_TYRE, null)?.split('/')?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == 3 }
            ?.let { (width, aspect, rim) -> TyreSize(width, aspect, rim) }
            ?.takeIf { it.valid }
        val ratios = prefs.getString(CAR_RATIOS, null)?.split(',')?.mapNotNull(String::toFloatOrNull)?.takeIf { it.isNotEmpty() }
        return CarSetup(
            make = prefs.getString(CAR_MAKE, null) ?: default.make,
            model = prefs.getString(CAR_MODEL, null) ?: default.model,
            colour = CarColour.entries.firstOrNull { it.name == prefs.getString(CAR_COLOUR, null) } ?: default.colour,
            gears = default.gears,
            gearSource = GearSource.entries.firstOrNull { it.name == prefs.getString(CAR_GEAR_SOURCE, null) } ?: default.gearSource,
            tyre = tyre ?: default.tyre,
            ratios = ratios ?: default.ratios,
            finalDrive = prefs.getFloat(CAR_FINAL_DRIVE, default.finalDrive),
        ).withGears(prefs.getInt(CAR_GEARS, default.gears))
    }

    private fun read() = HmiSettings(
        toggles = DEFAULT_TOGGLES.mapValues { (key, default) -> prefs.getBoolean(TOGGLE_PREFIX + key, default) },
        levels = DEFAULT_LEVELS.mapValues { (key, default) -> prefs.getInt(LEVEL_PREFIX + key, default) },
        obdAdapter = prefs.getString(OBD_ADDRESS, null)?.let { address ->
            ObdAdapter.restore(prefs.getString(OBD_KIND, null), address, prefs.getString(OBD_NAME, null) ?: address)
        },
        car = readCar(),
        fuelWidgetApp = prefs.getString(FUEL_WIDGET_APP, null)?.let(AppKey::parse),
        projectionApp = prefs.getString(PROJECTION_APP, null)?.let(AppKey::parse),
        rearCameraId = prefs.getString(REAR_CAMERA, null),
        googleApiKey = prefs.getString(GOOGLE_API_KEY, null),
    )

    companion object {
        const val DEMO_DRIVE = "demo"
        const val REDUCED_MOTION = "anim"

        /** The rev counter in whole rpm (900) rather than thousands (0.9). */
        const val FULL_RPM = "fullRpm"
        const val TOUCH_FEEDBACK = "haptic"
        const val HOME_FUEL = "homeFuel"
        const val MENU_SOUND = "menuSound"
        const val DIALER_SOUND = "dialerSound"
        const val KEYBOARD_SOUND = "keyboardSound"

        /** A synthesised engine through the speakers, following the car's rpm. */
        const val ENGINE_SOUND = "engineSound"
        /** Which [EngineLayout], by position. */
        const val ENGINE_LAYOUT = "engineLayout"
        /** Which [ExhaustNote], by position. */
        const val ENGINE_EXHAUST = "engineExhaust"
        const val ENGINE_VOLUME = "engineVol"
        /** How much to turn up the engine's low end. */
        const val ENGINE_BASS = "engineBass"
        /** Pops from the exhaust on lifting off at high revs. */
        const val ENGINE_CRACKLE = "engineCrackle"
        /** The engine in stereo, each bank's pipe on its own side. */
        const val ENGINE_SURROUND = "engineSurround"
        /** How the revs glide between the car's readings. */
        const val ENGINE_SMOOTHING = "engineSmoothing"
        /** The starter and the engine catching when the car starts. */
        const val ENGINE_START = "engineStart"
        const val CAMERA_ROTATION = "cameraRotation"
        const val HOME_PHONE = "homePhone"
        const val HOME_MEDIA = "homeMedia"
        const val HOME_MEDIA_SWIPE = "homeMediaSwipe"
        const val HOME_MAP = "homeMap"
        const val BRIGHTNESS = "bright"

        /** Auto night mode set to dim from sunset to sunrise; the light sensor overrides it when on. */
        const val SUNSET_DIMMING = "sunsetDim"
        const val DISPLAY_SIZE = "displaySize"
        const val TIME_FORMAT = "timeFormat"
        const val DATE_FORMAT = "dateFormat"
        const val DISPLAY_SIZE_DEFAULT = 100
        val DISPLAY_SIZES = listOf(80, 90, 100, 110, 120, 130, 140, 150, 160)
        const val MEDIA_VOLUME = "vol"
        const val NAV_VOLUME = "navVol"
        const val AVOID_TOLLS = "avoidTolls"
        const val SAVE_OBD_LOG = "obdLog"
        /** CarPlay fills the Auto screen instead of sharing it with its status column. */
        const val CARPLAY_WIDE = "carplayWide"
        /** Without wide screen, the Auto screen's side column shows CarPlay's settings instead of its status and controls. */
        const val CARPLAY_SETTINGS_BESIDE = "carplaySettingsBeside"
        /** Without wide screen, CarPlay sits right of its side column instead of left. */
        const val CARPLAY_ON_RIGHT = "carplayOnRight"
        /**
         * Experimental: Revv's map routes to where CarPlay guides, found by the destination's name,
         * and shows CarPlay's turns without a match. Off, Revv's map ignores CarPlay's route.
         */
        const val CARPLAY_FOLLOW_ROUTE = "carplayFollowRoute"
        /** Experimental: with [CARPLAY_FOLLOW_ROUTE] off, Revv's map still shows CarPlay's turns and ETA. */
        const val CARPLAY_SHOW_TURNS = "carplayShowTurns"
        /** Which services Navigation's place search and routes use. */
        const val PLACE_SEARCH = "placeSearch"
        const val SEARCH_OSM = 0
        const val SEARCH_GOOGLE = 1

        private const val TOGGLE_PREFIX = "toggle."
        private const val LEVEL_PREFIX = "level."
        private const val REAR_CAMERA = "camera.rearId"
        private const val PROJECTION_APP = "auto.projectionApp"
        private const val FUEL_WIDGET_APP = "home.fuelWidgetApp"
        private const val GOOGLE_API_KEY = "search.googleApiKey"
        private const val OBD_KIND = "obd.kind"
        private const val OBD_ADDRESS = "obd.address"
        private const val OBD_NAME = "obd.name"
        private const val CAR_MAKE = "car.make"
        private const val CAR_MODEL = "car.model"
        private const val CAR_COLOUR = "car.colour"
        private const val CAR_GEARS = "car.gears"
        private const val CAR_GEAR_SOURCE = "car.gearSource"
        private const val CAR_TYRE = "car.tyre"
        private const val CAR_RATIOS = "car.ratios"
        private const val CAR_FINAL_DRIVE = "car.finalDrive"

        // Defaults from the design. Most are placeholders until the matching integration exists.
        private val DEFAULT_TOGGLES = mapOf(
            CARPLAY_WIDE to false,
            CARPLAY_SETTINGS_BESIDE to false,
            CARPLAY_ON_RIGHT to false,
            CARPLAY_FOLLOW_ROUTE to false,
            CARPLAY_SHOW_TURNS to false,
            DEMO_DRIVE to true,
            HOME_FUEL to true,
            MENU_SOUND to true,
            DIALER_SOUND to true,
            KEYBOARD_SOUND to true,
            ENGINE_SOUND to false,
            ENGINE_CRACKLE to true,
            ENGINE_SURROUND to true,
            ENGINE_START to true,
            HOME_PHONE to true,
            HOME_MEDIA to true,
            HOME_MEDIA_SWIPE to false,
            HOME_MAP to true,
            SUNSET_DIMMING to false,
            REDUCED_MOTION to false,
            FULL_RPM to false,
            TOUCH_FEEDBACK to true,
            AVOID_TOLLS to false,
            SAVE_OBD_LOG to true,
        )
        private val DEFAULT_LEVELS = mapOf(
            NAV_VOLUME to 18,
            DISPLAY_SIZE to DISPLAY_SIZE_DEFAULT,
            TIME_FORMAT to 0,
            DATE_FORMAT to 0,
            CAMERA_ROTATION to 0,
            PLACE_SEARCH to SEARCH_OSM,
            ENGINE_LAYOUT to EngineLayout.DEFAULT.ordinal,
            ENGINE_EXHAUST to ExhaustNote.DEFAULT.ordinal,
            ENGINE_VOLUME to EngineSoundSettings.DEFAULT_VOLUME,
            ENGINE_BASS to EngineSoundSettings.DEFAULT_BASS,
            ENGINE_SMOOTHING to EngineSoundSettings.DEFAULT_SMOOTHING,
        )
    }
}
