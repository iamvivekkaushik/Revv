package com.vivekkaushik.revv.nav

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Works out driving routes for Navigation. */
interface Router {
    /** Who works the routes out, for the log. */
    val name: String

    /**
     * [heading] (degrees, when moving) makes the route start off the way the car is already going
     * rather than with a U-turn. Throws [RouteException] with a reason fit to show, or
     * [IOException] when the server can't be reached.
     */
    suspend fun route(from: LatLon, heading: Double?, to: LatLon, avoidTolls: Boolean): Route
}

/**
 * Routes from the FOSSGIS Valhalla server, which is free for fair use over OpenStreetMap data.
 * There's no live traffic; timings are Valhalla's typical speeds for each road.
 */
class ValhallaRouter(private val endpoint: String = "https://valhalla1.openstreetmap.de/route") : Router {
    override val name = "Valhalla"

    override suspend fun route(from: LatLon, heading: Double?, to: LatLon, avoidTolls: Boolean): Route = withContext(Dispatchers.IO) {
        val request = JSONObject()
            .put("locations", JSONArray().put(location(from, heading)).put(location(to, null)))
            .put("costing", "auto")
            .put("costing_options", JSONObject().put("auto", JSONObject().put("use_tolls", if (avoidTolls) 0.0 else 0.5)))
            .put("units", "kilometers")
            .put("directions_type", "maneuvers")
        val (code, body) = Http.get("$endpoint?json=${URLEncoder.encode(request.toString(), "UTF-8")}")
        if (code == 429 || code >= 500) throw RouteException("The route server is busy. Try again in a moment")
        ValhallaParser.parse(body)
    }

    private fun location(position: LatLon, heading: Double?) = JSONObject()
        .put("lat", position.lat)
        .put("lon", position.lon)
        .apply { if (heading != null) put("heading", heading.toInt()).put("heading_tolerance", 60) }
}

/** Finds places by name or address for Navigation's search and for CarPlay's destination. */
interface PlaceFinder {
    /** Who the results come from, when their terms ask for a credit beside them; null for none. */
    val credit: String?

    /**
     * Places matching [query], nearby ones first when [near] is known. With [withinMetres] too, only
     * places about that close, so namesakes far away can't crowd out the one nearby. Throws
     * [IOException] when the service can't be reached and [PlaceSearchException] when it refuses.
     */
    suspend fun search(query: String, near: LatLon?, withinMetres: Double? = null): List<Place>
}

/** The search service turned the request down, e.g. over its API key; [message] is fit to show. */
class PlaceSearchException(message: String) : Exception(message)

/** Place search from Komoot's public Photon server, free for fair use over OpenStreetMap data. */
class PhotonSearch(private val endpoint: String = "https://photon.komoot.io/api/") : PlaceFinder {
    // The map credits OpenStreetMap already.
    override val credit: String? = null

    override suspend fun search(query: String, near: LatLon?, withinMetres: Double?): List<Place> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(endpoint).append("?q=").append(URLEncoder.encode(query, "UTF-8")).append("&limit=8&lang=en")
            // A strong pull towards where the car is; far-off places still show when nothing's closer.
            if (near != null) append("&lat=${near.lat}&lon=${near.lon}&zoom=10&location_bias_scale=0.05")
            if (near != null && withinMetres != null) {
                val lat = withinMetres / METRES_PER_DEGREE
                val lon = lat / cos(Math.toRadians(near.lat)).coerceAtLeast(0.01)
                append("&bbox=${near.lon - lon},${near.lat - lat},${near.lon + lon},${near.lat + lat}")
            }
        }
        val (code, body) = Http.get(url)
        if (code != HttpURLConnection.HTTP_OK) throw IOException("Search failed with HTTP $code")
        PhotonParser.parse(body)
    }

    private companion object {
        const val METRES_PER_DEGREE = 111_200.0
    }
}

/**
 * Place search from Google's Places API (New) Text Search, with the driver's own API key. Knows far
 * more shops and offices than OpenStreetMap does. [caller] lets a key restricted to Revv work.
 */
class GooglePlacesSearch(
    private val apiKey: String,
    private val caller: AndroidCaller?,
    private val endpoint: String = "https://places.googleapis.com/v1/places:searchText",
) : PlaceFinder {
    // Google's terms ask for its credit wherever Places results show without a Google map.
    override val credit: String = "GOOGLE MAPS"

    override suspend fun search(query: String, near: LatLon?, withinMetres: Double?): List<Place> = withContext(Dispatchers.IO) {
        val request = JSONObject().put("textQuery", query).put("pageSize", 8).put("languageCode", "en")
        if (near != null && withinMetres != null) {
            // Text search restricts to a rectangle only.
            val lat = withinMetres / METRES_PER_DEGREE
            val lon = lat / cos(Math.toRadians(near.lat)).coerceAtLeast(0.01)
            request.put(
                "locationRestriction",
                JSONObject().put(
                    "rectangle",
                    JSONObject().put("low", point(near.lat - lat, near.lon - lon)).put("high", point(near.lat + lat, near.lon + lon)),
                ),
            )
        } else if (near != null) {
            request.put("locationBias", JSONObject().put("circle", JSONObject().put("center", point(near.lat, near.lon)).put("radius", BIAS_METRES)))
        }
        val headers = buildMap {
            put("X-Goog-Api-Key", apiKey)
            // Only what a result shows, which keeps each search in the cheaper tier.
            put("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
            caller?.let {
                put("X-Android-Package", it.packageName)
                it.certSha1?.let { sha1 -> put("X-Android-Cert", sha1) }
            }
        }
        val (code, body) = Http.post(endpoint, request.toString(), headers)
        if (code != HttpURLConnection.HTTP_OK) throw PlaceSearchException(GooglePlacesParser.error(body) ?: "Google search failed with HTTP $code")
        GooglePlacesParser.parse(body)
    }

    private fun point(lat: Double, lon: Double) = JSONObject().put("latitude", lat).put("longitude", lon)

    private companion object {
        const val METRES_PER_DEGREE = 111_200.0
        /** Google's largest bias circle. */
        const val BIAS_METRES = 50_000.0
    }
}

/** Google Maps Platform API keys, as the driver types, pastes or imports them. */
object GoogleApiKey {
    const val MAX_LENGTH = 100

    /** Google's keys today: "AIza" and 35 more letters, digits, - or _. */
    private val STANDARD = Regex("AIza[0-9A-Za-z_-]{35}")
    /** Anything else that could be a key, in case Google's format changes. */
    private val TOKEN = Regex("[0-9A-Za-z_-]{30,$MAX_LENGTH}")

    /** The key in [text], which may hold more (a line from a .env file, JSON, a note); null without one. */
    fun find(text: String): String? = (STANDARD.find(text) ?: TOKEN.find(text))?.value
}

/**
 * Routes from Google's Routes API with the driver's own key, traffic-aware, so Revv's map takes the
 * roads Google Maps would, e.g. Google Maps on CarPlay. [caller] lets a key restricted to Revv work.
 */
class GoogleRouter(
    private val apiKey: String,
    private val caller: AndroidCaller?,
    private val endpoint: String = "https://routes.googleapis.com/directions/v2:computeRoutes",
) : Router {
    override val name = "Google"

    override suspend fun route(from: LatLon, heading: Double?, to: LatLon, avoidTolls: Boolean): Route = withContext(Dispatchers.IO) {
        val origin = JSONObject().put("latLng", latLng(from)).apply { if (heading != null) put("heading", heading.toInt().mod(360)) }
        val request = JSONObject()
            .put("origin", JSONObject().put("location", origin))
            .put("destination", JSONObject().put("location", JSONObject().put("latLng", latLng(to))))
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("polylineQuality", "HIGH_QUALITY")
            .put("routeModifiers", JSONObject().put("avoidTolls", avoidTolls))
            .put("languageCode", "en")
            .put("units", "METRIC")
        val headers = buildMap {
            put("X-Goog-Api-Key", apiKey)
            // Only what Navigation uses, which keeps each route in the cheaper tier.
            put("X-Goog-FieldMask", FIELDS)
            caller?.let {
                put("X-Android-Package", it.packageName)
                it.certSha1?.let { sha1 -> put("X-Android-Cert", sha1) }
            }
        }
        val (code, body) = Http.post(endpoint, request.toString(), headers)
        if (code == 429 || code >= 500) throw RouteException("Google's route service is busy. Try again in a moment")
        if (code != HttpURLConnection.HTTP_OK) throw RouteException(GooglePlacesParser.error(body) ?: "Google couldn't work out a route (HTTP $code)")
        GoogleRoutesParser.parse(body)
    }

    private fun latLng(point: LatLon) = JSONObject().put("latitude", point.lat).put("longitude", point.lon)

    private companion object {
        const val FIELDS = "routes.duration,routes.polyline.encodedPolyline," +
            "routes.legs.steps.staticDuration,routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction"
    }
}

/** Revv as an Android app, for API keys restricted to it: its package and signing certificate. */
class AndroidCaller(val packageName: String, val certSha1: String?) {
    companion object {
        fun of(context: Context): AndroidCaller {
            val sha1 = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
                MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray()).joinToString("") { "%02X".format(it) }
            }.getOrNull()
            return AndroidCaller(context.packageName, sha1)
        }
    }
}

internal object Http {
    private const val USER_AGENT = "Revv (Android car launcher)"

    /** The status and body of a GET, error bodies included; throws [IOException] if unreachable. */
    fun get(url: String): Pair<Int, String> = request(url, body = null, headers = emptyMap())

    /** As [get], for a JSON POST. */
    fun post(url: String, body: String, headers: Map<String, String>): Pair<Int, String> = request(url, body, headers)

    private fun request(url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
