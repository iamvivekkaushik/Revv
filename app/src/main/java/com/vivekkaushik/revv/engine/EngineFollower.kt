package com.vivekkaushik.revv.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One look at the engine, from the car or the demo drive. */
class EngineReading(
    val rpm: Int,
    val speedKmh: Int?,
    /** Forward gear from 1, 0 for neutral, or null when not known. */
    val gear: Int?,
    /** How full the cylinders are: about 0.2 with the throttle shut, 1 wide open, more with boost. */
    val airFill: Float?,
    /** OBD-II's calculated engine load, percent. */
    val engineLoad: Int?,
    /** How far the accelerator is down, percent: the pedal where the car reports it, else the throttle. */
    val throttle: Int?,
    /** When it was read, on the SystemClock.elapsedRealtimeNanos clock. */
    val atNanos: Long,
)

/**
 * Turns readings that arrive a few times a second, each already a little old, into an engine
 * that moves smoothly from one audio block to the next: it carries the rpm on along its last
 * trend for part of a poll, eases toward that, and works out the load from the air flow, moved
 * on by what the pedal has done since the air flow was read, and from how fast the revs are
 * changing. A gear change lifts off briefly, as a driver does. A reading with the same time as
 * the last one brings the slower values up to date. [report] may be called from any thread;
 * [advance] only from the audio thread.
 */
class EngineFollower {

    @Volatile
    private var reported: EngineReading? = null

    /**
     * How the revs get from one reading to the next: 0 goes straight there; more glides, so a
     * jump between readings is heard as the engine pulling up or dropping back, and at 1 no
     * faster than an engine would. Set from the audio thread.
     */
    var smoothing = DEFAULT_SMOOTHING
    private var current: EngineReading? = null

    /** Rpm per second between the last two readings. */
    private var slope = 0.0
    private var interval = DEFAULT_INTERVAL
    private var closedThrottle = Int.MAX_VALUE
    private var openThrottle = 0
    private var airLoad = Double.NaN

    /** Where the pedal was when the air flow last changed. */
    private var pedalAtAir: Double? = null
    private var shiftUntil = 0L
    private var rpm = 0.0
    private var load = 0.0
    private var lastAt = 0L

    fun report(reading: EngineReading) {
        reported = reading
    }

    /** The engine at [nowNanos], on the same clock as the readings. */
    fun advance(nowNanos: Long): EngineState {
        val seconds = if (lastAt == 0L) 0.0 else ((nowNanos - lastAt) / 1e9).coerceIn(0.0, MAX_STEP)
        lastAt = nowNanos
        reported?.let { if (it !== current) take(it) }

        val reading = current?.takeIf { nowNanos - it.atNanos < STALE_NANOS && it.rpm >= RUNNING_RPM }
        if (reading == null) {
            rpm *= exp(-seconds / SPIN_DOWN)
            load = 0.0
            return EngineState(rpm, 0.0, overrun = false, running = false)
        }

        val since = (nowNanos - reading.atNanos) / 1e9
        // Readings are already old when they arrive, so look a little ahead, but never further
        // than about one poll: past that the trend is a guess.
        // A longer glide trails the readings by that much more: aim that much further along the trend.
        val glide = RPM_SMOOTHING + (LONGEST_GLIDE - RPM_SMOOTHING) * smoothing
        val further = glide - RPM_SMOOTHING
        val ahead = (since + LEAD_SECONDS + further).coerceIn(0.0, min(interval, MAX_AHEAD_SECONDS) + further)
        val targetRpm = max(RUNNING_RPM.toDouble(), reading.rpm + slope * ahead * EXTRAPOLATION)
        var step = (targetRpm - rpm) * (1 - exp(-seconds / glide))
        if (smoothing > 0) {
            val rise = SLOWEST_RISE / smoothing
            step = step.coerceIn(-rise * FALL_TO_RISE * seconds, rise * seconds)
        }
        rpm += step

        val rising = (slope / SLOPE_FOR_FULL_LOAD).coerceIn(-1.0, 1.0)
        var targetLoad = baseLoad(reading)
        // The air flow is read after the pedal and lags it anyway: move it on by what the pedal has
        // done since, so pressing down is heard at once.
        val pedal = pedalLoad(reading.throttle)
        val pedalThen = pedalAtAir
        if (pedal != null && pedalThen != null) targetLoad = (targetLoad + pedal - pedalThen).coerceIn(0.0, MAX_LOAD)
        // Revs climbing means the driver is on the throttle, before the slower readings say so.
        if (rising > 0) targetLoad = max(targetLoad, min(1.0, FREE_REV_LOAD + rising))
        val throttleShut = reading.throttle != null && closedThrottle < THROTTLE_SHUT_MAX && reading.throttle <= closedThrottle + THROTTLE_SLACK
        val overrun = rpm > OVERRUN_MIN_RPM && rising < 0.1 && (throttleShut || targetLoad < 0.05 || rising < -0.3)
        if (overrun) targetLoad = min(targetLoad, OVERRUN_LOAD)
        if (nowNanos < shiftUntil) targetLoad = 0.0
        val smoothing = if (targetLoad > load) LOAD_RISE else LOAD_FALL
        load += (targetLoad - load) * (1 - exp(-seconds / smoothing))
        return EngineState(rpm, load, overrun, running = true)
    }

    private fun take(reading: EngineReading) {
        val previous = current
        current = reading
        reading.throttle?.let {
            closedThrottle = min(closedThrottle, it)
            openThrottle = max(openThrottle, it)
        }
        val air = baseLoad(reading)
        if (air != airLoad) {
            airLoad = air
            pedalAtAir = pedalLoad(reading.throttle)
        }
        // The same moment again, with the slower readings caught up: the trend stands.
        if (previous != null && reading.atNanos == previous.atNanos) return
        if (previous == null) {
            slope = 0.0
            if (rpm < RUNNING_RPM) rpm = reading.rpm.toDouble()
            return
        }
        val seconds = (reading.atNanos - previous.atNanos) / 1e9
        if (seconds in MIN_INTERVAL..MAX_INTERVAL) {
            slope = ((reading.rpm - previous.rpm) / seconds).coerceIn(-MAX_SLOPE, MAX_SLOPE)
            interval = seconds
        } else {
            slope = 0.0
            interval = DEFAULT_INTERVAL
        }
        val shifted = (previous.gear ?: 0) >= 1 && (reading.gear ?: 0) >= 1 && previous.gear != reading.gear
        if (shifted && (reading.speedKmh ?: 0) > SHIFT_MIN_KMH) shiftUntil = reading.atNanos + SHIFT_NANOS
    }

    /** How far the pedal is down, 0 to 1, between the lowest and highest seen; null until it's been seen shut. */
    private fun pedalLoad(throttle: Int?): Double? {
        if (throttle == null || closedThrottle >= THROTTLE_SHUT_MAX) return null
        val travel = max(openThrottle - closedThrottle, MIN_PEDAL_TRAVEL)
        return ((throttle - closedThrottle - THROTTLE_SLACK).toDouble() / travel).coerceIn(0.0, 1.0)
    }

    /** Load from the air the engine takes in, or OBD's own load figure, as 0 (shut) to 1 (wide open). */
    private fun baseLoad(reading: EngineReading): Double = when {
        reading.airFill != null -> ((reading.airFill - SHUT_FILL) / (1 - SHUT_FILL)).coerceIn(0.0, MAX_LOAD)
        reading.engineLoad != null -> ((reading.engineLoad / 100.0 - SHUT_LOAD) / (1 - SHUT_LOAD)).coerceIn(0.0, 1.0)
        else -> UNKNOWN_LOAD
    }

    private companion object {
        const val RUNNING_RPM = 250
        const val STALE_NANOS = 3_000_000_000L
        const val MAX_STEP = 0.1
        const val SPIN_DOWN = 0.25
        const val RPM_SMOOTHING = 0.08
        const val DEFAULT_SMOOTHING = 0.5

        /** The glide with smoothing all the way up, and how fast the revs may then climb and fall. */
        const val LONGEST_GLIDE = 0.25
        const val SLOWEST_RISE = 4000.0
        const val FALL_TO_RISE = 0.75

        /** Onto the throttle quickly, off it a little more gently. */
        const val LOAD_RISE = 0.03
        const val LOAD_FALL = 0.08
        const val MAX_LOAD = 1.4
        const val LEAD_SECONDS = 0.1
        const val MAX_AHEAD_SECONDS = 0.3
        const val EXTRAPOLATION = 0.6
        const val DEFAULT_INTERVAL = 0.25
        const val MIN_INTERVAL = 0.03
        const val MAX_INTERVAL = 1.5

        /** No petrol engine revs up or down faster than this. */
        const val MAX_SLOPE = 8000.0
        const val SLOPE_FOR_FULL_LOAD = 3000.0
        const val FREE_REV_LOAD = 0.3
        const val OVERRUN_MIN_RPM = 1500.0
        const val OVERRUN_LOAD = 0.03

        /** Cylinder fill and OBD load with the throttle shut at speed. */
        const val SHUT_FILL = 0.25
        const val SHUT_LOAD = 0.2
        const val UNKNOWN_LOAD = 0.3

        /** A closed throttle reads well under this; anything higher was never seen closed. */
        const val THROTTLE_SHUT_MAX = 40
        const val THROTTLE_SLACK = 2

        /** Pedal travel assumed until more has been seen: few go less than this from shut to floored. */
        const val MIN_PEDAL_TRAVEL = 50

        const val SHIFT_MIN_KMH = 5
        const val SHIFT_NANOS = 250_000_000L
    }
}
