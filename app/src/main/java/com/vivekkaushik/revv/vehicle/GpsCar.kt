package com.vivekkaushik.revv.vehicle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A car known only from GPS: road speed from the fixes, and the gear, revs and load a calm driver
 * of a manual would have at that speed, from the gearing in [setup]. Fixes come about once a
 * second; between them the speed carries on toward where the last two say it's heading, so the
 * revs climb and fall smoothly rather than in steps. The load and the gear changes follow the
 * acceleration smoothed over the last few fixes, less the little that GPS speed wanders by on its
 * own, so a steady speed is a steady note and a burst of speed eases off instead of lifting off
 * the moment it ends. Gears go up sooner cruising than pulling hard, one at a time, and never
 * back and forth within [SHIFT_GAP_NANOS]. Times are on one monotonic clock, in nanoseconds.
 * Fixes may come from one thread while another reads [at].
 */
class GpsCar(setup: CarSetup, private val profile: VehicleProfile = VehicleProfile.SWIFT_VXI_2015) {

    /** What the car is doing at one moment. */
    data class State(
        val speedKmh: Double,
        /** Forward gear from 1, or 0 standing still. */
        val gear: Int,
        val rpm: Int,
        /** How hard the engine works: 0 idling or coasting, 1 flat out. */
        val load: Double,
    )

    /** The car as set up in Settings, whose gears it drives in; it can change mid-drive. */
    @Volatile
    var setup: CarSetup = setup

    private var lastFixAt = 0L
    private var lastFixKmh = 0.0

    /** Metres per second², smoothed over the last few fixes; glides there from [fromAccel] with the speed. */
    private var accel = 0.0
    private var fromAccel = 0.0

    /** The speed glides from [fromKmh] to [toKmh] over [rampSeconds] from [rampFrom]. */
    private var fromKmh = 0.0
    private var toKmh = 0.0
    private var rampFrom = 0L
    private var rampSeconds = USUAL_INTERVAL
    private var gear = 0
    private var lastShift = 0L

    /** A fix saying the car was doing [speedKmh] as it arrived at [nanos]. */
    @Synchronized
    fun fix(speedKmh: Double, nanos: Long) {
        // GPS wanders a little while parked: that's standing still.
        val measured = if (speedKmh < STANDSTILL_KMH) 0.0 else speedKmh
        val gap = if (lastFixAt == 0L) Double.MAX_VALUE else (nanos - lastFixAt) / 1e9
        val interval: Double
        val now: Double
        if (gap > MAX_INTERVAL) {
            // The first fix, or the first in a while: nothing to tell a trend from.
            interval = USUAL_INTERVAL
            now = if (lastFixAt == 0L) measured else speedAt(nanos)
            accel = 0.0
            fromAccel = 0.0
        } else {
            interval = max(gap, MIN_INTERVAL)
            now = speedAt(nanos)
            fromAccel = accelAt(nanos)
            val change = ((measured - lastFixKmh) / KMH_PER_MPS / interval).coerceIn(-MAX_ACCEL, MAX_ACCEL)
            accel += (change - accel) * ACCEL_BLEND
        }
        lastFixAt = nanos
        lastFixKmh = measured
        // Aim for where the car will be by the next fix, so the speed keeps up instead of trailing a fix behind.
        val ahead = measured + accel * KMH_PER_MPS * interval
        fromKmh = now
        toKmh = if (measured == 0.0) 0.0 else max(0.0, ahead)
        rampFrom = nanos
        rampSeconds = interval
    }

    /** The car at [nanos], or null before the first fix and once fixes have stopped for a while. */
    @Synchronized
    fun at(nanos: Long): State? {
        if (lastFixAt == 0L || nanos - lastFixAt > HOLD_NANOS) return null
        val speed = speedAt(nanos)
        val pull = pulling(accelAt(nanos))
        val ratios = setup.rpmPerKmh()
        val gear = shift(speed, pull, ratios, nanos)
        if (gear == 0) return State(speed, 0, IDLE_RPM.roundToInt(), 0.0)
        val effort = (pull / HARD_ACCEL).coerceIn(0.0, 1.0)
        // Below the speed first gear idles at, the clutch slips: more revs the harder it pulls away.
        val floor = if (gear == 1) IDLE_RPM + (LAUNCH_RPM - IDLE_RPM) * effort else IDLE_RPM
        val rpm = max(floor, speed * ratios[gear - 1])
        return State(speed, gear, rpm.roundToInt(), load(speed, pull, rpm))
    }

    private fun progress(nanos: Long) = ((nanos - rampFrom) / 1e9 / rampSeconds).coerceIn(0.0, 1.0)

    private fun speedAt(nanos: Long) = fromKmh + (toKmh - fromKmh) * progress(nanos)

    /**
     * The smoothed acceleration, glided like the speed. Past the glide, without a newer fix, it
     * fades over [TREND_FADE] seconds: the speed is being held, so the car is cruising at it.
     */
    private fun accelAt(nanos: Long): Double {
        val elapsed = (nanos - rampFrom) / 1e9
        if (elapsed < rampSeconds) return fromAccel + (accel - fromAccel) * (elapsed / rampSeconds)
        return accel * (1 - (elapsed - rampSeconds) / TREND_FADE).coerceIn(0.0, 1.0)
    }

    /**
     * [accel] less what the speed wanders by from one fix to the next on its own, which isn't the
     * driver's doing: it neither pulls nor lifts off within that.
     */
    private fun pulling(accel: Double) = if (abs(accel) <= JITTER_ACCEL) 0.0 else accel - sign(accel) * JITTER_ACCEL

    private fun shift(speedKmh: Double, accel: Double, ratios: List<Float>, nanos: Long): Int {
        if (ratios.isEmpty() || speedKmh < MOVING_KMH) {
            gear = 0
            return gear
        }
        val effort = (accel / HARD_ACCEL).coerceIn(0.0, 1.0)
        val upshiftAt = CALM_UPSHIFT_RPM + (HARD_UPSHIFT_RPM - CALM_UPSHIFT_RPM) * effort
        val downshiftAt = CALM_DOWNSHIFT_RPM + (HARD_DOWNSHIFT_RPM - CALM_DOWNSHIFT_RPM) * effort
        fun rpmIn(gear: Int) = speedKmh * ratios[gear - 1]
        if (gear == 0) {
            // Moving off, or found already moving: the highest gear that pulls without labouring.
            gear = (ratios.size downTo 1).firstOrNull { rpmIn(it) >= downshiftAt + SHIFT_MARGIN_RPM } ?: 1
            lastShift = nanos
            return gear
        }
        gear = gear.coerceIn(1, ratios.size)
        val rpm = rpmIn(gear)
        // Out of an engine's range, change at once; within it, give the last change time to settle.
        val due = nanos - lastShift >= SHIFT_GAP_NANOS || rpm > MAX_RPM || rpm < IDLE_RPM
        val up = gear < ratios.size && rpm >= upshiftAt && rpmIn(gear + 1) >= downshiftAt + SHIFT_MARGIN_RPM
        val down = gear > 1 && rpm < downshiftAt && rpmIn(gear - 1) <= upshiftAt - SHIFT_MARGIN_RPM
        if (due && (up || down)) {
            gear += if (up) 1 else -1
            lastShift = nanos
        }
        return gear
    }

    /**
     * The share of the engine's torque at [rpm] it takes to hold [accel] against rolling
     * resistance and drag; 0 when the car slows faster than those alone would slow it.
     */
    private fun load(speedKmh: Double, accel: Double, rpm: Double): Double {
        val mps = speedKmh / KMH_PER_MPS
        val force = MASS_KG * (accel + GRAVITY * ROLLING_RESISTANCE) + AIR_DENSITY * DRAG_AREA * mps * mps / 2
        if (force <= 0) return 0.0
        val torque = force * mps / DRIVELINE_EFFICIENCY / (rpm * PI / 30)
        return (torque / fullTorque(rpm)).coerceIn(0.0, 1.0)
    }

    /** What a naturally aspirated petrol engine gives flat out at [rpm], Nm: rising to its peak at 4000, easing off past it. */
    private fun fullTorque(rpm: Double): Double {
        val shape = if (rpm < PEAK_TORQUE_RPM) {
            LOW_END_TORQUE + (1 - LOW_END_TORQUE) * ((rpm - IDLE_RPM) / (PEAK_TORQUE_RPM - IDLE_RPM)).coerceIn(0.0, 1.0)
        } else {
            1 - TOP_END_FADE * min(1.0, (rpm - PEAK_TORQUE_RPM) / 2000)
        }
        return TORQUE_PER_LITRE * profile.displacementLitres * shape
    }

    companion object {
        const val IDLE_RPM = 900.0

        /** Parked GPS drifts at a km/h or two. */
        const val STANDSTILL_KMH = 2.5
        const val MOVING_KMH = 1.0

        /** How long to hold the last speed without fixes, e.g. through a tunnel. */
        const val HOLD_NANOS = 15_000_000_000L

        /** Changing gear: cruising, and pulling as hard as [HARD_ACCEL]. */
        const val CALM_UPSHIFT_RPM = 2300.0
        const val HARD_UPSHIFT_RPM = 4500.0
        const val CALM_DOWNSHIFT_RPM = 1300.0
        const val HARD_DOWNSHIFT_RPM = 2000.0
        const val SHIFT_MARGIN_RPM = 200.0
        const val SHIFT_GAP_NANOS = 1_000_000_000L
        const val MAX_RPM = 6000.0
        const val LAUNCH_RPM = 1500.0

        /** Metres per second²: about as hard as a small petrol car pulls. */
        const val HARD_ACCEL = 2.5

        /**
         * Metres per second² the speed wanders by from fix to fix with the car holding steady,
         * about 0.9 km/h a second: a receiver's Doppler speed is good to a few tenths of a km/h,
         * and the smoothing takes out most of what two fixes disagree by.
         */
        const val JITTER_ACCEL = 0.25

        /** Seconds the trend takes to fade once the glide has run out without a newer fix. */
        private const val TREND_FADE = 2.0

        private const val KMH_PER_MPS = 3.6
        private const val USUAL_INTERVAL = 1.0
        private const val MIN_INTERVAL = 0.2
        private const val MAX_INTERVAL = 3.0
        private const val MAX_ACCEL = 8.0
        private const val ACCEL_BLEND = 0.5

        /** A small hatchback with two aboard. */
        private const val MASS_KG = 1100.0
        private const val GRAVITY = 9.81
        private const val ROLLING_RESISTANCE = 0.012
        private const val AIR_DENSITY = 1.2

        /** Drag coefficient × frontal area, m². */
        private const val DRAG_AREA = 0.7
        private const val DRIVELINE_EFFICIENCY = 0.9
        private const val TORQUE_PER_LITRE = 95.0
        private const val PEAK_TORQUE_RPM = 4000.0
        private const val LOW_END_TORQUE = 0.75
        private const val TOP_END_FADE = 0.15
    }
}
