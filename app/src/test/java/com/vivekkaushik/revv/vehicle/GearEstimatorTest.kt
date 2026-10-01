package com.vivekkaushik.revv.vehicle

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearEstimatorTest {

    private val swift = CarSetup.SWIFT_VXI_2015

    /** Feeds an estimator readings five times a second, as polling does. */
    private class Drive(val gears: GearEstimator) {
        private var now = 1_000L

        /** Holds [speedKmh] at [rpm] for [seconds]; returns the gear shown at the end. */
        fun hold(speedKmh: Int, rpm: Int, seconds: Double = 1.0): Int? {
            var gear: Int? = null
            repeat((seconds * 5).roundToInt().coerceAtLeast(1)) {
                now += 200
                gear = gears.update(speedKmh, rpm, now)
            }
            return gear
        }

        /** Drives in a gear turning [rpmPerKmh] at [speedKmh]. */
        fun inGear(rpmPerKmh: Float, speedKmh: Int, seconds: Double = 1.0) = hold(speedKmh, (rpmPerKmh * speedKmh).roundToInt(), seconds)
    }

    @Test
    fun fromRatios_eachGearIsKnownAtOnce() {
        val drive = Drive(GearEstimator(swift))
        val gearing = swift.rpmPerKmh()
        assertEquals(1, drive.inGear(gearing[0], 15))
        assertEquals(2, drive.inGear(gearing[1], 35))
        assertEquals(3, drive.inGear(gearing[2], 50))
        assertEquals(4, drive.inGear(gearing[3], 70))
        assertEquals(5, drive.inGear(gearing[4], 100))
    }

    @Test
    fun neutral_isShownOnlyStandingStill() {
        val drive = Drive(GearEstimator(swift))
        assertEquals(4, drive.inGear(swift.rpmPerKmh()[3], 70))
        // Clutch down, coasting at idle: still in fourth as far as the strip is concerned.
        assertEquals(4, drive.hold(speedKmh = 60, rpm = 850, seconds = 5.0))
        assertEquals(0, drive.hold(speedKmh = 0, rpm = 850))
    }

    @Test
    fun movingOff_showsFirst_untilTheCarShowsAnotherGear() {
        val drive = Drive(GearEstimator(swift))
        assertEquals(0, drive.hold(speedKmh = 0, rpm = 850))
        assertEquals(1, drive.hold(speedKmh = 3, rpm = 900))
        assertEquals(2, drive.inGear(swift.rpmPerKmh()[1], 20))
    }

    @Test
    fun learning_withNothingLearnt_lightsNothingWhileMoving() {
        val drive = Drive(GearEstimator(swift.copy(gearSource = GearSource.Learnt)))
        assertNull(drive.hold(speedKmh = 50, rpm = 2_000))
        assertEquals(0, drive.hold(speedKmh = 0, rpm = 850))
    }

    @Test
    fun learning_picksUpASixSpeedFromOneDrive() {
        val sixSpeed = listOf(120f, 70f, 48f, 37f, 30f, 25f)
        val gears = GearEstimator(swift.copy(gearSource = GearSource.Learnt).withGears(6))
        val drive = Drive(gears)
        drive.hold(speedKmh = 0, rpm = 850, seconds = 2.0)
        // Moving off in first, then up through the box, a few seconds in each.
        drive.inGear(sixSpeed[0], 10, seconds = 1.5)
        listOf(15, 30, 45, 60, 80, 100).zip(sixSpeed).forEach { (speed, gearing) -> drive.inGear(gearing, speed, seconds = 8.0) }

        val learnt = gears.learnt()
        assertEquals(6, learnt.size)
        learnt.zip(sixSpeed).forEach { (actual, wanted) -> assertEquals(wanted, actual, wanted * 0.02f) }
        assertEquals(3, drive.inGear(sixSpeed[2], 40))
        assertEquals(6, drive.inGear(sixSpeed[5], 110))
    }

    @Test
    fun learntGears_carryOverToTheNextDrive() {
        val gears = GearEstimator(swift.copy(gearSource = GearSource.Learnt), learnt = swift.rpmPerKmh())
        assertEquals(3, Drive(gears).inGear(swift.rpmPerKmh()[2], 50))
    }

    @Test
    fun switchingToRatios_midDrive_takesEffectAtOnce() {
        val gears = GearEstimator(swift.copy(gearSource = GearSource.Learnt))
        val drive = Drive(gears)
        assertNull(drive.inGear(swift.rpmPerKmh()[2], 50))
        gears.setup = swift
        assertEquals(3, drive.inGear(swift.rpmPerKmh()[2], 50))
    }
}
