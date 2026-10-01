package com.vivekkaushik.revv.vehicle

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleMathTest {

    private val swift = VehicleProfile.SWIFT_VXI_2015

    @Test
    fun fuelFlow_fromMassAirFlow() {
        // 10 g/s of air at 14.7:1 is 0.68 g/s of petrol, about 3.3 L/h.
        assertEquals(3.31f, FuelMath.litresPerHour(10f), 0.01f)
    }

    @Test
    fun airFlow_fromManifoldPressure() {
        // 2,000 rpm at 50 kPa and 30 °C in a 1.2 L engine: roughly 9.7 g/s.
        assertEquals(9.7f, FuelMath.airFlow(50, 2000, 30, swift), 0.1f)
    }

    @Test
    fun economy_isUnknownWhileStationary() {
        assertNull(FuelMath.kmPerLitre(0, 1.2f))
        assertEquals(20f, FuelMath.kmPerLitre(60, 3f)!!, 0.001f)
        assertEquals(99.9f, FuelMath.kmPerLitre(80, 0.1f)!!, 0.001f)
    }

    @Test
    fun trip_addsDistanceFuelAndTime() {
        val trip = TripComputer()
        repeat(3600) { trip.add(1.0, speedKmh = 60, litresPerHour = 3f, engineRunning = true) }
        assertEquals(60.0, trip.distanceKm, 0.001)
        assertEquals(3.0, trip.fuelLitres, 0.001)
        assertEquals(3600.0, trip.engineSeconds, 0.001)
        assertEquals(20f, trip.averageKmPerLitre!!, 0.001f)
    }

    @Test
    fun trip_ignoresLongGapsAndShortTrips() {
        val trip = TripComputer()
        trip.add(600.0, speedKmh = 60, litresPerHour = 3f, engineRunning = true)
        // A ten-minute stall in the link counts as two seconds, not ten kilometres.
        assertEquals(33, (trip.distanceKm * 1000).roundToInt())
        assertNull(trip.averageKmPerLitre)
    }
}
