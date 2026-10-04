package com.vivekkaushik.revv.engine

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFollowerTest {

    private val follower = EngineFollower()

    @Test
    fun settlesOnSteadyReadings() {
        val state = drive(seconds = 2.0) { reading(rpm = 2000, airFill = 0.5f, at = it) }
        assertTrue(state.running)
        assertEquals(2000.0, state.rpm, 5.0)
        assertEquals((0.5 - 0.25) / 0.75, state.load, 0.02)
        assertFalse(state.overrun)
    }

    @Test
    fun followsAClimbWithoutRunningAway() {
        // Revs climbing 2,000 rpm a second, read five times a second, then holding.
        fun actual(t: Double) = if (t < 2.0) 1000 + 2000 * t else 5000.0
        var worst = 0.0
        var highest = 0.0
        val each = { t: Double, state: EngineState ->
            if (t in 0.5..2.0) worst = maxOf(worst, abs(state.rpm - actual(t)))
            highest = maxOf(highest, state.rpm)
        }
        drive(seconds = 4.0, each = each) { reading(rpm = actual(it).toInt(), at = it) }
        assertTrue("lags by up to $worst rpm", worst < 500)
        assertTrue("overshoots to $highest", highest < 5400)
    }

    @Test
    fun stopsWhenTheReadingsDo() {
        drive(seconds = 1.0) { reading(rpm = 900, at = it) }
        // No readings for the next 4 s.
        var state = EngineState.Off
        for (step in 0 until 4 * STEPS_PER_SECOND) state = follower.advance(nanos(1.0 + step.toDouble() / STEPS_PER_SECOND))
        assertFalse(state.running)
        assertTrue(state.rpm < 50)
    }

    @Test
    fun coastingWithTheThrottleShutIsOverrun() {
        drive(seconds = 1.0) { reading(rpm = 3000, throttle = 14, airFill = 0.6f, at = it) }
        val state = drive(seconds = 1.0, start = 1.0) { reading(rpm = (3000 - 600 * (it - 1.0)).toInt(), throttle = 14, airFill = 0.18f, at = it) }
        assertTrue(state.overrun)
        assertTrue("load ${state.load}", state.load < 0.05)
    }

    @Test
    fun idlingIsNotOverrun() {
        val state = drive(seconds = 2.0) { reading(rpm = 850, throttle = 14, airFill = 0.3f, at = it) }
        assertFalse(state.overrun)
        assertTrue(state.load > 0)
    }

    @Test
    fun aGearChangeLiftsOffBriefly() {
        drive(seconds = 1.0) { reading(rpm = 3000, gear = 2, speed = 40, airFill = 0.8f, at = it) }
        var lowest = 1.0
        val each = { t: Double, state: EngineState -> if (t < 1.3) lowest = minOf(lowest, state.load) }
        val after = drive(seconds = 1.0, start = 1.0, each = each) { reading(rpm = 2200, gear = 3, speed = 41, airFill = 0.8f, at = it) }
        assertTrue("lifted to $lowest", lowest < 0.3)
        assertTrue("back on to ${after.load}", after.load > 0.6)
    }

    /**
     * Advances the follower for [seconds] from [start], reporting a new reading five times a
     * second, and returns the last state.
     */
    private fun drive(
        seconds: Double,
        start: Double = 0.0,
        each: (Double, EngineState) -> Unit = { _, _ -> },
        reading: (Double) -> EngineReading,
    ): EngineState {
        var state = EngineState.Off
        val steps = (seconds * STEPS_PER_SECOND).toInt()
        for (step in 0 until steps) {
            val t = start + step.toDouble() / STEPS_PER_SECOND
            if (step % (STEPS_PER_SECOND / 5) == 0) follower.report(reading(t))
            state = follower.advance(nanos(t))
            each(t, state)
        }
        return state
    }

    private fun reading(rpm: Int, at: Double, gear: Int? = null, speed: Int? = null, airFill: Float? = null, throttle: Int? = null) =
        EngineReading(rpm, speed, gear, airFill, engineLoad = null, throttle = throttle, atNanos = nanos(at))

    private fun nanos(seconds: Double) = 1_000_000_000L + (seconds * 1e9).toLong()

    private companion object {
        /** Audio blocks a second, near enough. */
        const val STEPS_PER_SECOND = 170
    }
}
