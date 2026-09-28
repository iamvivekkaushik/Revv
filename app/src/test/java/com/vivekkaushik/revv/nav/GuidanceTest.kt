package com.vivekkaushik.revv.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {

    private val path = Path(listOf(LatLon(28.0, 77.0), LatLon(28.0, 77.01), LatLon(28.01, 77.01)))
    private val corner = path.along[1]

    /** Depart east, turn left at the corner, a name change halfway up, then arrive. */
    private val route = Route(
        path,
        listOf(
            Maneuver(Turn.Depart, "Park Drive", along = 0.0, seconds = 60.0),
            Maneuver(Turn.Left, "Golf Course Road", along = corner, seconds = 50.0),
            Maneuver(Turn.Straight, "Golf Course Road Underpass", along = corner + 556.0, seconds = 50.0),
            Maneuver(Turn.Arrive, null, along = path.length, seconds = 0.0),
        ),
        seconds = 160.0,
        hasTolls = false,
    )

    private fun at(lat: Double, lon: Double) = Guidance.at(route, path.locate(LatLon(lat, lon)))

    @Test
    fun beforeTheCorner_theLeftTurnIsNext() {
        val guidance = at(28.0, 77.005)
        assertEquals(Turn.Left, guidance.next.turn)
        assertEquals(corner / 2, guidance.metresToNext, 2.0)
        assertEquals(path.length - corner / 2, guidance.remainingMetres, 2.0)
        // Half the first stretch, then the rest.
        assertEquals(30.0 + 50.0 + 50.0, guidance.remainingSeconds, 0.5)
        assertFalse(guidance.arrived)
    }

    @Test
    fun afterTheTurn_aNameChangeIsNothingToActOn() {
        val guidance = at(28.002, 77.01)
        assertEquals(Turn.Arrive, guidance.next.turn)
        assertEquals(path.length - guidance.fix.along, guidance.metresToNext, 0.001)
    }

    @Test
    fun remainingTime_countsDownAcrossStretches() {
        val beyondTheNameChange = at(28.0075, 77.01)
        assertTrue("was ${beyondTheNameChange.remainingSeconds}", beyondTheNameChange.remainingSeconds in 5.0..45.0)
    }

    @Test
    fun atTheEnd_thatsArrival() {
        val guidance = at(28.0099, 77.01)
        assertTrue(guidance.arrived)
        assertTrue("was ${guidance.remainingSeconds}", guidance.remainingSeconds < 2.0)
    }

    @Test
    fun aRouteWithoutManeuvers_stillArrives() {
        val bare = Route(path, emptyList(), seconds = 100.0, hasTolls = false)
        val guidance = Guidance.at(bare, path.locate(LatLon(28.0, 77.005)))
        assertEquals(Turn.Arrive, guidance.next.turn)
    }

    @Test
    fun demoDrive_followsThePathAndStopsOrLoopsAtTheEnd() {
        val car = DemoDrive(path, loops = false)
        val moving = car.advance(500.0, speedMps = 16.0, nowMillis = 1L)
        assertEquals(90.0, moving.bearing!!, 0.1)
        assertEquals(16.0, moving.speedMps!!, 0.0)
        car.advance(5_000.0, speedMps = 16.0, nowMillis = 2L)
        assertEquals(path.length, car.travelled, 0.001)

        car.follow(path, loops = true)
        car.advance(path.length + 100.0, speedMps = 16.0, nowMillis = 3L)
        assertEquals(100.0, car.travelled, 0.001)
    }
}
