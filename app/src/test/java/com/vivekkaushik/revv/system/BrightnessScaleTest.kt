package com.vivekkaushik.revv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessScaleTest {

    /** Android's default range. */
    private val scale = BrightnessScale(10, 255)

    @Test
    fun followsAndroidsSliderCurve() {
        assertEquals(10, scale.settingOf(0))
        // Halfway along the slider is a twelfth of the backlight.
        assertEquals(30, scale.settingOf(50))
        assertEquals(94, scale.settingOf(80))
        assertEquals(255, scale.settingOf(100))
    }

    @Test
    fun readsBackRevvsOwnSteps_asRoundNumbers() {
        for (percent in 0..100 step BrightnessScale.STEP) assertEquals(percent, scale.percentOf(scale.settingOf(percent)))
    }

    @Test
    fun readsOtherSettings_alongTheCurve() {
        assertEquals(86, scale.percentOf(128))
        assertEquals(16, scale.percentOf(12))
        assertEquals(0, scale.percentOf(1))
        assertEquals(100, scale.percentOf(300))
    }

    @Test
    fun everyStep_changesTheBacklight() {
        for (percent in 0 until 100 step BrightnessScale.STEP) {
            assertTrue("$percent%", scale.settingOf(percent + BrightnessScale.STEP) > scale.settingOf(percent))
        }
    }

    @Test
    fun aSettingPastTheDeclaredTop_widensTheScale() {
        // OnePlus declares 4..255 but keeps the setting in the thousands.
        val onePlus = BrightnessScale(4, 255)
        assertEquals(255, onePlus.widenedFor(147).max)
        assertEquals(1023, onePlus.widenedFor(1020).max)
        assertEquals(1023, onePlus.widenedFor(1023).max)
        assertEquals(2047, onePlus.widenedFor(1024).max)
        assertEquals(4, onePlus.widenedFor(1020).min)
        assertEquals(100, onePlus.widenedFor(1023).percentOf(1023))
    }

    @Test
    fun steps_landOnTheTens() {
        assertEquals(80, BrightnessScale.step(70, 1))
        assertEquals(60, BrightnessScale.step(70, -1))
        assertEquals(20, BrightnessScale.step(16, 1))
        assertEquals(10, BrightnessScale.step(16, -1))
        assertEquals(0, BrightnessScale.step(0, -1))
        assertEquals(100, BrightnessScale.step(100, 1))
    }
}
