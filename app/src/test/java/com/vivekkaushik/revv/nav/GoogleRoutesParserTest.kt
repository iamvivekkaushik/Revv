package com.vivekkaushik.revv.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRoutesParserTest {
    // Encoded polylines (precision 5): (38.5,-120.2) → (40.7,-120.95) → (43.252,-126.453), split in two steps.
    private val json = """
        {"routes": [{
          "duration": "1200s",
          "polyline": {"encodedPolyline": "_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
          "legs": [{"steps": [
            {"staticDuration": "300s", "polyline": {"encodedPolyline": "_p~iF~ps|U_ulLnnqC"},
             "navigationInstruction": {"maneuver": "DEPART", "instructions": "Head north on Golf Course Rd"}},
            {"staticDuration": "500s", "polyline": {"encodedPolyline": "_flwFn`faV_mqNvxq`@"},
             "navigationInstruction": {"maneuver": "TURN_LEFT", "instructions": "Turn left onto MG Road\nDestination will be on the right"}}
          ]}]
        }]}
    """.trimIndent()

    @Test
    fun stepsBecomeTheRouteAndItsTurns() {
        val route = GoogleRoutesParser.parse(json)

        assertEquals(3, route.path.points.size)
        assertEquals(listOf(Turn.Depart, Turn.Left, Turn.Arrive), route.maneuvers.map { it.turn })
        assertEquals("Golf Course Rd", route.maneuvers[0].road)
        assertEquals("MG Road", route.maneuvers[1].road)
        // The left turn happens where its step starts, the second point.
        assertEquals(route.path.along[1], route.maneuvers[1].along, 0.001)
        assertEquals(route.path.length, route.maneuvers.last().along, 0.001)
    }

    @Test
    fun stepTimesAddUpToTheTrafficAwareTotal() {
        val route = GoogleRoutesParser.parse(json)
        assertEquals(1200.0, route.seconds, 0.001)
        assertEquals(1200.0, route.maneuvers.sumOf { it.seconds }, 0.001)
    }

    @Test
    fun roadsAndExitsComeOutOfTheInstructions() {
        assertEquals("Sohna Road", GoogleRoutesParser.roadIn("At the roundabout, take the 2nd exit onto Sohna Road"))
        assertEquals("NH 48", GoogleRoutesParser.roadIn("Turn right to stay on NH 48"))
        assertNull(GoogleRoutesParser.roadIn("Turn right"))
    }

    @Test
    fun noRouteIsARouteException() {
        val failed = runCatching { GoogleRoutesParser.parse("{}") }.exceptionOrNull()
        assertTrue(failed is RouteException)
    }
}
