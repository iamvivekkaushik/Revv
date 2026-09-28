package com.vivekkaushik.revv.vehicle

import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.GEAR_NEUTRAL
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.GEAR_REVERSE
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.IDLE_RPM
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.MAX_RPM
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.PHASE_DARK
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.PHASE_FRAME
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.PHASE_READY
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.PHASE_SWEEP
import com.vivekkaushik.revv.vehicle.DriveSimulator.Companion.phaseAt
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSimulatorTest {

    /** Steps at 60 fps for [seconds] and returns the last frame. */
    private fun DriveSimulator.run(seconds: Float, demoDrive: Boolean = true, reversing: Boolean = false): Telemetry {
        var last = step(1 / 60f, demoDrive, reversing)
        repeat((seconds * 60).roundToInt() - 1) { last = step(1 / 60f, demoDrive, reversing) }
        return last
    }

    @Test
    fun ignitionRunsThroughItsPhases() {
        assertEquals(PHASE_DARK, phaseAt(0.1f))
        assertEquals(PHASE_FRAME, phaseAt(0.5f))
        assertEquals(PHASE_SWEEP, phaseAt(2f))
        assertEquals(PHASE_READY, phaseAt(3f))
    }

    @Test
    fun gaugesSweepToFullScaleDuringIgnition() {
        val peak = DriveSimulator().run(1.9f, demoDrive = false)
        assertEquals(PHASE_SWEEP, peak.phase)
        assertEquals(1f, peak.speedFraction, 0.02f)
        assertEquals(1f, peak.rpmFraction, 0.02f)
    }

    @Test
    fun withoutDemoDriveTheCarIdlesInNeutral() {
        val idle = DriveSimulator().run(10f, demoDrive = false)
        assertEquals(0, idle.speedKmh)
        assertEquals(GEAR_NEUTRAL, idle.gearIndex)
        assertEquals(IDLE_RPM / MAX_RPM, idle.rpmFraction, 0.0001f)
        assertNull(idle.kmPerLitre)
    }

    @Test
    fun demoDrivePicksUpSpeedAndShiftsUp() {
        val cruising = DriveSimulator().run(12f)
        assertTrue("speed was ${cruising.speedKmh}", cruising.speedKmh > 20)
        assertTrue("gear was ${cruising.gearIndex}", cruising.gearIndex in 1..5)
        assertNotNull(cruising.kmPerLitre)
    }

    @Test
    fun reversingBringsTheCarToAStopInReverse() {
        val simulator = DriveSimulator()
        simulator.run(12f)
        val reversing = simulator.run(15f, reversing = true)
        assertEquals(0, reversing.speedKmh)
        assertEquals(GEAR_REVERSE, reversing.gearIndex)
    }

    @Test
    fun skippingIgnitionStartsReady() {
        val simulator = DriveSimulator().apply { ignite(skipSequence = true) }
        assertEquals(PHASE_READY, simulator.step(0.016f, demoDrive = false, reversing = false).phase)
    }
}
