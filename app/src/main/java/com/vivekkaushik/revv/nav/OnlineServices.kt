package com.vivekkaushik.revv.nav

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes from the FOSSGIS Valhalla server, which is free for fair use over OpenStreetMap data.
 * There's no live traffic; timings are Valhalla's typical speeds for each road.
 */
class ValhallaRouter(private val endpoint: String = "https://valhalla1.openstreetmap.de/route") {

    /**
     * [heading] (degrees, when moving) makes the route start off the way the car is already going
     * rather than with a U-turn. Throws [RouteException] with a reason fit to show, or
     * [IOException] when the server can't be reached.
     */
    suspend fun route(from: LatLon, heading: Double?, to: LatLon, avoidTolls: Boolean): Route = withContext(Dispatchers.IO) {
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

/** Place search from Komoot's public Photon server, free for fair use over OpenStreetMap data. */
class PhotonSearch(private val endpoint: String = "https://photon.komoot.io/api/") {

    /** Places matching [query], nearby ones first when [near] is known. */
    suspend fun search(query: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(endpoint).append("?q=").append(URLEncoder.encode(query, "UTF-8")).append("&limit=8&lang=en")
            // A strong pull towards where the car is; far-off places still show when nothing's closer.
            if (near != null) append("&lat=${near.lat}&lon=${near.lon}&zoom=10&location_bias_scale=0.05")
        }
        val (code, body) = Http.get(url)
        if (code != HttpURLConnection.HTTP_OK) throw IOException("Search failed with HTTP $code")
        PhotonParser.parse(body)
    }
}

internal object Http {
    private const val USER_AGENT = "Revv (Android car launcher)"

    /** The status and body of a GET, error bodies included; throws [IOException] if unreachable. */
    fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
