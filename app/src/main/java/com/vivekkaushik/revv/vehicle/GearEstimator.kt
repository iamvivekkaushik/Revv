package com.vivekkaushik.revv.vehicle

import kotlin.math.abs
import kotlin.math.ln

/**
 * Works out which gear a manual car is in from engine and road speed, since OBD-II doesn't say.
 * Returns the gear number, or 0 for neutral: stationary, or the clutch down while rolling.
 */
class GearEstimator(private val profile: VehicleProfile) {

    fun estimate(speedKmh: Int, rpm: Int): Int {
        if (speedKmh < MIN_SPEED_KMH || rpm < MIN_RPM) return 0
        val ratio = rpm.toFloat() / speedKmh
        var best = 0
        var bestError = Float.MAX_VALUE
        profile.rpmPerKmh.forEachIndexed { index, expected ->
            val error = abs(ln(ratio / expected))
            if (error < bestError) {
                best = index + 1
                bestError = error
            }
        }
        return if (bestError <= TOLERANCE) best else 0
    }

    private companion object {
        const val MIN_SPEED_KMH = 5
        const val MIN_RPM = 500

        /** Just under half the gap between 4th and 5th, the closest pair, in log terms. */
        const val TOLERANCE = 0.11f
    }
}
