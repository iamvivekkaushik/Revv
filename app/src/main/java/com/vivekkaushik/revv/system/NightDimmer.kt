package com.vivekkaushik.revv.system

import android.content.Context
import androidx.core.content.edit
import com.vivekkaushik.revv.nav.LatLon
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.roundToInt

/** How Settings › Display › Auto night mode dims the screen at night. */
enum class NightMode {
    Off,

    /** Revv dims it from sunset to sunrise where the car is. */
    Sunset,

    /** Android's adaptive brightness dims it by the light sensor. */
    LightSensor;

    companion object {
        /** The light sensor, while Android's adaptive brightness is on, takes over from sunset dimming. */
        fun of(lightSensor: Boolean, sunset: Boolean): NightMode = when {
            lightSensor -> LightSensor
            sunset -> Sunset
            else -> Off
        }
    }
}

/**
 * Whether it's night, and when that changes; [until] is null while the sun stays up or down for
 * days. [located] is false while the times are a guess, until GPS has found the car.
 */
data class NightSchedule(val night: Boolean, val until: Instant?, val located: Boolean) {

    companion object {
        private val GUESSED_SUNRISE = LocalTime.of(6, 0)
        private val GUESSED_SUNSET = LocalTime.of(18, 30)

        /** Night from 18:30 to 06:00, for before GPS has found the car. */
        fun byClock(now: Instant, zone: ZoneId): NightSchedule {
            val local = now.atZone(zone)
            val sunrise = local.with(GUESSED_SUNRISE)
            val sunset = local.with(GUESSED_SUNSET)
            return when {
                local < sunrise -> NightSchedule(night = true, until = sunrise.toInstant(), located = false)
                local < sunset -> NightSchedule(night = false, until = sunset.toInstant(), located = false)
                else -> NightSchedule(night = true, until = sunrise.plusDays(1).toInstant(), located = false)
            }
        }
    }
}

/**
 * Sunset dimming's two brightness levels, in percent, and whether the night one is on screen.
 * Whatever the screen is at when day or night ends, set with Revv or with Android's slider, comes
 * back the next day or night.
 */
data class Dimming(val night: Boolean = false, val dayLevel: Int? = null, val nightLevel: Int? = null) {

    /**
     * Turning to night or day ([toNight]) with the screen at [current]: what to remember, and the
     * level to put on screen. Null when that's already showing.
     */
    fun turn(toNight: Boolean, current: Int): Pair<Dimming, Int>? = when {
        toNight == night -> null
        toNight -> Dimming(night = true, dayLevel = current, nightLevel = nightLevel) to (nightLevel ?: min(FIRST_NIGHT_LEVEL, current))
        else -> Dimming(night = false, dayLevel = dayLevel, nightLevel = current) to (dayLevel ?: current)
    }

    companion object {
        /** The first night's level, until the driver picks their own: dim, but easy to read. */
        const val FIRST_NIGHT_LEVEL = 40
    }
}

/**
 * Dims the screen from sunset to sunrise where the car is, for head units without a light sensor
 * to do it by. Call [update] at each sunrise and sunset and whenever the mode changes; it catches
 * up on any it missed while the head unit was off.
 */
class NightDimmer(context: Context) {

    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Keeps where the car is, to a tenth of a degree: close enough for sunset to the minute, and
     * no closer than the town.
     */
    fun locate(position: LatLon) {
        val lat = tenth(position.lat)
        val lon = tenth(position.lon)
        if (prefs.getFloat(LAT, Float.NaN) != lat || prefs.getFloat(LON, Float.NaN) != lon) {
            prefs.edit {
                putFloat(LAT, lat)
                putFloat(LON, lon)
            }
        }
    }

    /** Day or night now and until when: by the sun where the car was last seen, else by the clock. */
    fun schedule(now: Instant = Instant.now()): NightSchedule {
        val lat = prefs.getFloat(LAT, Float.NaN).toDouble()
        val lon = prefs.getFloat(LON, Float.NaN).toDouble()
        if (lat.isNaN() || lon.isNaN()) return NightSchedule.byClock(now, ZoneId.systemDefault())
        return NightSchedule(Sun.isDown(now, lat, lon), Sun.nextChange(now, lat, lon), located = true)
    }

    /** With sunset dimming [on], puts the day or night level on screen if it isn't already; off, the day level. */
    fun update(on: Boolean, now: Instant = Instant.now()): NightSchedule {
        val schedule = schedule(now)
        val dimming = Dimming(
            night = prefs.getBoolean(NIGHT, false),
            dayLevel = prefs.getInt(DAY_LEVEL, NONE).takeIf { it != NONE },
            nightLevel = prefs.getInt(NIGHT_LEVEL, NONE).takeIf { it != NONE },
        )
        dimming.turn(on && schedule.night, ScreenBrightness.percent(context))?.let { (kept, level) ->
            // Without leave to change the brightness, nothing changed: try again next time.
            if (ScreenBrightness.set(context, level)) {
                prefs.edit {
                    putBoolean(NIGHT, kept.night)
                    putInt(DAY_LEVEL, kept.dayLevel ?: NONE)
                    putInt(NIGHT_LEVEL, kept.nightLevel ?: NONE)
                }
            }
        }
        return schedule
    }

    private fun tenth(degrees: Double): Float = (degrees * 10).roundToInt() / 10f

    private companion object {
        const val PREFS = "nightDimming"
        const val LAT = "lat"
        const val LON = "lon"
        const val NIGHT = "night"
        const val DAY_LEVEL = "dayLevel"
        const val NIGHT_LEVEL = "nightLevel"
        const val NONE = -1
    }
}
