package com.vivekkaushik.revv.nav

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What the driver does at a maneuver. Left and right forms share icons, mirrored. */
enum class Turn(val left: Boolean = false) {
    Depart,
    Straight,
    SlightRight,
    Right,
    SharpRight,
    UTurnRight,
    UTurnLeft(left = true),
    SharpLeft(left = true),
    Left(left = true),
    SlightLeft(left = true),
    KeepRight,
    KeepLeft(left = true),
    RampRight,
    RampLeft(left = true),
    ExitRight,
    ExitLeft(left = true),
    Merge,
    Roundabout,
    LeaveRoundabout,
    Ferry,
    Arrive,
    ;

    /** Carries on along the same road: nothing for the driver to do, so guidance looks past it. */
    val isPassive: Boolean get() = this == Straight || this == Depart
}

/**
 * A point on the route where something happens. [road] is the road taken afterwards, when named;
 * [along] is where it happens, in metres from the start; [seconds] is the time for the stretch
 * from here to the next maneuver.
 */
data class Maneuver(
    val turn: Turn,
    val road: String?,
    val along: Double,
    val seconds: Double,
    val roundaboutExit: Int? = null,
)

/** A place to drive to, from search or the recent list. */
data class Place(val name: String, val detail: String, val position: LatLon)

class Route(val path: Path, val maneuvers: List<Maneuver>, val seconds: Double, val hasTolls: Boolean) {
    val metres: Double get() = path.length

    val end: LatLon get() = path.points.last()
}

/** Why a route couldn't be worked out, in words for the driver. */
class RouteException(message: String) : Exception(message)

/** Reads Valhalla's `/route` answers (with `directions_type=maneuvers`). */
object ValhallaParser {

    fun parse(json: String): Route {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw RouteException("The route server sent something unreadable")
        }
        root.optJSONObject("trip")?.let { return parseTrip(it) }
        throw RouteException(errorMessage(root))
    }

    /** Valhalla reports problems as `{"error_code": 171, "error": "No suitable edges near location"}`. */
    fun errorMessage(root: JSONObject): String = when (root.optInt("error_code")) {
        154 -> "That's too far to drive in one go"
        170, 171 -> "No road near one of the points"
        442, 443 -> "No road route between these places"
        else -> root.optString("error").ifEmpty { "The route server couldn't find a route" }
    }

    private fun parseTrip(trip: JSONObject): Route {
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        var points = Geo.decodePolyline(leg.getString("shape"), precision = 6)
        if (points.size == 1) points = points + points
        val path = Path(points)
        val maneuvers = leg.getJSONArray("maneuvers").let { list ->
            (0 until list.length()).map { i -> maneuver(list.getJSONObject(i), path) }
        }
        val summary = trip.optJSONObject("summary")
        val seconds = summary?.optDouble("time")?.takeUnless { it.isNaN() } ?: maneuvers.sumOf { it.seconds }
        return Route(path, maneuvers, seconds, hasTolls = summary?.optBoolean("has_toll") == true)
    }

    private fun maneuver(json: JSONObject, path: Path): Maneuver {
        val index = json.optInt("begin_shape_index").coerceIn(0, path.points.size - 1)
        val exit = json.optInt("roundabout_exit_count").takeIf { it > 0 }
        return Maneuver(
            turn = turnFor(json.optInt("type")),
            road = firstName(json.optJSONArray("street_names")) ?: firstName(json.optJSONArray("begin_street_names")),
            along = path.along[index],
            seconds = json.optDouble("time").takeUnless { it.isNaN() } ?: 0.0,
            roundaboutExit = exit,
        )
    }

    private fun firstName(names: JSONArray?): String? =
        names?.takeIf { it.length() > 0 && !it.isNull(0) }?.optString(0)?.takeIf { it.isNotBlank() }

    /** Valhalla's maneuver types, from its `DirectionsLeg_Maneuver_Type`. */
    internal fun turnFor(type: Int): Turn = when (type) {
        1, 2, 3 -> Turn.Depart
        4, 5, 6 -> Turn.Arrive
        9 -> Turn.SlightRight
        10 -> Turn.Right
        11 -> Turn.SharpRight
        12 -> Turn.UTurnRight
        13 -> Turn.UTurnLeft
        14 -> Turn.SharpLeft
        15 -> Turn.Left
        16 -> Turn.SlightLeft
        18 -> Turn.RampRight
        19 -> Turn.RampLeft
        20 -> Turn.ExitRight
        21 -> Turn.ExitLeft
        23 -> Turn.KeepRight
        24 -> Turn.KeepLeft
        25, 36, 37 -> Turn.Merge
        26 -> Turn.Roundabout
        27 -> Turn.LeaveRoundabout
        28, 29 -> Turn.Ferry
        // 7 becomes, 8 continue, 17 ramp straight, 22 stay straight, and anything newer.
        else -> Turn.Straight
    }
}

/** Reads Photon's GeoJSON search answers into places, dropping near-duplicates. */
object PhotonParser {

    fun parse(json: String): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val places = ArrayList<Place>()
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val position = LatLon(coordinates.getDouble(1), coordinates.getDouble(0))
            val name = properties.text("name")
                ?: listOfNotNull(properties.text("housenumber"), properties.text("street")).joinToString(" ").ifEmpty { null }
                ?: continue
            // The same stop or building often comes back several times under different tags.
            if (places.any { it.name.equals(name, ignoreCase = true) && Geo.distance(it.position, position) < 250 }) continue
            places += Place(name, detailOf(properties, name), position)
        }
        return places
    }

    private fun detailOf(properties: JSONObject, name: String): String {
        val street = properties.text("street")?.let { street ->
            properties.text("housenumber")?.let { "$it $street" } ?: street
        }
        return listOfNotNull(
            street,
            properties.text("district") ?: properties.text("locality"),
            properties.text("city") ?: properties.text("county"),
            properties.text("state"),
        )
            .filter { !it.equals(name, ignoreCase = true) }
            .distinct()
            .take(3)
            .joinToString(" · ")
    }

    // optString turns a JSON null into the text "null", so check for it first.
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

/** Reads Google Places API (New) Text Search answers into places. */
object GooglePlacesParser {

    fun parse(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("places") ?: return emptyList()
        val places = ArrayList<Place>()
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            val location = result.optJSONObject("location") ?: continue
            val name = result.optJSONObject("displayName")?.optString("text")?.takeIf { it.isNotBlank() } ?: continue
            val address = result.optString("formattedAddress")
            // The address often starts with the name again.
            val detail = address.removePrefix(name).trimStart(',', ' ')
            places += Place(name, detail, LatLon(location.getDouble("latitude"), location.getDouble("longitude")))
        }
        return places
    }

    /** Google's reason for an error answer, cut to its first sentence; null when there is none. */
    fun error(json: String): String? = runCatching {
        JSONObject(json).getJSONObject("error").getString("message").substringBefore(". ").trimEnd('.')
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "Google: $it" }
}

/** Reads Google Routes API `computeRoutes` answers into a route Navigation can follow. */
object GoogleRoutesParser {

    fun parse(json: String): Route {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw RouteException("Google sent something unreadable")
        }
        val route = root.optJSONArray("routes")?.optJSONObject(0) ?: throw RouteException("No road route between these places")
        val steps = route.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps")
        // The steps' polylines joined make the path, and each step's maneuver happens where it starts.
        val points = ArrayList<LatLon>()
        val starts = ArrayList<Int>()
        for (i in 0 until (steps?.length() ?: 0)) {
            val encoded = steps!!.getJSONObject(i).optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
            val stepPoints = Geo.decodePolyline(encoded, precision = 5)
            starts += (points.size - 1).coerceAtLeast(0)
            points += if (points.isEmpty()) stepPoints else stepPoints.drop(1)
        }
        if (points.isEmpty()) {
            points += Geo.decodePolyline(route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty(), precision = 5)
        }
        if (points.isEmpty()) throw RouteException("Google sent a route without a path")
        if (points.size == 1) points += points[0]
        val path = Path(points)
        val stepSeconds = (0 until starts.size).map { seconds(steps!!.getJSONObject(it).optString("staticDuration")) }
        val total = seconds(route.optString("duration")).takeIf { it > 0 } ?: stepSeconds.sum()
        // Steps are timed without traffic; share the traffic-aware total out between them.
        val traffic = if (stepSeconds.sum() > 0) total / stepSeconds.sum() else 1.0
        val maneuvers = starts.indices.map { i ->
            val instruction = steps!!.getJSONObject(i).optJSONObject("navigationInstruction")
            val text = instruction?.optString("instructions").orEmpty().lineSequence().firstOrNull().orEmpty()
            Maneuver(
                turn = turnFor(instruction?.optString("maneuver").orEmpty()),
                road = roadIn(text),
                along = path.along[starts[i].coerceIn(0, points.size - 1)],
                seconds = stepSeconds[i] * traffic,
                roundaboutExit = EXIT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            )
        } + Maneuver(Turn.Arrive, null, path.length, 0.0)
        // Google only says whether there are tolls in a dearer tier; Navigation doesn't use it.
        return Route(path, maneuvers, total, hasTolls = false)
    }

    /** Google's `Maneuver` names. */
    internal fun turnFor(maneuver: String): Turn = when (maneuver) {
        "DEPART" -> Turn.Depart
        "TURN_SLIGHT_LEFT" -> Turn.SlightLeft
        "TURN_SHARP_LEFT" -> Turn.SharpLeft
        "UTURN_LEFT" -> Turn.UTurnLeft
        "TURN_LEFT" -> Turn.Left
        "TURN_SLIGHT_RIGHT" -> Turn.SlightRight
        "TURN_SHARP_RIGHT" -> Turn.SharpRight
        "UTURN_RIGHT" -> Turn.UTurnRight
        "TURN_RIGHT" -> Turn.Right
        "RAMP_LEFT" -> Turn.RampLeft
        "RAMP_RIGHT" -> Turn.RampRight
        "MERGE" -> Turn.Merge
        "FORK_LEFT" -> Turn.KeepLeft
        "FORK_RIGHT" -> Turn.KeepRight
        "FERRY", "FERRY_TRAIN" -> Turn.Ferry
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> Turn.Roundabout
        // STRAIGHT, NAME_CHANGE and anything newer.
        else -> Turn.Straight
    }

    /** The road an instruction leads onto: "Turn left onto MG Road" → "MG Road". */
    internal fun roadIn(instruction: String): String? =
        ROAD.find(instruction)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    /** Google's durations look like "754s". */
    private fun seconds(duration: String): Double = duration.removeSuffix("s").toDoubleOrNull() ?: 0.0

    private val ROAD = Regex("""\b(?:onto|on|toward|towards)\s+(.+)$""")
    private val EXIT = Regex("""(\d+)(?:st|nd|rd|th) exit""")
}
