package com.vivekkaushik.revv.vehicle

/** Facts about the car that OBD-II doesn't report but the fuel estimates need. */
class VehicleProfile(
    val displacementLitres: Float,
    /** How fully the cylinders fill; used to estimate air flow on cars without a MAF sensor. */
    val volumetricEfficiency: Float,
    val tankLitres: Float,
    /** Economy assumed for the range estimate until the current trip has measured its own. */
    val typicalKmPerLitre: Float,
) {
    companion object {
        /** Swift VXi 2015: K12M 1.2 petrol, 42 L tank. Its gearing is in [CarSetup.SWIFT_VXI_2015]. */
        val SWIFT_VXI_2015 = VehicleProfile(
            displacementLitres = 1.197f,
            volumetricEfficiency = 0.85f,
            tankLitres = 42f,
            typicalKmPerLitre = 18f,
        )
    }
}
