package com.vivekkaushik.revv.system

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightDimmingTest {

    @Test
    fun theFirstNight_dimsToTheFirstNightLevel_andKeepsTheDayLevel() {
        val (kept, level) = Dimming().turn(toNight = true, current = 80)!!
        assertEquals(Dimming.FIRST_NIGHT_LEVEL, level)
        assertEquals(Dimming(night = true, dayLevel = 80, nightLevel = null), kept)
    }

    @Test
    fun aDimDayScreen_isntBrightenedForTheNight() {
        assertEquals(30, Dimming().turn(toNight = true, current = 30)!!.second)
    }

    @Test
    fun atSunrise_theDayLevelComesBack_andTheNightsLevelIsKept() {
        // The driver turned the night level down to 20.
        val night = Dimming(night = true, dayLevel = 80)
        val (kept, level) = night.turn(toNight = false, current = 20)!!
        assertEquals(80, level)
        assertEquals(Dimming(night = false, dayLevel = 80, nightLevel = 20), kept)
        // And the next night is at 20 again, wherever the day was left.
        assertEquals(20, kept.turn(toNight = true, current = 90)!!.second)
    }

    @Test
    fun nothingChanges_whileThePeriodIsTheSame() {
        assertNull(Dimming().turn(toNight = false, current = 70))
        assertNull(Dimming(night = true).turn(toNight = true, current = 40))
    }

    @Test
    fun beforeGpsFindsTheCar_nightRunsByTheClock() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun at(time: String) = LocalDateTime.parse(time).atZone(zone).toInstant()

        val early = NightSchedule.byClock(at("2026-10-02T05:00"), zone)
        assertTrue(early.night)
        assertEquals(at("2026-10-02T06:00"), early.until)
        assertFalse(early.located)

        val noon = NightSchedule.byClock(at("2026-10-02T12:00"), zone)
        assertFalse(noon.night)
        assertEquals(at("2026-10-02T18:30"), noon.until)

        val late = NightSchedule.byClock(at("2026-10-02T22:00"), zone)
        assertTrue(late.night)
        assertEquals(at("2026-10-03T06:00"), late.until)
    }

    @Test
    fun theLightSensor_takesOverFromSunsetDimming() {
        assertEquals(NightMode.LightSensor, NightMode.of(lightSensor = true, sunset = true))
        assertEquals(NightMode.Sunset, NightMode.of(lightSensor = false, sunset = true))
        assertEquals(NightMode.Off, NightMode.of(lightSensor = false, sunset = false))
    }
}
