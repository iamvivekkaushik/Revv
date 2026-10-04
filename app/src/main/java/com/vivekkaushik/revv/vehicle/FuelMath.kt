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

    /**
     * How full the cylinders are, the share of a sea-level cylinderful of air each one takes in:
     * about 0.2 with the throttle shut, 1 wide open, above 1 on boost. From manifold pressure, or
     * from the MAF sensor against what the engine would draw at that rpm. Null below idle.
     */
    fun airFill(maf: Float?, manifoldKpa: Int?, rpm: Int?, intakeAirC: Int, profile: VehicleProfile): Float? = when {
        manifoldKpa != null -> manifoldKpa / SEA_LEVEL_KPA
        maf != null && rpm != null && rpm >= MIN_FILL_RPM -> {
            val gramsPerLitre = SEA_LEVEL_KPA * AIR_MOLAR_MASS / (GAS_CONSTANT * (intakeAirC + 273.15f))
            maf / (rpm / 120f * profile.displacementLitres * gramsPerLitre)
        }
        else -> null
    }

    private const val SEA_LEVEL_KPA = 101.325f
    private const val MIN_FILL_RPM = 300

    /** Distance per litre right now, or null while stationary or when flow is too small to trust. */
    fun kmPerLitre(speedKmh: Int, litresPerHour: Float): Float? {
        if (speedKmh < 1 || litresPerHour < 0.05f) return null
        return min(speedKmh / litresPerHour, MAX_KM_PER_LITRE)
    }
}
