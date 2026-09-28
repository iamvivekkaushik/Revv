package com.vivekkaushik.revv.nav

import android.content.Context
import org.json.JSONObject

/**
 * The demo drive's pretend car: it follows a path at whatever speed the demo cluster shows, so the
 * map, the gauges and the guidance all tell the same story.
 */
class DemoDrive(path: Path, var loops: Boolean) {

    var path: Path = path
        private set

    /** Metres driven along [path]. */
    var travelled = 0.0
        private set

    /** Drives [path] from its start; [loops] says whether to go round again at the end. */
    fun follow(path: Path, loops: Boolean) {
        this.path = path
        this.loops = loops
        travelled = 0.0
    }

    fun advance(metres: Double, speedMps: Double, nowMillis: Long): Fix {
        travelled += metres.coerceAtLeast(0.0)
        if (travelled > path.length) travelled = if (loops) travelled % path.length else path.length
        return fixAt(speedMps, nowMillis)
    }

    fun fixAt(speedMps: Double, nowMillis: Long): Fix {
        val at = path.pointAt(travelled)
        return Fix(at.position, at.bearing, speedMps, accuracyMetres = 5.0, timeMillis = nowMillis)
    }
}

/** The demo's trip through Gurugram, a real route stored with the app so it works offline. */
class DemoTrip(val destination: Place, val route: Route) {
    companion object {
        fun load(context: Context): DemoTrip {
            val json = context.assets.open("map/demo_route.json").bufferedReader().use { it.readText() }
            val destination = JSONObject(json).getJSONObject("destination")
            return DemoTrip(
                Place(
                    destination.getString("name"),
                    destination.getString("detail"),
                    LatLon(destination.getDouble("lat"), destination.getDouble("lon")),
                ),
                ValhallaParser.parse(json),
            )
        }
    }
}
