package com.vivekkaushik.revv.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GearLearnerTest {

    @Test
    fun aRatioHeldLongEnough_becomesAGear() {
        val learner = GearLearner()
        learner.add(46f, seconds = 3.0)
        assertTrue("three seconds isn't enough", learner.gears().isEmpty())
        learner.add(47f, seconds = 4.0)
        assertEquals(1, learner.gears().size)
        assertEquals(46.6f, learner.gears().single(), 0.3f)
    }

    @Test
    fun movingOff_marksFirst_andAnythingLowerGearedIsASlippingClutch() {
        val learner = GearLearner()
        learner.add(200f, seconds = 10.0)
        learner.add(128f, seconds = 0.2, launch = true)
        learner.add(69f, seconds = 10.0)
        assertEquals(listOf(128f, 69f), learner.gears())
    }

    @Test
    fun restoredGears_keepTheirOrder() {
        val saved = listOf(128.6f, 69f, 46.4f, 35f, 27.5f)
        GearLearner(saved).gears().zip(saved).forEach { (actual, wanted) -> assertEquals(wanted, actual, 0.01f) }
    }
}
