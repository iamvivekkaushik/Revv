package com.vivekkaushik.revv.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    private val start = LatLon(28.0, 77.0)
    private val corner = LatLon(28.0, 77.01)
    private val end = LatLon(28.01, 77.01)

    /** Heads east for about 980 m, then north for about 1.1 km. */
    private val path = Path(listOf(start, corner, end))

    @Test
    fun distance_matchesKnownValues() {
        // A hundredth of a degree of latitude is 1.112 km anywhere.
        assertEquals(1_112.0, Geo.distance(corner, end), 1.0)
        // Longitude shrinks with the cosine of the latitude.
        assertEquals(981.8, Geo.distance(start, corner), 1.0)
    }

    @Test
    fun bearing_pointsTheRightWay() {
        assertEquals(90.0, Geo.bearing(start, corner), 0.1)
        assertEquals(0.0, Geo.bearing(corner, end), 0.1)
        assertEquals(270.0, Geo.bearing(corner, start), 0.1)
    }

    @Test
    fun decodePolyline_readsGooglesExample() {
        val points = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, points.size)
        assertEquals(LatLon(38.5, -120.2), points[0])
        assertEquals(40.7, points[1].lat, 1e-9)
        assertEquals(-120.95, points[1].lon, 1e-9)
        assertEquals(43.252, points[2].lat, 1e-9)
        assertEquals(-126.453, points[2].lon, 1e-9)
    }

    @Test
    fun decodePolyline_readsSixDigitPrecision() {
        val points = Geo.decodePolyline(Polyline6.encode(listOf(start, corner, end)))
        assertEquals(listOf(start, corner, end), points)
    }

    @Test
    fun path_measuresAlongItsPoints() {
        assertEquals(0.0, path.along[0], 0.0)
        assertEquals(981.8, path.along[1], 1.0)
        assertEquals(981.8 + 1_112.0, path.length, 2.0)
    }

    @Test
    fun pointAt_interpolatesAndKnowsTheDirection() {
        val halfway = path.pointAt(491.0)
        assertEquals(0, halfway.segment)
        assertEquals(28.0, halfway.position.lat, 1e-9)
        assertEquals(77.005, halfway.position.lon, 1e-4)
        assertEquals(90.0, halfway.bearing, 0.1)
        assertEquals(0.0, path.pointAt(1_500.0).bearing, 0.1)
        assertEquals(end, path.pointAt(99_999.0).position)
    }

    @Test
    fun locate_snapsANearbyFixOntoThePath() {
        // About 22 m north of the first stretch, a third of the way along.
        val fix = path.locate(LatLon(28.0002, 77.0033))
        assertEquals(0, fix.segment)
        assertEquals(323.9, fix.along, 2.0)
        assertEquals(22.2, fix.offset, 1.0)
        assertEquals(28.0, fix.position.lat, 1e-6)
    }

    @Test
    fun locate_findsTheSecondStretch() {
        val fix = path.locate(LatLon(28.005, 77.0101))
        assertEquals(1, fix.segment)
        assertEquals(981.8 + 556.0, fix.along, 2.0)
        assertTrue("offset ${fix.offset}", fix.offset < 15.0)
    }

    @Test
    fun locate_nearTheLastSpotDoesntJumpBack() {
        // A road that doubles back beside itself: out east, then back west 30 m further north.
        val loop = Path(listOf(LatLon(28.0, 77.0), LatLon(28.0, 77.01), LatLon(28.0003, 77.01), LatLon(28.0003, 77.0)))
        val onTheWayBack = loop.locate(LatLon(28.0003, 77.005))
        assertEquals(2, onTheWayBack.segment)
        // Just north of the outward stretch, but the car was last seen on the way back.
        val next = loop.locate(LatLon(28.00014, 77.004), near = onTheWayBack)
        assertEquals(2, next.segment)
    }

    @Test
    fun remainingFrom_startsAtTheCar() {
        val fix = path.locate(LatLon(28.0, 77.005))
        val rest = path.remainingFrom(fix)
        assertEquals(3, rest.size)
        assertEquals(fix.position, rest.first())
        assertEquals(end, rest.last())
    }
}

/** Encodes polylines for test data, the inverse of [Geo.decodePolyline]. */
object Polyline6 {
    fun encode(points: List<LatLon>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        fun put(value: Long) {
            var v = if (value < 0) (value shl 1).inv() else value shl 1
            while (v >= 0x20) {
                out.append(((0x20 or (v and 0x1F).toInt()) + 63).toChar())
                v = v shr 5
            }
            out.append((v + 63).toInt().toChar())
        }
        for (point in points) {
            val lat = Math.round(point.lat * factor)
            val lon = Math.round(point.lon * factor)
            put(lat - lastLat)
            put(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return out.toString()
    }
}
