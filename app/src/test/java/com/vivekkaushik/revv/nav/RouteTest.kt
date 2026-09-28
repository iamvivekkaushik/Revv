package com.vivekkaushik.revv.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouteTest {

    private val shape = Polyline6.encode(listOf(LatLon(28.0, 77.0), LatLon(28.0, 77.01), LatLon(28.01, 77.01)))

    /** East, then a left turn north onto Golf Course Road, as Valhalla describes it. */
    private val trip = """
        {"trip": {
          "summary": {"length": 2.094, "time": 180.0, "has_toll": true},
          "legs": [{"shape": "$shape", "maneuvers": [
            {"type": 1, "street_names": ["Park Drive"], "time": 60.0, "length": 0.982, "begin_shape_index": 0, "end_shape_index": 1},
            {"type": 15, "street_names": ["Golf Course Road", "NH48"], "time": 120.0, "length": 1.112, "begin_shape_index": 1, "end_shape_index": 2},
            {"type": 4, "time": 0.0, "length": 0.0, "begin_shape_index": 2, "end_shape_index": 2}
          ]}]
        }}
    """.trimIndent()

    @Test
    fun parse_readsTheShapeAndManeuvers() {
        val route = ValhallaParser.parse(trip)
        assertEquals(3, route.path.points.size)
        assertEquals(2_094.0, route.metres, 3.0)
        assertEquals(180.0, route.seconds, 0.0)
        assertTrue(route.hasTolls)
        assertEquals(listOf(Turn.Depart, Turn.Left, Turn.Arrive), route.maneuvers.map { it.turn })
        val left = route.maneuvers[1]
        assertEquals("Golf Course Road", left.road)
        assertEquals(981.8, left.along, 1.0)
        assertEquals(120.0, left.seconds, 0.0)
        assertNull(route.maneuvers[2].road)
    }

    @Test
    fun parse_turnsErrorsIntoReasons() {
        val error = """{"error_code":171,"error":"No suitable edges near location","status_code":400,"status":"Bad Request"}"""
        try {
            ValhallaParser.parse(error)
            fail("expected a RouteException")
        } catch (e: RouteException) {
            assertEquals("No road near one of the points", e.message)
        }
    }

    @Test
    fun parse_rejectsGarbage() {
        try {
            ValhallaParser.parse("<html>Bad gateway</html>")
            fail("expected a RouteException")
        } catch (e: RouteException) {
            assertEquals("The route server sent something unreadable", e.message)
        }
    }

    @Test
    fun turnTypes_mapBothWays() {
        assertEquals(Turn.Right, ValhallaParser.turnFor(10))
        assertEquals(Turn.SlightLeft, ValhallaParser.turnFor(16))
        assertEquals(Turn.KeepRight, ValhallaParser.turnFor(23))
        assertEquals(Turn.Roundabout, ValhallaParser.turnFor(26))
        assertEquals(Turn.Arrive, ValhallaParser.turnFor(5))
        assertEquals(Turn.Straight, ValhallaParser.turnFor(8))
        assertTrue(Turn.Left.left)
        assertFalse(Turn.Right.left)
    }

    @Test
    fun demoRoute_isARealDriveUpGolfCourseRoad() {
        // Unit tests run from the module directory.
        val route = ValhallaParser.parse(File("src/main/assets/map/demo_route.json").readText())
        assertEquals(8_518.0, route.metres, 150.0)
        assertEquals(Turn.Depart, route.maneuvers.first().turn)
        assertEquals(Turn.Arrive, route.maneuvers.last().turn)
        assertEquals("Golf Course Road", route.maneuvers.first().road)
        assertTrue(route.maneuvers.zipWithNext().all { (a, b) -> a.along <= b.along })
    }

    @Test
    fun photon_readsPlacesAndDropsDuplicates() {
        val json = """
            {"type": "FeatureCollection", "features": [
              {"type": "Feature", "geometry": {"type": "Point", "coordinates": [77.10481, 28.43296]},
               "properties": {"osm_key": "railway", "name": "Sector 54 Chowk", "street": "Golf Course Road", "district": "Sector 53", "city": "Gurgaon", "state": "Haryana"}},
              {"type": "Feature", "geometry": {"type": "Point", "coordinates": [77.1049, 28.43298]},
               "properties": {"osm_key": "railway", "name": "Sector 54 Chowk", "street": "Golf Course Road", "city": "Gurgaon"}},
              {"type": "Feature", "geometry": {"type": "Point", "coordinates": [77.08851, 28.4951]},
               "properties": {"name": "DLF Cyber Hub", "street": null, "district": "Sector 25A", "city": "Gurgaon", "state": "Haryana"}},
              {"type": "Feature", "geometry": {"type": "Point", "coordinates": [77.1, 28.4]},
               "properties": {"housenumber": "12", "street": "Park Drive", "city": "Gurgaon"}},
              {"type": "Feature", "geometry": {"type": "Point", "coordinates": [77.2, 28.5]},
               "properties": {"city": "Gurgaon"}}
            ]}
        """.trimIndent()
        val places = PhotonParser.parse(json)
        assertEquals(listOf("Sector 54 Chowk", "DLF Cyber Hub", "12 Park Drive"), places.map { it.name })
        assertEquals("Golf Course Road · Sector 53 · Gurgaon", places[0].detail)
        assertEquals("Sector 25A · Gurgaon · Haryana", places[1].detail)
        assertEquals("Gurgaon", places[2].detail)
        assertEquals(LatLon(28.4951, 77.08851), places[1].position)
    }
}
