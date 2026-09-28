package com.vivekkaushik.revv.nav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position in degrees. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS_METRES = 6_371_008.8

    /** Great-circle distance in metres. */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = radians(b.lat - a.lat)
        val dLon = radians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(radians(a.lat)) * cos(radians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial compass bearing from [a] to [b], 0 until 360 degrees. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val lat1 = radians(a.lat)
        val lat2 = radians(b.lat)
        val dLon = radians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (degrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Decodes a Google-style encoded polyline; Valhalla uses [precision] 6. */
    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = encoded[index++].code - 63
                result = result or ((byte and 0x1F).toLong() shl shift)
                shift += 5
                if (byte < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            lat += next()
            lon += next()
            points += LatLon(lat / factor, lon / factor)
        }
        return points
    }

    internal fun radians(degrees: Double) = degrees * PI / 180.0
    internal fun degrees(radians: Double) = radians * 180.0 / PI

    /**
     * Where [point] falls on the segment [a]–[b], worked out on a flat local projection, which is
     * accurate to centimetres over a road segment's length.
     */
    internal fun project(point: LatLon, a: LatLon, b: LatLon): SegmentFix {
        val metresPerDegreeLat = EARTH_RADIUS_METRES * PI / 180.0
        val metresPerDegreeLon = metresPerDegreeLat * cos(radians((a.lat + b.lat) / 2))
        val bx = (b.lon - a.lon) * metresPerDegreeLon
        val by = (b.lat - a.lat) * metresPerDegreeLat
        val px = (point.lon - a.lon) * metresPerDegreeLon
        val py = (point.lat - a.lat) * metresPerDegreeLat
        val lengthSquared = bx * bx + by * by
        val t = if (lengthSquared == 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        return SegmentFix(t, hypot(px - bx * t, py - by * t))
    }

    internal fun interpolate(a: LatLon, b: LatLon, t: Double) =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}

/** [t] is how far along the segment (0 to 1), [offset] how far off it in metres. */
internal class SegmentFix(val t: Double, val offset: Double)

/** A spot on a [Path]: [along] metres from its start, [offset] metres beside it. */
data class PathFix(
    val segment: Int,
    val along: Double,
    val offset: Double,
    val position: LatLon,
    val bearing: Double,
)

/** A polyline that knows the distance to each of its points, for following a car along a route. */
class Path(val points: List<LatLon>) {

    /** Metres from the start to each point. */
    val along: DoubleArray = DoubleArray(points.size).also { along ->
        for (i in 1 until points.size) along[i] = along[i - 1] + Geo.distance(points[i - 1], points[i])
    }

    val length: Double get() = if (along.isEmpty()) 0.0 else along.last()

    init {
        require(points.size >= 2) { "A path needs at least two points" }
    }

    /** The point [distance] metres along, and which way the path heads there. */
    fun pointAt(distance: Double): PathFix {
        val d = distance.coerceIn(0.0, length)
        var segment = along.binarySearch(d).let { if (it >= 0) it else -it - 2 }
        segment = segment.coerceIn(0, points.size - 2)
        val a = points[segment]
        val b = points[segment + 1]
        val span = along[segment + 1] - along[segment]
        val t = if (span == 0.0) 0.0 else (d - along[segment]) / span
        return PathFix(segment, d, 0.0, Geo.interpolate(a, b, t), bearingOf(segment))
    }

    /**
     * The closest spot on the path to [position]. Searching starts a few metres behind [near] (the
     * last known spot, allowing for GPS jitter) and runs ahead [window] metres, so a road that
     * doubles back on itself or runs beside an earlier stretch doesn't make the car jump. Without
     * [near], the whole path is searched.
     */
    fun locate(position: LatLon, near: PathFix? = null, window: Double = 2_000.0): PathFix {
        var first = 0
        var last = points.size - 2
        if (near != null) {
            first = near.segment.coerceAtMost(last)
            while (first > 0 && along[first] > near.along - BEHIND_METRES) first--
            last = first
            while (last < points.size - 2 && along[last] < near.along + window) last++
        }
        var best: PathFix? = null
        for (i in first..last) {
            val fix = Geo.project(position, points[i], points[i + 1])
            if (best == null || fix.offset < best.offset) {
                val along = along[i] + (along[i + 1] - along[i]) * fix.t
                best = PathFix(i, along, fix.offset, Geo.interpolate(points[i], points[i + 1], fix.t), bearingOf(i))
            }
        }
        return best!!
    }

    /** The rest of the path from [fix] on, for drawing what's left of a route. */
    fun remainingFrom(fix: PathFix): List<LatLon> {
        val rest = ArrayList<LatLon>(points.size - fix.segment)
        rest += fix.position
        for (i in fix.segment + 1 until points.size) rest += points[i]
        return rest
    }

    private companion object {
        const val BEHIND_METRES = 30.0
    }

    private fun bearingOf(segment: Int): Double {
        // Zero-length segments (repeated points) take the direction of the next real one.
        var i = segment
        while (i < points.size - 2 && points[i] == points[i + 1]) i++
        return Geo.bearing(points[i], points[i + 1])
    }
}
