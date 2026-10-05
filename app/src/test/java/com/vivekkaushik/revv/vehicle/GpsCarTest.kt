package com.vivekkaushik.revv.vehicle

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsCarTest {

    private val swift = CarSetup.SWIFT_VXI_2015
    private val gearing = swift.rpmPerKmh()

    /** A clock that starts well past zero, as the device's does. */
    private var now = 5_000_000_000L

    /**
     * Feeds a fix a second for [seconds], at the speed [speedAt] gives for each second, reading
     * the car ten times a second in between as the adapter would. Returns every reading.
     */
    private fun GpsCar.drive(seconds: Int, speedAt: (Int) -> Double): List<GpsCar.State> {
        val states = mutableListOf<GpsCar.State>()
        for (second in 0 until seconds) {
            fix(speedAt(second), now)
            repeat(10) {
                states += at(now) ?: error("no reading at $second s")
                now += 100_000_000L
            }
        }
        return states
    }

    @Test
    fun saysNothingBeforeTheFirstFix() {
        assertNull(GpsCar(swift).at(now))
    }

    @Test
    fun parkedIsNeutralAtIdleEvenWithGpsDrift() {
        val car = GpsCar(swift)
        val last = car.drive(5) { if (it % 2 == 0) 1.8 else 0.4 }.last()
        assertEquals(0.0, last.speedKmh, 0.0)
        assertEquals(0, last.gear)
        assertEquals(GpsCar.IDLE_RPM.toInt(), last.rpm)
        assertEquals(0.0, last.load, 0.0)
    }

    @Test
    fun cruisesInTopGearWithRevsFromTheGearing() {
        val last = GpsCar(swift).drive(10) { 60.0 }.last()
        assertEquals(5, last.gear)
        assertEquals(60 * gearing[4], last.rpm.toFloat(), 2f)
        assertTrue("cruising takes some load: ${last.load}", last.load in 0.05..0.6)
    }

    @Test
    fun foundAlreadyMovingStartsInAGearThatFits() {
        val first = GpsCar(swift).drive(1) { 80.0 }.first()
        assertEquals(5, first.gear)
    }

    @Test
    fun pullsAwayThroughEveryGearInTurn() {
        // 0 to 100 km/h at 2 m/s², then holding it.
        val states = GpsCar(swift).drive(20) { minOf(100.0, it * 7.2) }
        val gears = states.map { it.gear }.filter { it > 0 }
        assertEquals((1..5).toList(), gears.distinct())
        assertEquals("never changes down while pulling away", gears.sorted(), gears)
        val moving = states.filter { it.speedKmh > 10 }
        assertTrue(moving.all { it.rpm in 1200..GpsCar.MAX_RPM.toInt() })
        assertTrue("working hard: ${moving.map { it.load }}", moving.count { it.load > 0.4 } > moving.size / 2)
    }

    @Test
    fun changesUpLaterPullingHardThanEasingAlong() {
        fun upshiftRpm(kmhPerSecond: Double): Int {
            val states = GpsCar(swift).drive(12) { it * kmhPerSecond }
            val shift = states.zipWithNext().first { (a, b) -> a.gear == 1 && b.gear == 2 }
            return shift.first.rpm
        }
        val gentle = upshiftRpm(3.0)
        val hard = upshiftRpm(9.0)
        assertTrue("gentle $gentle, hard $hard", hard > gentle + 500)
    }

    @Test
    fun speedGlidesBetweenFixesInsteadOfStepping() {
        val states = GpsCar(swift).drive(6) { 40.0 + it * 3.6 }
        val speeds = states.drop(10).map { it.speedKmh }
        assertTrue("rises every reading", speeds.zipWithNext().all { (a, b) -> b > a })
        assertTrue("no jump bigger than the trend", speeds.zipWithNext().all { (a, b) -> b - a < 1.0 })
        // Keeps up with the car rather than trailing a fix behind it: the last reading is 5.9 s in.
        assertEquals(40.0 + 5.9 * 3.6, states.last().speedKmh, 1.0)
    }

    @Test
    fun brakingCoastsOnAShutThrottleDownToNeutral() {
        // 60 km/h to a stop at about 3 m/s².
        val states = GpsCar(swift).drive(12) { if (it < 3) 60.0 else maxOf(0.0, 60 - (it - 3) * 11.0) }
        val braking = states.drop(40).filter { it.speedKmh > 10 }
        assertTrue(braking.isNotEmpty())
        assertTrue("no load while braking: ${braking.map { it.load }}", braking.all { it.load == 0.0 })
        val gears = states.drop(30).map { it.gear }
        assertEquals("only changes down", gears.sortedDescending(), gears)
        assertEquals(0, states.last().gear)
    }

    @Test
    fun holdsTheLastSpeedThroughATunnelThenGivesUp() {
        val car = GpsCar(swift)
        car.drive(3) { 50.0 }
        now += 10_000_000_000L
        assertEquals(50.0, car.at(now)!!.speedKmh, 0.5)
        now += 6_000_000_000L
        assertNull(car.at(now))
        // And picks up again from the next fix, without a trend made up from the gap.
        car.fix(30.0, now)
        val back = car.at(now + 500_000_000L)!!
        assertTrue(abs(back.speedKmh - 40.0) < 10.1)
    }

    @Test
    fun followsTheCarSetUpInSettings() {
        val car = GpsCar(swift)
        car.drive(5) { 50.0 }
        car.setup = swift.copy(finalDrive = swift.finalDrive * 1.2f)
        val state = car.at(now)!!
        assertEquals(50 * gearing[state.gear - 1] * 1.2f, state.rpm.toFloat(), 3f)
    }
}
