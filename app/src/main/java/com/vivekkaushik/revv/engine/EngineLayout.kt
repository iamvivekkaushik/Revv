package com.vivekkaushik.revv.engine

/** One cylinder as the exhaust hears it. */
class Cylinder(
    /** When it fires, in crank degrees through the 720° four-stroke cycle. */
    val firesAt: Double,
    /** The exhaust its pulses leave by: one per bank on most V engines. */
    val exhaust: Int,
    /** Its header primary in metres; with its exhaust's length, how far its pulses travel to the tailpipe. */
    val primaryMetres: Double,
    /** How loud its pulses are next to the others' (engine-sim's sound attenuation). */
    val gain: Double,
)

/**
 * The engines Settings › Sound offers, after engine-sim's example engines: the firing order and
 * intervals, which exhaust each cylinder feeds, and how long the pipes are. Uneven intervals and
 * unequal pipes are what give a cross-plane V8 its burble and a boxer its rumble.
 */
enum class EngineLayout(
    val label: String,
    val cylinders: List<Cylinder>,
    /** Each exhaust's length from the headers to the tailpipe, in metres. */
    val exhaustMetres: List<Double>,
    /** engine-sim's "jitter": how unsteady each pulse's timing is, 0 to 1. */
    val jitter: Double,
) {
    INLINE_3(
        "Inline 3",
        cylinders(
            order = intArrayOf(1, 2, 3),
            angles = evenly(3, 240.0),
            exhaust = { 0 },
            primary = doubleArrayOf(0.35, 0.30, 0.38),
            gain = doubleArrayOf(1.0, 0.95, 1.05),
        ),
        exhaustMetres = listOf(2.3),
        jitter = 0.3,
    ),

    /** A 4-2-1 header into one pipe, firing 1-3-4-2. */
    INLINE_4(
        "Inline 4",
        cylinders(
            order = intArrayOf(1, 3, 4, 2),
            angles = evenly(4, 180.0),
            exhaust = { 0 },
            primary = doubleArrayOf(0.42, 0.38, 0.40, 0.45),
            gain = doubleArrayOf(1.0, 0.9, 1.05, 0.95),
        ),
        exhaustMetres = listOf(2.5),
        jitter = 0.2,
    ),

    /** engine-sim's Subaru EJ25 with unequal-length headers: one bank's pipes are half a metre longer. */
    BOXER_4(
        "Boxer 4",
        cylinders(
            order = intArrayOf(1, 3, 2, 4),
            angles = evenly(4, 180.0),
            exhaust = { 0 },
            primary = doubleArrayOf(0.051, 0.50, 0.076, 0.55),
        ),
        exhaustMetres = listOf(0.5),
        jitter = 0.5,
    ),

    /** Audi's 1-2-4-5-3, every 144°, its cylinders split between two pipes. */
    INLINE_5(
        "Inline 5",
        cylinders(
            order = intArrayOf(1, 2, 4, 5, 3),
            angles = evenly(5, 144.0),
            exhaust = { if (it % 2 == 1) 0 else 1 },
            primary = doubleArrayOf(0.40, 0.34, 0.30, 0.34, 0.40),
            gain = doubleArrayOf(1.0, 0.8, 1.0, 0.8, 1.0),
        ),
        exhaustMetres = listOf(2.5, 2.6),
        jitter = 0.3,
    ),

    /** Toyota 2JZ: 1-5-3-6-2-4, front and rear three in their own pipes. */
    INLINE_6(
        "Inline 6",
        cylinders(
            order = intArrayOf(1, 5, 3, 6, 2, 4),
            angles = evenly(6, 120.0),
            exhaust = { if (it <= 3) 0 else 1 },
            primary = doubleArrayOf(0.064, 0.051, 0.038, 0.038, 0.051, 0.064),
            gain = doubleArrayOf(0.9, 0.95, 0.9, 0.97, 0.98, 1.0),
        ),
        exhaustMetres = listOf(2.54, 2.54),
        jitter = 0.23,
    ),

    /** A 60° V6 firing evenly every 120°, a pipe per bank. */
    V6(
        "V6",
        cylinders(
            order = intArrayOf(1, 2, 3, 4, 5, 6),
            angles = evenly(6, 120.0),
            exhaust = { if (it % 2 == 1) 0 else 1 },
            primary = doubleArrayOf(0.254, 0.254, 0.127, 0.127, 0.0, 0.0),
            gain = doubleArrayOf(0.9, 0.95, 0.95, 0.9, 1.0, 1.0),
        ),
        exhaustMetres = listOf(2.54, 2.54),
        jitter = 0.4,
    ),

    /** A 90° V6 on a three-throw crank: firing 150° and 90° apart, with one pipe much longer. */
    V6_ODD_FIRE(
        "V6 odd-fire",
        cylinders(
            order = intArrayOf(1, 6, 5, 4, 3, 2),
            angles = doubleArrayOf(0.0, 150.0, 240.0, 390.0, 480.0, 630.0),
            exhaust = { if (it % 2 == 1) 0 else 1 },
            primary = doubleArrayOf(0.254, 0.254, 0.127, 0.127, 0.0, 0.0),
        ),
        exhaustMetres = listOf(2.54, 4.37),
        jitter = 0.4,
    ),

    /**
     * GM's LS: 1-8-7-2-6-5-4-3 on a cross-plane crank, so each bank fires unevenly, and the right
     * bank's pipe is 1.8 m longer. The burble.
     */
    V8_CROSS_PLANE(
        "V8 cross-plane",
        cylinders(
            order = intArrayOf(1, 8, 7, 2, 6, 5, 4, 3),
            angles = evenly(8, 90.0),
            exhaust = { if (it % 2 == 1) 0 else 1 },
            primary = doubleArrayOf(0.172, 0.162, 0.112, 0.152, 0.081, 0.121, 0.05, 0.0),
        ),
        exhaustMetres = listOf(2.54, 4.37),
        jitter = 0.6,
    ),

    /** Ferrari's F136: 1-5-3-7-4-8-2-6 on a flat-plane crank, each bank firing evenly. */
    V8_FLAT_PLANE(
        "V8 flat-plane",
        cylinders(
            order = intArrayOf(1, 5, 3, 7, 4, 8, 2, 6),
            angles = evenly(8, 90.0),
            exhaust = { if (it <= 4) 0 else 1 },
            primary = doubleArrayOf(0.02, 0.01, 0.03, 0.05, 0.01, 0.05, 0.07, 0.0),
            gain = doubleArrayOf(0.9, 0.8, 1.1, 1.0, 0.9, 0.72, 0.81, 0.63),
        ),
        exhaustMetres = listOf(2.54, 2.54),
        jitter = 0.15,
    ),

    /** Lexus LFA: every 72°, odd and even cylinders in their own pipes. */
    V10(
        "V10",
        cylinders(
            order = intArrayOf(1, 2, 3, 4, 7, 8, 9, 10, 5, 6),
            angles = evenly(10, 72.0),
            exhaust = { if (it % 2 == 1) 0 else 1 },
            primary = doubleArrayOf(0.05, 0.05, 0.04, 0.04, 0.03, 0.03, 0.02, 0.02, 0.01, 0.01),
        ),
        exhaustMetres = listOf(2.553, 2.54),
        jitter = 0.1,
    ),

    /** A 60° V12 firing evenly every 60°, a pipe per bank. */
    V12(
        "V12",
        cylinders(
            order = intArrayOf(1, 7, 5, 11, 3, 9, 6, 12, 2, 8, 4, 10),
            angles = evenly(12, 60.0),
            exhaust = { if (it <= 6) 0 else 1 },
            primary = doubleArrayOf(0.06, 0.03, 0.02, 0.05, 0.08, 0.03, 0.04, 0.02, 0.07, 0.05, 0.03, 0.06),
            gain = doubleArrayOf(1.0, 0.95, 1.05, 0.9, 1.0, 1.05, 0.95, 1.0, 0.9, 1.05, 1.0, 0.95),
        ),
        exhaustMetres = listOf(2.54, 2.6),
        jitter = 0.1,
    ),
    ;

    companion object {
        /** Revv was built for the Swift's four. */
        val DEFAULT = INLINE_4
    }
}

/**
 * The exhausts Settings › Sound offers, each one of engine-sim's recorded impulse responses, from
 * muffled to raspy, with engine-sim's noise and high-frequency settings to suit it.
 */
enum class ExhaustNote(
    val label: String,
    /** The impulse response in assets. */
    val impulse: String,
    /** engine-sim's "noise": how much the pulses are roughened by turbulence, 0 to 1. */
    val noise: Double,
    /** engine-sim's "high frequency gain": how much of the pulses' edges to keep. */
    val highFrequencyGain: Double,
    /** Evens out how loud the recordings play: the muffled one passes far less of the pulses. */
    val loudness: Double,
) {
    STOCK("Stock", "engine/mild_exhaust.wav", noise = 0.6, highFrequencyGain = 0.006, loudness = 2.7),
    SPORT("Sport", "engine/smooth_39.wav", noise = 1.0, highFrequencyGain = 0.01, loudness = 1.0),
    OPEN("Open", "engine/minimal_muffling_02.wav", noise = 1.0, highFrequencyGain = 0.01, loudness = 1.2),
    STRAIGHT_PIPE("Straight pipe", "engine/minimal_muffling_01.wav", noise = 1.0, highFrequencyGain = 0.012, loudness = 1.5),
    ;

    companion object {
        val DEFAULT = SPORT
    }
}

/** [angles] are when each cylinder in the firing [order] fires; [primary] and [gain] go by cylinder number. */
private fun cylinders(
    order: IntArray,
    angles: DoubleArray,
    exhaust: (Int) -> Int,
    primary: DoubleArray,
    gain: DoubleArray = DoubleArray(primary.size) { 1.0 },
): List<Cylinder> = List(primary.size) { index ->
    val number = index + 1
    Cylinder(angles[order.indexOf(number)], exhaust(number), primary[index], gain[index])
}

private fun evenly(count: Int, interval: Double) = DoubleArray(count) { it * interval }
