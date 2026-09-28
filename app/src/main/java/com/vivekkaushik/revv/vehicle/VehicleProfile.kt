package com.vivekkaushik.revv.vehicle

/** Facts about the car that OBD-II doesn't report but fuel and gear estimates need. */
class VehicleProfile(
    val displacementLitres: Float,
    /** How fully the cylinders fill; used to estimate air flow on cars without a MAF sensor. */
    val volumetricEfficiency: Float,
    val tankLitres: Float,
    /** Engine rpm per km/h in each forward gear: gear ratio × final drive ÷ tyre circumference. */
    val rpmPerKmh: FloatArray,
    /** Economy assumed for the range estimate until the current trip has measured its own. */
    val typicalKmPerLitre: Float,
) {
    companion object {
        /**
         * Swift VXi 2015: K12M 1.2 petrol, 42 L tank, 165/80 R14 tyres, 5-speed gearbox with
         * ratios 3.545, 1.904, 1.280, 0.966, 0.757 and a 4.235 final drive.
         */
        val SWIFT_VXI_2015 = VehicleProfile(
            displacementLitres = 1.197f,
            volumetricEfficiency = 0.85f,
            tankLitres = 42f,
            rpmPerKmh = floatArrayOf(128.6f, 69.0f, 46.4f, 35.0f, 27.5f),
            typicalKmPerLitre = 18f,
        )
    }
}
