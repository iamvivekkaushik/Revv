package com.vivekkaushik.revv.vehicle

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Works out which gear the car is in from engine and road speed, since OBD-II doesn't say: in gear,
 * the engine turns a fixed number of rpm per km/h. Knows the gears from [setup]'s tyre size and
 * ratios, or learns them as the car is driven; [learnt] restores what earlier drives taught it.
 * Feed it every reading, in order.
 */
class GearEstimator(setup: CarSetup, learnt: List<Float> = emptyList()) {

    /** The car as set up in Settings; it can change mid-drive. */
    @Volatile
    var setup: CarSetup = setup

    @Volatile
    private var learner = GearLearner(learnt)
    private var steadyRatio = 0f
    private var steadySince = 0L
    private var lastAt = 0L
    private var movingOff = true
    private var gear: Int? = 0

    /** Everything learnt about this car's gears, first gear first, to save for the next drive. */
    fun learnt(): List<Float> = learner.gears()

    /** Forgets the learnt gears, for a car whose gears were learnt wrong. */
    fun forget() {
        learner = GearLearner()
    }

    /** The car's forward gears in rpm per km/h, first gear first, from wherever the setup says. */
    fun ratios(): List<Float> = when (setup.gearSource) {
        GearSource.Ratios -> setup.rpmPerKmh()
        GearSource.Learnt -> learner.gears().take(setup.gears)
    }

    /**
     * The gear at [nowMillis]: 1 upwards, or 0 for neutral, which is shown only standing still.
     * Moving, it keeps the last gear it recognised through gear changes and the clutch, and is
     * null only when no gears are known yet.
     */
    fun update(speedKmh: Int, rpm: Int, nowMillis: Long): Int? {
        val seconds = if (lastAt == 0L) 0.0 else (nowMillis - lastAt).coerceIn(0L, MAX_GAP_MILLIS) / 1000.0
        lastAt = nowMillis
        if (speedKmh <= 0) {
            movingOff = true
            steadySince = 0L
            gear = 0
            return gear
        }
        val ratios = ratios()
        if (rpm >= RUNNING_RPM) {
            val ratio = rpm.toFloat() / speedKmh
            learn(ratio, speedKmh, rpm, seconds, nowMillis)
            if (speedKmh >= MATCH_KMH) gearOf(ratios, ratio, speedKmh)?.let { gear = it }
        }
        // Just moved off: first, until the car shows another gear.
        if (gear == 0 || gear == null) gear = if (ratios.isNotEmpty()) 1 else null
        return gear
    }

    private fun learn(ratio: Float, speedKmh: Int, rpm: Int, seconds: Double, nowMillis: Long) {
        if (movingOff && speedKmh > LAUNCH_MAX_KMH) movingOff = false
        if (steadySince == 0L || abs(ln(ratio / steadyRatio)) > steadiness(speedKmh)) {
            steadyRatio = ratio
            steadySince = nowMillis
            return
        }
        val held = nowMillis - steadySince
        when {
            // Moving off, the first steady moment is the clutch fully up in first.
            movingOff && speedKmh >= LAUNCH_MIN_KMH && held >= LAUNCH_MILLIS -> {
                learner.add(ratio, seconds, launch = true)
                movingOff = false
            }
            !movingOff && held >= STEADY_MILLIS && speedKmh >= LEARN_KMH && rpm >= LEARN_RPM -> learner.add(ratio, seconds)
        }
    }

    /** The gear [ratio] belongs to among [gears], if it's close enough to one to be in it. */
    private fun gearOf(gears: List<Float>, ratio: Float, speedKmh: Int): Int? {
        if (gears.isEmpty()) return null
        // Within half the gap to the next gear, allowing for whole-km/h speed readings.
        val closest = gears.zipWithNext { a, b -> abs(ln(a / b)) }.minOrNull() ?: Float.MAX_VALUE
        val tolerance = max(min(MAX_TOLERANCE, closest / 2), steadiness(speedKmh))
        val (index, error) = gears.mapIndexed { index, expected -> index to abs(ln(ratio / expected)) }.minBy { it.second }
        return if (error <= tolerance) index + 1 else null
    }

    /** How far the ratio may wander and still count as steady: speed comes in whole km/h, coarse when slow. */
    private fun steadiness(speedKmh: Int): Float = max(STEADY, 0.6f / speedKmh)

    private companion object {
        const val RUNNING_RPM = 500
        const val MATCH_KMH = 5

        /** Ratio changes within 3% are the car holding a gear. */
        const val STEADY = 0.03f
        const val STEADY_MILLIS = 800L
        const val LEARN_KMH = 8
        const val LEARN_RPM = 900

        /** Moving off from a standstill: the speeds at which the car is still in first. */
        const val LAUNCH_MIN_KMH = 7
        const val LAUNCH_MAX_KMH = 25
        const val LAUNCH_MILLIS = 400L

        /** Just under half the gap between the Swift's 4th and 5th, the closest pair, in log terms. */
        const val MAX_TOLERANCE = 0.11f
        const val MAX_GAP_MILLIS = 2_000L
    }
}
