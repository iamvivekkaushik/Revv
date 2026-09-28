package com.vivekkaushik.revv.nav

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The last few places driven to, newest first, kept on the head unit only. */
class RecentPlaces(context: Context) {

    private val prefs = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)

    private val _places = MutableStateFlow(read())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    fun add(place: Place) {
        val kept = _places.value.filterNot { it.name == place.name && Geo.distance(it.position, place.position) < 100 }
        save((listOf(place) + kept).take(MAX))
    }

    fun clear() = save(emptyList())

    private fun save(places: List<Place>) {
        val json = JSONArray()
        places.forEach { place ->
            json.put(
                JSONObject()
                    .put("name", place.name)
                    .put("detail", place.detail)
                    .put("lat", place.position.lat)
                    .put("lon", place.position.lon),
            )
        }
        prefs.edit { putString(KEY, json.toString()) }
        _places.value = places
    }

    private fun read(): List<Place> = try {
        val json = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until json.length()).map { i ->
            val place = json.getJSONObject(i)
            Place(place.getString("name"), place.optString("detail"), LatLon(place.getDouble("lat"), place.getDouble("lon")))
        }
    } catch (e: JSONException) {
        emptyList()
    }

    private companion object {
        const val KEY = "recent_places"
        const val MAX = 6
    }
}
