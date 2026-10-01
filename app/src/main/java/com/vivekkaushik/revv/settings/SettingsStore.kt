package com.vivekkaushik.revv.settings

import android.content.Context
import androidx.core.content.edit
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
) {
    fun isOn(key: String): Boolean = toggles[key] ?: false
    fun level(key: String): Int = levels[key] ?: 0

    val demoDrive: Boolean get() = isOn(SettingsStore.DEMO_DRIVE)
    val reducedMotion: Boolean get() = isOn(SettingsStore.REDUCED_MOTION)
    val touchFeedback: Boolean get() = isOn(SettingsStore.TOUCH_FEEDBACK)

    /** 0 follows the system clock setting, 1 is 12-hour, 2 is 24-hour. */
    val timeFormat: Int get() = level(SettingsStore.TIME_FORMAT)

    /** Index into the date layouts offered in Settings; 0 is the design's "THU 01 OCT". */
    val dateFormat: Int get() = level(SettingsStore.DATE_FORMAT)

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
    )

    companion object {
        const val DEMO_DRIVE = "demo"
        const val REDUCED_MOTION = "anim"
        const val TOUCH_FEEDBACK = "haptic"
        const val BRIGHTNESS = "bright"

        /** Auto night mode set to dim from sunset to sunrise; the light sensor overrides it when on. */
        const val SUNSET_DIMMING = "sunsetDim"
        const val DISPLAY_SIZE = "displaySize"
        const val TIME_FORMAT = "timeFormat"
        const val DATE_FORMAT = "dateFormat"
        const val DISPLAY_SIZE_DEFAULT = 100
        val DISPLAY_SIZES = listOf(80, 90, 100, 110, 120, 130)
        const val MEDIA_VOLUME = "vol"
        const val NAV_VOLUME = "navVol"
        const val AVOID_TOLLS = "avoidTolls"
        const val SAVE_OBD_LOG = "obdLog"

        private const val TOGGLE_PREFIX = "toggle."
        private const val LEVEL_PREFIX = "level."
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
            DEMO_DRIVE to true,
            SUNSET_DIMMING to false,
            REDUCED_MOTION to false,
            "autoVol" to true,
            TOUCH_FEEDBACK to true,
            "hotspot" to true,
            "tpms" to true,
            "shiftL" to true,
            AVOID_TOLLS to false,
            SAVE_OBD_LOG to true,
            "traffic" to true,
            "autoUpd" to true,
        )
        private val DEFAULT_LEVELS = mapOf(NAV_VOLUME to 18, DISPLAY_SIZE to DISPLAY_SIZE_DEFAULT, TIME_FORMAT to 0, DATE_FORMAT to 0)
    }
}
