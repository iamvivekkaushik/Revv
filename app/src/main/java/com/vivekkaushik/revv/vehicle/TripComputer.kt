package com.vivekkaushik.revv.vehicle

import kotlin.math.min

/** Adds up distance, fuel and engine time from successive samples into trip figures. */
class TripComputer {

    var distanceKm = 0.0
        private set
    var fuelLitres = 0.0
        private set
    var engineSeconds = 0.0
        private set

    /** Average economy, once the trip is long enough for it to mean something. */
    val averageKmPerLitre: Float?
        get() = if (distanceKm >= MIN_DISTANCE_KM && fuelLitres > 0.0) (distanceKm / fuelLitres).toFloat() else null

    /**
     * Records [seconds] at the given speed and fuel flow. Long gaps (a stalled link) are capped so
     * they can't add phantom distance.
     */
    fun add(seconds: Double, speedKmh: Int, litresPerHour: Float?, engineRunning: Boolean) {
        val hours = min(seconds, MAX_SAMPLE_SECONDS).coerceAtLeast(0.0) / 3600.0
        distanceKm += speedKmh * hours
        if (litresPerHour != null) fuelLitres += litresPerHour * hours
        if (engineRunning) engineSeconds += hours * 3600.0
    }

    private companion object {
        const val MAX_SAMPLE_SECONDS = 2.0
        const val MIN_DISTANCE_KM = 0.5
    }
}
