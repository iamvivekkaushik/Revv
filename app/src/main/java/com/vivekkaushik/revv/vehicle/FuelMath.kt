package com.vivekkaushik.revv.vehicle

import kotlin.math.min

/** Fuel consumption from engine sensors, for a petrol engine running at the stoichiometric mixture. */
object FuelMath {

    private const val AIR_FUEL_RATIO = 14.7f
    private const val PETROL_GRAMS_PER_LITRE = 740f
    private const val AIR_MOLAR_MASS = 28.97f
    private const val GAS_CONSTANT = 8.314f
    private const val MAX_KM_PER_LITRE = 99.9f

    /** Fuel flow in litres per hour for a mass air flow in grams per second. */
    fun litresPerHour(airGramsPerSecond: Float): Float =
        airGramsPerSecond / AIR_FUEL_RATIO * 3600f / PETROL_GRAMS_PER_LITRE

    /** Air flow estimated from manifold pressure and intake temperature ("speed density"). */
    fun airFlow(manifoldKpa: Int, rpm: Int, intakeAirC: Int, profile: VehicleProfile): Float {
        val kelvin = intakeAirC + 273.15f
        val molesPerSecond = rpm * manifoldKpa / kelvin / 120f * profile.volumetricEfficiency *
            profile.displacementLitres / GAS_CONSTANT
        return molesPerSecond * AIR_MOLAR_MASS
    }

    /** Distance per litre right now, or null while stationary or when flow is too small to trust. */
    fun kmPerLitre(speedKmh: Int, litresPerHour: Float): Float? {
        if (speedKmh < 1 || litresPerHour < 0.05f) return null
        return min(speedKmh / litresPerHour, MAX_KM_PER_LITRE)
    }
}
