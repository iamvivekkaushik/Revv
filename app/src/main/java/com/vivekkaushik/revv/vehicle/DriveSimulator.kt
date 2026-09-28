package com.vivekkaushik.revv.vehicle

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The design's demo drive, ported step for step: an ignition sequence (dark, frame, gauge sweep,
 * ready) followed by a 50 s drive loop that includes an 8 s stop. Stands in for real telemetry
 * until Revv can read an OBD-II adapter.
 */
class DriveSimulator(private val sixSpeed: Boolean = false) {

    private var bootSeconds = 0f
    private var time = 0f
    private var speedKmh = 0f
    private var sampleTimer = 0f
    private var engineLoad = 0
    private var throttle = 0
    private var engineRpm = 0

    /** Restarts the ignition sequence, or jumps straight to the ready state. */
    fun ignite(skipSequence: Boolean = false) {
        bootSeconds = if (skipSequence) READY_AT else 0f
        speedKmh = 0f
    }

    fun step(elapsedSeconds: Float, demoDrive: Boolean, reversing: Boolean): Telemetry {
        val dt = min(MAX_STEP, elapsedSeconds)
        time += dt
        bootSeconds += dt
        val phase = phaseAt(bootSeconds)

        val cycle = time % CYCLE_SECONDS
        val stopped = (cycle > 30f && cycle < 38f) || reversing
        val target = if (phase < PHASE_READY || stopped || !demoDrive) {
            0f
        } else {
            (62f + 46f * sin(time * 0.18f) + 8f * sin(time * 1.3f)).coerceIn(0f, MAX_SPEED)
        }
        val response = if (target < speedKmh) 0.9f else 1.5f
        speedKmh += (target - speedKmh) * min(1f, dt * response)
        if (speedKmh < 0.6f && target == 0f) speedKmh = 0f

        val bands = if (sixSpeed) SIX_SPEED_BANDS else FIVE_SPEED_BANDS
        var gear = 1
        for (i in 1 until bands.size) if (speedKmh >= bands[i]) gear = i + 1
        val bandLow = bands[gear - 1]
        val bandHigh = if (gear < bands.size) bands[gear] else 180f
        val rpm = when {
            speedKmh >= 1f -> 1500f + (speedKmh - bandLow) / (bandHigh - bandLow) * 2600f
            phase < PHASE_SWEEP -> 0f
            else -> IDLE_RPM
        }

        var speedFraction = speedKmh / MAX_SPEED
        var rpmFraction = rpm / MAX_RPM
        if (phase == PHASE_SWEEP) {
            val sweep = sin(PI.toFloat() * (bootSeconds - SWEEP_AT) / (READY_AT - SWEEP_AT))
            speedFraction = sweep
            rpmFraction = sweep
        }
        val idle = phase == PHASE_READY && speedKmh < 1f
        val gearIndex = when {
            reversing && phase == PHASE_READY -> GEAR_REVERSE
            idle || phase < PHASE_READY -> GEAR_NEUTRAL
            else -> gear
        }

        sampleTimer += dt
        if (sampleTimer > SAMPLE_SECONDS) {
            sampleTimer = 0f
            val throttleFraction = ((target - speedKmh) / 30f + 0.25f).coerceIn(0f, 1f)
            engineLoad = (throttleFraction * 60 + rpmFraction * 30).roundToInt()
            throttle = (throttleFraction * 100).roundToInt()
            engineRpm = (rpm / 10).roundToInt() * 10
        }

        return Telemetry(
            phase = phase,
            bootSeconds = bootSeconds,
            speedFraction = speedFraction,
            rpmFraction = rpmFraction,
            gearIndex = gearIndex,
            kmPerLitre = if (speedKmh < 1f) null else 28f - speedKmh * 0.12f,
            steer = sin(time * 0.5f) * 60f,
            engineLoad = engineLoad,
            throttle = throttle,
            engineRpm = engineRpm,
        )
    }

    companion object {
        const val PHASE_DARK = 0
        const val PHASE_FRAME = 1
        const val PHASE_SWEEP = 2
        const val PHASE_READY = 3

        /** Indexes into the gear strip R 1 2 3 4 5 6 N. */
        const val GEAR_REVERSE = 0
        const val GEAR_NEUTRAL = 7

        const val MAX_SPEED = 200f
        const val MAX_RPM = 8000f
        const val IDLE_RPM = 850f

        private const val FRAME_AT = 0.2f
        private const val SWEEP_AT = 1.2f
        private const val READY_AT = 2.6f
        private const val CYCLE_SECONDS = 50f
        private const val MAX_STEP = 0.05f
        private const val SAMPLE_SECONDS = 0.15f
        private val FIVE_SPEED_BANDS = floatArrayOf(0f, 20f, 40f, 62f, 88f)
        private val SIX_SPEED_BANDS = floatArrayOf(0f, 18f, 35f, 54f, 76f, 102f)

        fun phaseAt(bootSeconds: Float): Int = when {
            bootSeconds < FRAME_AT -> PHASE_DARK
            bootSeconds < SWEEP_AT -> PHASE_FRAME
            bootSeconds < READY_AT -> PHASE_SWEEP
            else -> PHASE_READY
        }
    }
}

/** One frame of cluster data. Fractions are of 200 km/h and 8,000 rpm. */
data class Telemetry(
    val phase: Int,
    val bootSeconds: Float,
    val speedFraction: Float,
    val rpmFraction: Float,
    val gearIndex: Int,
    /** Null while stationary. */
    val kmPerLitre: Float?,
    val steer: Float,
    val engineLoad: Int,
    val throttle: Int,
    val engineRpm: Int,
) {
    val speedKmh: Int get() = (speedFraction * DriveSimulator.MAX_SPEED).roundToInt()
}
