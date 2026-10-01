package com.vivekkaushik.revv.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import androidx.core.content.edit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The screen's backlight, which Android keeps as system settings so every app shows at it: its
 * level, and whether the light sensor sets it instead. Revv may change them once the user lets it
 * modify system settings, a switch on a page of Android's.
 */
object ScreenBrightness {

    private const val PREFS = "brightness"
    private const val HIGHEST_SEEN = "highestSeen"
    private const val MANUAL = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
    private const val AUTOMATIC = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    private val declared by lazy { BrightnessScale.ofDevice() }

    fun canChange(context: Context): Boolean = Settings.System.canWrite(context)

    /** The backlight as a percentage along Android's own brightness slider. */
    fun percent(context: Context): Int {
        val setting = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, declared.max)
        return scale(context, setting).percentOf(setting)
    }

    /** Sets the backlight to [percent] along Android's slider; false if Revv may not. */
    fun set(context: Context, percent: Int): Boolean = runCatching {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, scale(context).settingOf(percent))
    }.getOrDefault(false)

    /** Whether this device can set the backlight by ambient light, which takes a light sensor. */
    @SuppressLint("DiscouragedApi") // The framework's configuration has no other way in.
    fun canAdapt(context: Context): Boolean {
        val sensor = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
        val resources = Resources.getSystem()
        val configured = runCatching {
            resources.getBoolean(resources.getIdentifier("config_automatic_brightness_available", "bool", "android"))
        }.getOrDefault(true)
        return sensor != null && configured
    }

    /** Whether Android is setting the backlight by ambient light, its adaptive brightness. */
    fun isAdaptive(context: Context): Boolean = canAdapt(context) &&
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, MANUAL) == AUTOMATIC

    /** Turns adaptive brightness on or off; false if Revv may not. */
    fun setAdaptive(context: Context, on: Boolean): Boolean = runCatching {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) AUTOMATIC else MANUAL)
    }.getOrDefault(false)

    /**
     * The range Android declares, widened to the highest setting ever seen: some makers keep the
     * setting on a finer scale than they declare, OnePlus in the thousands against a declared 255.
     */
    private fun scale(context: Context, seen: Int = 0): BrightnessScale {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val highest = prefs.getInt(HIGHEST_SEEN, 0)
        if (seen > highest) prefs.edit { putInt(HIGHEST_SEEN, seen) }
        return declared.widenedFor(max(seen, highest))
    }
}

/**
 * Android's brightness slider over the backlight settings from [min] to [max]. The slider follows
 * a perceptual curve, Hybrid Log-Gamma as in Android's own BrightnessUtils, so each step along it
 * looks as big as the last; the setting it writes is linear in backlight.
 */
class BrightnessScale(val min: Int, val max: Int) {

    /** The backlight setting [percent] of the way along the slider. */
    fun settingOf(percent: Int): Int {
        val slider = percent.coerceIn(0, 100) / 100.0
        val linear = if (slider <= R) (slider / R) * (slider / R) else exp((slider - C) / A) + B
        return (min + (max - min) * linear / HLG_MAX).roundToInt()
    }

    /**
     * How far along the slider [setting] is, in percent. Settings at the dim end are too coarse to
     * tell 19% from 20%, so where one of Revv's own steps gives this very setting, it's that step.
     */
    fun percentOf(setting: Int): Int {
        val linear = (setting - min).coerceIn(0, max - min) * HLG_MAX / (max - min)
        val slider = if (linear <= 1) sqrt(linear) * R else A * ln(linear - B) + C
        val exact = (slider * 100).coerceIn(0.0, 100.0)
        val step = (exact / STEP).roundToInt() * STEP
        return if (settingOf(step) == setting) step else exact.roundToInt()
    }

    /** This scale, or one reaching the next power of two up when [setting] is past its top. */
    fun widenedFor(setting: Int): BrightnessScale =
        if (setting <= max) this else BrightnessScale(min, (setting.takeHighestOneBit() shl 1) - 1)

    companion object {
        /** Revv's brightness buttons move a tenth of the slider at a time. */
        const val STEP = 10

        /** Android's own range, for a device whose configuration can't be read. */
        private const val DEFAULT_MIN = 10
        private const val DEFAULT_MAX = 255

        // Hybrid Log-Gamma, which Android scales to 0..12 rather than 0..1.
        private const val R = 0.5
        private const val A = 0.17883277
        private const val B = 0.28466892
        private const val C = 0.55991073
        private const val HLG_MAX = 12.0

        /** One step from [percent] in [direction] (-1 or 1), landing back on the tens if Android's slider left it between. */
        fun step(percent: Int, direction: Int): Int {
            val next = if (direction > 0) (percent / STEP + 1) * STEP else ((percent + STEP - 1) / STEP - 1) * STEP
            return next.coerceIn(0, 100)
        }

        /** The range this device lets the brightness setting take, from Android's own configuration. */
        @SuppressLint("DiscouragedApi") // The framework's configuration has no other way in.
        fun ofDevice(): BrightnessScale {
            val resources = Resources.getSystem()
            fun config(name: String, default: Int): Int = runCatching {
                resources.getInteger(resources.getIdentifier(name, "integer", "android"))
            }.getOrDefault(default)
            val min = config("config_screenBrightnessSettingMinimum", DEFAULT_MIN)
            val max = config("config_screenBrightnessSettingMaximum", DEFAULT_MAX)
            return if (max > min) BrightnessScale(min, max) else BrightnessScale(DEFAULT_MIN, DEFAULT_MAX)
        }
    }
}
