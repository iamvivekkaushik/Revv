package com.vivekkaushik.revv.nav

import android.content.Context
import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException

enum class LocationAccess { NeedsPermission, Off, Searching, Found }

sealed interface TripStatus {
    /** Waiting for a route, or for a first position to route from. */
    data object Routing : TripStatus
    data class Failed(val reason: String) : TripStatus
    data object Guiding : TripStatus
    data object Arrived : TripStatus
}

data class Trip(
    val destination: Place,
    val status: TripStatus,
    val route: Route? = null,
    val guidance: Guidance? = null,
    /** What's left of the route, for drawing. */
    val remaining: List<LatLon> = emptyList(),
    val rerouting: Boolean = false,
    /** The demo drive's own trip, which ends as soon as real GPS takes over. */
    val demo: Boolean = false,
)

data class PlaceSearch(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
)

data class NavState(
    val access: LocationAccess = LocationAccess.Searching,
    /** Where to draw the car: on the route line while following one. */
    val fix: Fix? = null,
    /** [fix] is the demo car rather than GPS. */
    val demo: Boolean = false,
    val trip: Trip? = null,
    val search: PlaceSearch = PlaceSearch(),
)

/**
 * Where the car is and where it's going. Follows GPS when the head unit has it, or the demo drive's
 * pretend car until then, and guides along a route to the chosen place, rerouting when the car
 * leaves it. Runs on the main thread; only network calls leave it.
 */
class Navigator(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val device = DeviceLocation(appContext)
    private val router = ValhallaRouter()
    private val places = PhotonSearch()
    private val recentPlaces = RecentPlaces(appContext)
    private val demoTrip by lazy { DemoTrip.load(appContext) }

    private val _state = MutableStateFlow(NavState(access = currentAccess()))
    val state: StateFlow<NavState> = _state.asStateFlow()

    val recents: StateFlow<List<Place>> = recentPlaces.places

    var avoidTolls = false

    private var started = false
    private var listening = false
    private var realFixSeen = false
    private var demoAllowed = false
    private var demoCar: DemoDrive? = null
    private var onRoute: PathFix? = null
    private var offRouteFixes = 0
    private var lastRerouteAt = 0L
    private var lastBearing: Double? = null
    private var routeJob: Job? = null
    private var searchJob: Job? = null
    private var arrivalJob: Job? = null

    /** Revv is on screen: listen to GPS. */
    fun start() {
        started = true
        refreshAccess()
    }

    fun stop() {
        started = false
        listening = false
        device.stop()
    }

    /** Picks up permission or location switches changed outside Revv. */
    fun refreshAccess() {
        if (started && !listening && device.permitted) {
            device.start(::onDeviceFix, onChange = ::refreshAccess)
            listening = true
        }
        _state.update { it.copy(access = currentAccess()) }
    }

    /** Whether the demo car may drive: the demo drive is on and no OBD-II adapter is set up. */
    fun setDemoAllowed(allowed: Boolean) {
        demoAllowed = allowed
        if (!allowed) stopDemo()
    }

    /** Moves the demo car on by what the demo cluster covered: [seconds] at [speedKmh]. */
    fun advanceDemo(seconds: Double, speedKmh: Int) {
        if (!demoAllowed || realFixSeen) return
        val car = demoCar ?: startDemo()
        val speed = speedKmh / 3.6
        onFix(car.advance(speed * seconds, speed, System.currentTimeMillis()), demo = true)
    }

    fun navigateTo(place: Place) {
        recentPlaces.add(place)
        arrivalJob?.cancel()
        routeJob?.cancel()
        onRoute = null
        _state.update { it.copy(trip = Trip(place, TripStatus.Routing)) }
        // Without a position yet, the first fix asks for the route.
        _state.value.fix?.let { requestRoute(place, it, reroute = false) }
    }

    fun retryRoute() {
        _state.value.trip?.let { navigateTo(it.destination) }
    }

    fun endRoute() {
        routeJob?.cancel()
        arrivalJob?.cancel()
        onRoute = null
        offRouteFixes = 0
        _state.update { it.copy(trip = null) }
        // The demo car carries on to the end of the road it's on, then goes round again.
        demoCar?.loops = true
    }

    fun search(query: String) {
        searchJob?.cancel()
        val text = query.trim()
        if (text.length < MIN_QUERY) {
            _state.update { it.copy(search = PlaceSearch(query)) }
            return
        }
        _state.update { it.copy(search = it.search.copy(query = query, searching = true, error = null)) }
        searchJob = scope.launch {
            // Wait for a pause in typing rather than asking the server for every letter.
            delay(TYPING_PAUSE_MILLIS)
            val result = try {
                PlaceSearch(query, places.search(text, near = _state.value.fix?.position))
            } catch (e: IOException) {
                PlaceSearch(query, error = NO_CONNECTION)
            } catch (e: JSONException) {
                PlaceSearch(query, error = "Search sent back something unreadable")
            }
            _state.update { it.copy(search = result) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = PlaceSearch()) }
    }

    fun clearRecents() = recentPlaces.clear()

    private fun currentAccess() = when {
        !device.permitted -> LocationAccess.NeedsPermission
        !device.enabled -> LocationAccess.Off
        realFixSeen -> LocationAccess.Found
        else -> LocationAccess.Searching
    }

    private fun onDeviceFix(fix: Fix) {
        if (!realFixSeen) {
            // Real GPS from now on; never mix in the pretend car again.
            realFixSeen = true
            stopDemo()
            _state.update { it.copy(access = currentAccess()) }
        }
        onFix(fix, demo = false)
    }

    private fun onFix(raw: Fix, demo: Boolean) {
        var shown = raw
        var trip = _state.value.trip
        if (trip != null) {
            when (trip.status) {
                TripStatus.Routing -> if (routeJob?.isActive != true) requestRoute(trip.destination, raw, reroute = false)
                TripStatus.Guiding, TripStatus.Arrived -> {
                    val route = trip.route!!
                    val fix = locate(route, raw.position)
                    onRoute = fix
                    val guidance = Guidance.at(route, fix)
                    if (fix.offset <= SNAP_METRES) {
                        shown = raw.copy(position = fix.position, bearing = fix.bearing)
                        offRouteFixes = 0
                    } else {
                        offRouteFixes++
                    }
                    trip = trip.copy(guidance = guidance, remaining = route.path.remainingFrom(fix))
                    if (!demo && shouldReroute(trip, raw)) {
                        trip = trip.copy(rerouting = true)
                        requestRoute(trip.destination, raw, reroute = true)
                    }
                    if (guidance.arrived && trip.status == TripStatus.Guiding) {
                        trip = trip.copy(status = TripStatus.Arrived)
                        endTripSoon()
                    }
                }
                is TripStatus.Failed -> Unit
            }
        }
        // Stopped or crawling, GPS bearings wander; keep the last good one so the map doesn't spin.
        val bearing = when {
            shown !== raw -> shown.bearing
            raw.bearing != null && (demo || (raw.speedMps ?: 0.0) >= MIN_BEARING_SPEED) -> raw.bearing
            else -> lastBearing
        }
        lastBearing = bearing
        val placed = shown.copy(bearing = bearing)
        _state.update { it.copy(fix = placed, demo = demo, trip = trip) }
    }

    /** Where the car is on [route], looking near where it last was, then anywhere along it. */
    private fun locate(route: Route, position: LatLon): PathFix {
        val near = onRoute
        val local = route.path.locate(position, near)
        if (near == null || local.offset <= SNAP_METRES) return local
        val anywhere = route.path.locate(position)
        return if (anywhere.offset < local.offset) anywhere else local
    }

    private fun shouldReroute(trip: Trip, fix: Fix): Boolean {
        if (offRouteFixes < OFF_ROUTE_FIXES || trip.rerouting || trip.status != TripStatus.Guiding) return false
        // A poor fix wanders off the road by itself.
        if ((fix.accuracyMetres ?: 0.0) > MAX_REROUTE_ACCURACY) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastRerouteAt < REROUTE_GAP_MILLIS) return false
        lastRerouteAt = now
        return true
    }

    private fun requestRoute(destination: Place, from: Fix, reroute: Boolean) {
        routeJob?.cancel()
        val heading = from.bearing?.takeIf { (from.speedMps ?: 0.0) >= MIN_HEADING_SPEED }
        routeJob = scope.launch {
            val route = try {
                router.route(from.position, heading, destination.position, avoidTolls)
            } catch (e: RouteException) {
                routeFailed(destination, e.message ?: "No route found", reroute)
                return@launch
            } catch (e: IOException) {
                routeFailed(destination, NO_CONNECTION, reroute)
                return@launch
            }
            // The driver may have ended the trip or picked somewhere else meanwhile.
            if (_state.value.trip?.destination != destination) return@launch
            onRoute = null
            offRouteFixes = 0
            demoCar?.follow(route.path, loops = false)
            _state.update { it.copy(trip = Trip(destination, TripStatus.Guiding, route, remaining = route.path.points)) }
            // Guidance straight away rather than at the next fix.
            val state = _state.value
            val car = demoCar
            val fix = if (state.demo && car != null) car.fixAt(state.fix?.speedMps ?: 0.0, System.currentTimeMillis()) else state.fix
            fix?.let { onFix(it, demo = state.demo && car != null) }
        }
    }

    private fun routeFailed(destination: Place, reason: String, reroute: Boolean) {
        _state.update { state ->
            val trip = state.trip?.takeIf { it.destination == destination } ?: return@update state
            // A failed reroute keeps following the old route; the next check tries again.
            state.copy(trip = if (reroute) trip.copy(rerouting = false) else trip.copy(status = TripStatus.Failed(reason)))
        }
    }

    private fun endTripSoon() {
        arrivalJob?.cancel()
        arrivalJob = scope.launch {
            delay(ARRIVED_MILLIS)
            if (_state.value.trip?.status != TripStatus.Arrived) return@launch
            onRoute = null
            _state.update { it.copy(trip = null) }
            // The demo goes round again: back to the start and off to the same place.
            if (demoCar != null) startDemoTrip()
        }
    }

    private fun startDemo(): DemoDrive {
        val car = DemoDrive(demoTrip.route.path, loops = true)
        demoCar = car
        if (_state.value.trip == null) startDemoTrip()
        return car
    }

    private fun startDemoTrip() {
        val demo = demoTrip
        demoCar?.follow(demo.route.path, loops = false)
        onRoute = null
        _state.update { it.copy(trip = Trip(demo.destination, TripStatus.Guiding, demo.route, remaining = demo.route.path.points, demo = true)) }
    }

    private fun stopDemo() {
        if (demoCar == null) return
        demoCar = null
        arrivalJob?.cancel()
        _state.update { state ->
            state.copy(
                fix = if (state.demo) null else state.fix,
                demo = false,
                trip = state.trip?.takeUnless { it.demo },
            )
        }
    }

    private companion object {
        /** A fix this close to the route is drawn on it. */
        const val SNAP_METRES = 25.0
        const val OFF_ROUTE_FIXES = 3
        const val REROUTE_GAP_MILLIS = 15_000L
        const val MAX_REROUTE_ACCURACY = 50.0
        const val MIN_BEARING_SPEED = 1.5
        const val MIN_HEADING_SPEED = 3.0
        const val ARRIVED_MILLIS = 12_000L
        const val TYPING_PAUSE_MILLIS = 400L
        const val MIN_QUERY = 2
        const val NO_CONNECTION = "No internet connection"
    }
}
