package com.vivekkaushik.revv.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayRouteTest {
    private val car = LatLon(28.4595, 77.0266)

    /** A place [kilometres] due north of the car. */
    private fun north(name: String, kilometres: Double) = Place(name, "", LatLon(car.lat + kilometres / 111.2, car.lon))

    @Test
    fun picksTheBranchARouteOfThatLengthReaches() {
        val near = north("WeWork", 2.0)
        val far = north("WeWork", 15.0)

        assertEquals(near, CarPlayRoute.pick(listOf(far, near), car, routeMeters = 2_700.0))
        assertEquals(far, CarPlayRoute.pick(listOf(near, far), car, routeMeters = 19_000.0))
    }

    @Test
    fun aPlaceFurtherInAStraightLineThanTheRouteIsNotIt() {
        assertNull(CarPlayRoute.pick(listOf(north("Mall", 10.0)), car, routeMeters = 4_000.0))
    }

    @Test
    fun aPlaceFarCloserThanTheRouteIsNotItEither() {
        assertNull(CarPlayRoute.pick(listOf(north("Mall", 1.0)), car, routeMeters = 12_000.0))
    }

    @Test
    fun likelierFitsComeFirst() {
        val usual = north("WeWork", 7.5)
        val roundabout = north("WeWork", 4.0)
        assertEquals(listOf(usual, roundabout), CarPlayRoute.fits(listOf(roundabout, usual, north("WeWork", 40.0)), car, routeMeters = 10_000.0))
    }

    @Test
    fun theRouteAsLongAsCarPlaysWins() {
        val a = north("Branch A", 70.0)
        val b = north("Branch B", 74.0)
        assertEquals(b, CarPlayRoute.byRouteLength(listOf(a to 93_000.0, b to 105_200.0), routeMeters = 105_000.0))
    }

    @Test
    fun noRouteNearCarPlaysLengthIsNoMatch() {
        assertNull(CarPlayRoute.byRouteLength(listOf(north("Mall", 20.0) to 40_000.0), routeMeters = 105_000.0))
    }

    @Test
    fun nothingToPickFromPicksNothing() {
        assertNull(CarPlayRoute.pick(emptyList(), car, routeMeters = 5_000.0))
    }
}
