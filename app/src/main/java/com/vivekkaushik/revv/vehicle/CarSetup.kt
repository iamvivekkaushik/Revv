package com.vivekkaushik.revv.vehicle

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt

/** How the gear indicator knows the car's gears. */
enum class GearSource {
    /** Worked out from the tyre size and gearbox ratios entered in Settings. */
    Ratios,

    /** Learnt from the car's own engine and road speeds as it's driven. */
    Learnt,
}

/** A tyre size as printed on its sidewall: 165/80 R14 is 165 mm wide, its sidewall 80% of that, on a 14-inch rim. */
data class TyreSize(val widthMm: Int, val aspectPercent: Int, val rimInches: Int) {

    /** Overall diameter: the rim plus a sidewall above and below it. */
    val diameterMetres: Double get() = rimInches * METRES_PER_INCH + 2 * widthMm * aspectPercent / 100_000.0

    val valid: Boolean get() = widthMm in 100..400 && aspectPercent in 20..100 && rimInches in 10..24

    override fun toString() = "$widthMm/$aspectPercent R$rimInches"

    private companion object {
        const val METRES_PER_INCH = 0.0254
    }
}

/** Body colours to pick from; the Vehicle screen draws the car in the chosen one. */
enum class CarColour(val label: String, val argb: Long) {
    White("White", 0xFFF1F3F4),
    Silver("Silver", 0xFFB9BFC5),
    Grey("Grey", 0xFF6C737A),
    Black("Black", 0xFF22252A),
    Red("Red", 0xFFC62A2F),
    Blue("Blue", 0xFF2D5FB4),
    Brown("Brown", 0xFF7B5437),
    Orange("Orange", 0xFFE0702A),
    Green("Green", 0xFF3F7D52),
    Beige("Beige", 0xFFCDB894),
}

/** The car Revv is fitted to, as set in Settings › Vehicle. */
data class CarSetup(
    val make: String,
    val model: String,
    val colour: CarColour,
    /** Forward gears. */
    val gears: Int,
    val gearSource: GearSource,
    val tyre: TyreSize,
    /** Gearbox ratios, first gear first; there may be spares past [gears], kept in case it goes back up. */
    val ratios: List<Float>,
    val finalDrive: Float,
) {
    val name: String get() = "$make $model".trim()

    /** Engine rpm per km/h in each gear: gear ratio × final drive ÷ the tyre's circumference. */
    fun rpmPerKmh(): List<Float> {
        val circumference = PI * tyre.diameterMetres
        return ratios.take(gears).map { (it * finalDrive * METRES_PER_KM / MINUTES_PER_HOUR / circumference).toFloat() }
    }

    /** With [count] forward gears; ratios entered for gears it drops are kept, new ones guessed until entered. */
    fun withGears(count: Int): CarSetup {
        val gears = count.coerceIn(MIN_GEARS, MAX_GEARS)
        val last = ratios.lastOrNull() ?: 1f
        val guessed = List((gears - ratios.size).coerceAtLeast(0)) { i -> round3(last * TYPICAL_STEP.pow(i + 1)) }
        return copy(gears = gears, ratios = ratios + guessed)
    }

    companion object {
        const val MIN_GEARS = 4
        const val MAX_GEARS = 8
        private const val METRES_PER_KM = 1000.0
        private const val MINUTES_PER_HOUR = 60.0

        /** Each higher gear turns the engine about this much slower, for guessing an added gear. */
        private const val TYPICAL_STEP = 0.82f

        private fun round3(value: Float) = (value * 1000).roundToInt() / 1000f

        /**
         * The car Revv was built for: K12M 1.2 petrol on 165/80 R14 tyres, 5-speed gearbox with
         * ratios 3.545, 1.904, 1.280, 0.966, 0.757 and a 4.235 final drive.
         */
        val SWIFT_VXI_2015 = CarSetup(
            make = "Maruti Suzuki",
            model = "Swift VXi 2015",
            colour = CarColour.White,
            gears = 5,
            gearSource = GearSource.Ratios,
            tyre = TyreSize(165, 80, 14),
            ratios = listOf(3.545f, 1.904f, 1.280f, 0.966f, 0.757f),
            finalDrive = 4.235f,
        )
    }
}
