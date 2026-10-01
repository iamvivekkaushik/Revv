package com.vivekkaushik.revv.system

import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sun is, by the Astronomical Almanac's low-precision formulas: good to a hundredth of a
 * degree this century, which puts sunrise and sunset within a minute.
 */
object Sun {

    /** The sun's height above the horizon in degrees at [instant], seen from [latitude], [longitude]. */
    fun elevation(instant: Instant, latitude: Double, longitude: Double): Double {
        val days = (instant.toEpochMilli() - J2000_MILLIS) / MILLIS_PER_DAY
        val meanLongitude = 280.460 + 0.9856474 * days
        val meanAnomaly = Math.toRadians(357.528 + 0.9856003 * days)
        val eclipticLongitude = Math.toRadians(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly))
        val obliquity = Math.toRadians(23.439 - 0.0000004 * days)
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val siderealTime = Math.toRadians(280.46061837 + 360.98564736629 * days)
        val hourAngle = siderealTime + Math.toRadians(longitude) - rightAscension
        val lat = Math.toRadians(latitude)
        return Math.toDegrees(asin(sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)))
    }

    /** Whether the sun has set at [instant]: its top edge below the horizon, allowing for refraction. */
    fun isDown(instant: Instant, latitude: Double, longitude: Double): Boolean =
        elevation(instant, latitude, longitude) < HORIZON

    /** The next sunrise or sunset after [from]; null while the sun stays up or down for days, near the poles. */
    fun nextChange(from: Instant, latitude: Double, longitude: Double): Instant? {
        val down = isDown(from, latitude, longitude)
        var before = from
        repeat(SEARCH_STEPS) {
            val after = before.plus(STEP)
            if (isDown(after, latitude, longitude) != down) {
                // Narrow it down to the first moment after the change.
                var changed = after
                while (Duration.between(before, changed) > PRECISION) {
                    val middle = before.plus(Duration.between(before, changed).dividedBy(2))
                    if (isDown(middle, latitude, longitude) == down) before = middle else changed = middle
                }
                return changed
            }
            before = after
        }
        return null
    }

    /** Refraction lifts the sun about 34′, and its top edge shows 16′ before its centre. */
    private const val HORIZON = -0.833
    private val STEP = Duration.ofMinutes(10)
    private val PRECISION = Duration.ofSeconds(15)

    /** Two days of steps: long enough to cross any night outside the polar circles. */
    private const val SEARCH_STEPS = 2 * 24 * 6

    /** Noon on 1 January 2000, the epoch of the formulas. */
    private const val J2000_MILLIS = 946_728_000_000L
    private const val MILLIS_PER_DAY = 86_400_000.0
}
