package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.obd.ObdReadings
import com.vivekkaushik.revv.obd.ObdStatus
import com.vivekkaushik.revv.vehicle.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleFiguresTest {

    private val swift = VehicleProfile.SWIFT_VXI_2015

    @Test
    fun live_formatsReadingsAndWorksOutRange() {
        val readings = ObdReadings(
            coolantC = 88,
            intakeAirC = 34,
            ambientC = 31,
            fuelLevel = 50,
            batteryVolts = 14.04f,
            tripKm = 12.34f,
            tripAverageKmPerLitre = 20f,
            tripEngineSeconds = 3_900,
        )
        val figures = VehicleFigures.live(readings, ObdStatus(), swift)
        assertEquals("88", figures.coolant)
        assertEquals("14.0", figures.battery)
        assertEquals("31°C", figures.outsideTemperature)
        assertEquals(5, figures.fuelBars)
        // Half of a 42 L tank at 20 km/L.
        assertEquals("420", figures.range)
        assertEquals("21 L · AVG 20.0 KM/L", figures.fuelDetail)
        assertEquals("12.3", figures.tripKm)
        assertEquals("1:05", figures.driveTime)
        assertEquals(Health.Normal, figures.health)
        assertNull(figures.tyres)
    }

    @Test
    fun live_withoutFuelLevel_usesDashes() {
        val figures = VehicleFigures.live(ObdReadings(), ObdStatus(), swift)
        assertEquals(VehicleFigures.DASH, figures.range)
        assertEquals(VehicleFigures.DASH, figures.coolant)
        assertEquals("NO FUEL LEVEL · AVG -- KM/L", figures.fuelDetail)
        assertNull(figures.outsideTemperature)
    }

    @Test
    fun live_flagsTroubleCodes() {
        val status = ObdStatus(milOn = true, troubleCodeCount = 2, troubleCodes = listOf("P0171", "P0300"))
        val figures = VehicleFigures.live(ObdReadings(), status, swift)
        assertEquals(Health.Alert, figures.health)
        assertEquals("CHECK ENGINE · 2 CODES", figures.healthText)
        assertEquals(listOf("P0171", "P0300"), figures.troubleCodes)
    }

    @Test
    fun unknownValuesStayBareWithUnits() {
        assertEquals("89 °C", withUnit("89", "°C"))
        assertEquals(VehicleFigures.DASH, withUnit(VehicleFigures.DASH, "°C"))
    }
}
