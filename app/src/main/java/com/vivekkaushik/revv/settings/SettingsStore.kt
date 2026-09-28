package com.vivekkaushik.revv.settings

import android.content.Context
import androidx.core.content.edit
import com.vivekkaushik.revv.obd.ObdAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Switches, levels and the chosen OBD-II adapter from the Settings screen, persisted across restarts. */
data class HmiSettings(
    val toggles: Map<String, Boolean>,
    val levels: Map<String, Int>,
    val obdAdapter: ObdAdapter? = null,
) {
    fun isOn(key: String): Boolean = toggles[key] ?: false
    fun level(key: String): Int = levels[key] ?: 0

    val demoDrive: Boolean get() = isOn(SettingsStore.DEMO_DRIVE)
    val reducedMotion: Boolean get() = isOn(SettingsStore.REDUCED_MOTION)
    val touchFeedback: Boolean get() = isOn(SettingsStore.TOUCH_FEEDBACK)
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<HmiSettings> = _settings.asStateFlow()

    fun toggle(key: String) {
        val on = !_settings.value.isOn(key)
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

    private fun read() = HmiSettings(
        toggles = DEFAULT_TOGGLES.mapValues { (key, default) -> prefs.getBoolean(TOGGLE_PREFIX + key, default) },
        levels = DEFAULT_LEVELS.mapValues { (key, default) -> prefs.getInt(LEVEL_PREFIX + key, default) },
        obdAdapter = prefs.getString(OBD_ADDRESS, null)?.let { address ->
            ObdAdapter.restore(prefs.getString(OBD_KIND, null), address, prefs.getString(OBD_NAME, null) ?: address)
        },
    )

    companion object {
        const val DEMO_DRIVE = "demo"
        const val REDUCED_MOTION = "anim"
        const val TOUCH_FEEDBACK = "haptic"
        const val BRIGHTNESS = "bright"
        const val MEDIA_VOLUME = "vol"
        const val NAV_VOLUME = "navVol"
        const val AVOID_TOLLS = "avoidTolls"

        private const val TOGGLE_PREFIX = "toggle."
        private const val LEVEL_PREFIX = "level."
        private const val OBD_KIND = "obd.kind"
        private const val OBD_ADDRESS = "obd.address"
        private const val OBD_NAME = "obd.name"

        // Defaults from the design. Most are placeholders until the matching integration exists.
        private val DEFAULT_TOGGLES = mapOf(
            DEMO_DRIVE to true,
            "night" to true,
            REDUCED_MOTION to false,
            "autoVol" to true,
            TOUCH_FEEDBACK to true,
            "hotspot" to true,
            "tpms" to true,
            "shiftL" to true,
            AVOID_TOLLS to false,
            "traffic" to true,
            "autoUpd" to true,
        )
        private val DEFAULT_LEVELS = mapOf(BRIGHTNESS to 70, NAV_VOLUME to 18)
    }
}
