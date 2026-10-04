package com.vivekkaushik.revv.nav

import android.util.Log
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException

/**
 * Routes Revv's map to where CarPlay is guiding. CarPlay sends only the destination's name, so
 * this looks the name up near the car with Navigation's place search, and takes a match only when
 * its distance fits what CarPlay says is left of its route. When several fit (a chain's branches),
 * it plans a route to each and takes the one as long as CarPlay's. Without a match, Revv shows
 * CarPlay's own guidance instead. Revv's route ends with CarPlay's, unless the driver has picked somewhere else
 * meanwhile. Main thread.
 */
class CarPlayRoute(private val navigator: Navigator, private val scope: CoroutineScope) {
    /** CarPlay's destination as last looked up, found or not. */
    private var destination: String? = null
    /** Where Revv routed for it. */
    private var followed: Place? = null
    private var lookup: Job? = null
    private var unnamedLogged = false

    /** Experimental, so off until the driver turns it on (Settings › CarPlay › Follow CarPlay's route). */
    var enabled = false
        set(value) {
            field = value
            // Off, Revv's map ignores CarPlay: the route it took from CarPlay ends too.
            if (!value) end()
        }

    /** CarPlay's destination by name and the metres left on its route, or nulls without a route. */
    fun update(name: String?, routeMeters: Long?) {
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted == null) {
            if (routeMeters != null && !unnamedLogged) Log.i(TAG, "CarPlay guides a route without naming its destination")
            unnamedLogged = routeMeters != null
            end()
            return
        }
        if (!enabled || wanted == destination) return
        // The distance tells the right match from the namesakes; wait for it.
        val left = routeMeters?.takeIf { it > 0 } ?: return
        end()
        destination = wanted
        lookup = scope.launch { find(wanted, left.toDouble()) }
    }

    private suspend fun find(name: String, routeMeters: Double) {
        val nav = navigator.state.value
        // The demo car isn't where the iPhone is.
        val from = nav.fix?.takeUnless { nav.demo }?.position
        val candidates = if (from == null) null else try {
            // Only as far as CarPlay's route could reach.
            navigator.placeFinder.search(name, near = from, withinMetres = routeMeters + SLACK_METRES)
        } catch (e: PlaceSearchException) {
            // E.g. the key's API isn't enabled yet; CarPlay's own guidance shows meanwhile.
            Log.w(TAG, "Looking CarPlay's destination up was refused: ${e.message}")
            delay(REFUSED_RETRY_MILLIS)
            if (destination == name) destination = null
            return
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }
        if (from == null || candidates == null) {
            Log.i(TAG, if (from == null) "No position to look CarPlay's destination up from yet" else "Looking CarPlay's destination up failed")
            // No position or no connection yet: let a later update try again.
            delay(RETRY_MILLIS)
            if (destination == name) destination = null
            return
        }
        val route = "a %.1f km route".format(routeMeters / 1000)
        val fitting = fits(candidates, from, routeMeters)
        Log.i(
            TAG,
            when {
                candidates.isEmpty() -> "No place of CarPlay's destination name within reach of $route"
                else -> "CarPlay's destination: matches at " +
                    candidates.joinToString { "%.1f km".format(Geo.distance(from, it.position) / 1000) } +
                    " for $route; ${fitting.size} fit"
            },
        )
        when (fitting.size) {
            0 -> return
            1 -> {
                followed = fitting[0]
                navigator.navigateTo(fitting[0], remember = false)
            }
            else -> {
                // Several could be it: take the one a road route as long as CarPlay's leads to.
                val planned = coroutineScope {
                    fitting.take(MAX_PLANNED).map { place ->
                        async { runCatching { navigator.planRoute(place.position) }.getOrNull()?.let { place to it } }
                    }.awaitAll().filterNotNull()
                }
                val best = byRouteLength(planned.map { (place, plan) -> place to plan.metres }, routeMeters)
                Log.i(
                    TAG,
                    "Route lengths " + planned.joinToString { (_, plan) -> "%.1f km".format(plan.metres / 1000) } +
                        if (best == null) "; none is CarPlay's" else "; following the closest",
                )
                // Planning may have outlasted CarPlay's route or its destination.
                currentCoroutineContext().ensureActive()
                val (place, plan) = planned.firstOrNull { it.first == best } ?: return
                followed = place
                navigator.follow(place, plan, remember = false)
            }
        }
    }

    /** Place search changed: a destination it didn't find is looked up again on CarPlay's next update. */
    fun searchChanged() {
        if (followed != null) return
        lookup?.cancel()
        lookup = null
        destination = null
    }

    /** CarPlay's route is over: so is Revv's, if it still goes where CarPlay's went. */
    private fun end() {
        val place = followed
        forget()
        if (place != null && navigator.state.value.trip?.destination == place) navigator.endRoute()
    }

    private fun forget() {
        lookup?.cancel()
        lookup = null
        destination = null
        followed = null
    }

    companion object {
        private const val TAG = "RevvCarPlayRoute"
        private const val RETRY_MILLIS = 20_000L
        /** A refused search is tried again this much later, in case the key was fixed meanwhile. */
        private const val REFUSED_RETRY_MILLIS = 60_000L
        /** The iPhone and the head unit place the car apart, and a place's point is not its entrance. */
        private const val SLACK_METRES = 500.0
        /** A road route is rarely more than this many times the straight line. */
        private const val MAX_DETOUR = 3.0
        /** About how much longer than the straight line a road route usually is. */
        private const val TYPICAL_DETOUR = 1.3

        /** At most this many fitting places get a route planned to compare, each a request to the router. */
        private const val MAX_PLANNED = 3
        /** How far a planned route may differ from CarPlay's and still be taken for it: a share, plus metres. */
        private const val LENGTH_TOLERANCE = 0.25
        private const val LENGTH_SLACK_METRES = 1_000.0

        /**
         * The places among [candidates] that a road route of [routeMeters] from [from] can lead to:
         * never nearer by road than in a straight line, nor [MAX_DETOUR] times further. Those whose
         * detour is most usual come first.
         */
        fun fits(candidates: List<Place>, from: LatLon, routeMeters: Double): List<Place> =
            candidates
                .map { it to Geo.distance(from, it.position) }
                .filter { (_, straight) -> straight <= routeMeters + SLACK_METRES && straight * MAX_DETOUR + SLACK_METRES >= routeMeters }
                .sortedBy { (_, straight) -> abs(ln((routeMeters + 1) / (straight + 1)) - ln(TYPICAL_DETOUR)) }
                .map { it.first }

        /** The likeliest of [fits], or null when none fits. */
        fun pick(candidates: List<Place>, from: LatLon, routeMeters: Double): Place? = fits(candidates, from, routeMeters).firstOrNull()

        /**
         * Of places with the length of a route planned to each ([planned]), the one closest to
         * CarPlay's [routeMeters]; null when none is within [LENGTH_TOLERANCE] of it.
         */
        fun byRouteLength(planned: List<Pair<Place, Double>>, routeMeters: Double): Place? =
            planned
                .filter { (_, metres) -> abs(metres - routeMeters) <= routeMeters * LENGTH_TOLERANCE + LENGTH_SLACK_METRES }
                .minByOrNull { (_, metres) -> abs(metres - routeMeters) }
                ?.first
    }
}
