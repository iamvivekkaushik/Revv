package com.vivekkaushik.revv.ui.hmi

import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vivekkaushik.revv.nav.Guidance
import com.vivekkaushik.revv.nav.LocationAccess
import com.vivekkaushik.revv.nav.NavState
import com.vivekkaushik.revv.nav.Trip
import com.vivekkaushik.revv.nav.TripStatus
import com.vivekkaushik.revv.nav.Turn
import com.vivekkaushik.revv.settings.SettingsStore
import com.vivekkaushik.revv.nav.ProjectionGuidance
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val HOME_ZOOM = 15.5
private const val SCREEN_ZOOM = 16.0
private const val HEADING_UP_TILT = 45.0

/**
 * The map panel on the right of the home screen: the car, the route and the next turn. Without a
 * route of its own, it shows the one the phone's projection is guiding, if any ([projection]).
 */
@Composable
fun NavPanel(
    navigation: StateFlow<NavState>,
    covered: Boolean,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
    modifier: Modifier = Modifier,
    projection: ProjectionGuidance? = null,
) {
    val nav by navigation.collectAsStateWithLifecycle()
    val trip = nav.trip
    // Compact (display sizes above 130%): a smaller panel inside the page's margins, clear of the dock,
    // and with no trip just the map: no "Where to?" over it, though a tap still opens search.
    val compact = LocalCompact.current
    val header = !compact || trip != null || projection != null
    Box(modifier.background(Hmi.MapBg).clipToBounds()) {
        RevvMap(
            fix = nav.fix,
            route = trip?.remaining.orEmpty(),
            destination = trip?.destination?.position,
            camera = MapCamera.HeadingUp,
            zoom = HOME_ZOOM,
            // Nobody sees it while an app covers the home screen.
            visible = !covered,
            headingUpShift = 0.3,
            modifier = Modifier.fillMaxSize(),
        )
        if (header) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (compact) 170.dp else 230.dp)
                    .background(Brush.verticalGradient(0f to Hmi.MapBg, 0.45f to Hmi.MapBg, 1f to Color.Transparent)),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (compact) 150.dp else 300.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Hmi.MapBg, 1f to Hmi.MapBg)),
        )
        if (header) {
            TripHeader(
                nav,
                projection,
                distanceSize = if (compact) 48.sp else 64.sp,
                controls = false,
                actions = actions,
                onSearch = {},
                modifier = if (compact) Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp) else Modifier.padding(start = 36.dp, end = 36.dp, top = 40.dp),
                turnIconSize = if (compact) 84.dp else 110.dp,
            )
        }
        tripLeft(trip, projection, timeFormat)?.let { left ->
            TripSummary(
                left,
                withUnits = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = if (compact) 20.dp else 36.dp, end = if (compact) 20.dp else 36.dp, bottom = if (compact) 20.dp else 130.dp)
                    .fillMaxWidth()
                    .border(1.dp, Hmi.LineStrong)
                    .background(Hmi.MapBg.copy(alpha = 0.85f))
                    .padding(horizontal = if (compact) 20.dp else 28.dp, vertical = if (compact) 14.dp else 20.dp),
            )
        }
        MapAttribution(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp))
        // Anywhere on the panel opens the full Navigation screen; with no trip, "Where to?" goes straight to search.
        Pressable(
            onClick = { if (trip == null && projection == null) actions.openMapsSearch() else actions.open(HmiApp.Maps) },
            modifier = Modifier.fillMaxSize(),
            pressedBackground = Hmi.Cyan.copy(alpha = 0.04f),
            border = null,
        ) {}
        if (!compact) Box(Modifier.fillMaxHeight().width(1.dp).background(Hmi.Line))
    }
}

/** The full-screen Navigation app: the map to pan and zoom, search, and the route. */
@Composable
fun MapsScreen(
    state: HmiUiState,
    navigation: StateFlow<NavState>,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
) {
    val nav by navigation.collectAsStateWithLifecycle()
    val trip = nav.trip
    val projection = rememberProjectionGuidance(state)
    var followMode by rememberSaveable { mutableStateOf(MapCamera.NorthUp) }
    var panned by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableDoubleStateOf(SCREEN_ZOOM) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val bearing = remember { mutableFloatStateOf(0f) }
    // Compact (display sizes above 130%) keeps the notices clear of the trip card and has fewer buttons.
    val compact = LocalCompact.current
    LaunchedEffect(state.screen.searchRequested) {
        if (state.screen.searchRequested) {
            searching = true
            actions.mapsSearchShown()
        }
    }
    BackHandler(enabled = searching) {
        searching = false
        actions.clearPlaceSearch()
    }
    fun follow(mode: MapCamera) {
        followMode = mode
        panned = false
    }

    Box(Modifier.fillMaxSize().border(1.dp, Hmi.Line).background(Hmi.MapBg).clipToBounds()) {
        RevvMap(
            fix = nav.fix,
            route = trip?.remaining.orEmpty(),
            destination = trip?.destination?.position,
            camera = if (panned) MapCamera.Free else followMode,
            zoom = zoom,
            interactive = true,
            // The search panel covers it completely.
            visible = !searching,
            headingUpTilt = HEADING_UP_TILT,
            headingUpShift = 0.4,
            onCameraFreed = { panned = true },
            onZoomChanged = { zoom = it },
            onBearingChanged = { bearing.floatValue = it },
            modifier = Modifier.fillMaxSize(),
        )
        TripHeader(
            nav,
            projection,
            distanceSize = 56.sp,
            controls = true,
            actions = actions,
            onSearch = { searching = true },
            modifier = Modifier
                .padding(32.dp)
                .width(520.dp)
                .border(1.dp, Hmi.LineStrong)
                .background(Hmi.MapBg.copy(alpha = 0.9f))
                .padding(28.dp),
        )
        tripLeft(trip, projection, timeFormat)?.let { left ->
            TripSummary(
                left,
                withUnits = false,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp)
                    .width(520.dp)
                    .border(1.dp, Hmi.LineStrong)
                    .background(Hmi.MapBg.copy(alpha = 0.9f))
                    .padding(horizontal = 28.dp, vertical = 22.dp),
            )
        }
        Column(Modifier.align(Alignment.TopEnd).padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapButton(onClick = { zoom = (zoom + 1).coerceAtMost(19.0) }, repeatEveryMillis = 350) { HText("+", size = 24.sp, spacing = 1.sp) }
            MapButton(onClick = { zoom = (zoom - 1).coerceAtLeast(4.0) }, repeatEveryMillis = 350) { HText("−", size = 24.sp, spacing = 1.sp) }
            // Points north as the map turns; a tap puts north back at the top.
            MapButton(onClick = { follow(MapCamera.NorthUp) }) {
                HText("N", Modifier.graphicsLayer { rotationZ = -bearing.floatValue }, size = 14.sp, color = Hmi.Cyan, spacing = 1.sp)
            }
        }
        // Compact: between the trip card and the zoom buttons.
        if (nav.demo) {
            DemoNotice(
                nav.access,
                actions,
                if (compact) Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 104.dp) else Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )
        }
        if (nav.fix == null) {
            LocationNotice(
                nav.access,
                actions,
                if (compact) Modifier.align(Alignment.CenterEnd).padding(end = 104.dp).width(440.dp) else Modifier.align(Alignment.Center).width(560.dp),
            )
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (panned) AccentButton("RECENTER", { panned = false }, Modifier.height(56.dp))
            if (compact) {
                // One button for both modes; a second tap, like the N button, goes back to north-up.
                val headingUp = !panned && followMode == MapCamera.HeadingUp
                ModeButton("HEADING-UP", selected = headingUp) { follow(if (headingUp) MapCamera.NorthUp else MapCamera.HeadingUp) }
            } else {
                // Not in the design: hands off to the head unit's own navigation app, e.g. for traffic.
                GhostButton("OPEN MAPS APP", actions::openNavigationApp, Modifier.height(56.dp))
                ModeButton("2D NORTH-UP", selected = !panned && followMode == MapCamera.NorthUp) { follow(MapCamera.NorthUp) }
                ModeButton("HEADING-UP", selected = !panned && followMode == MapCamera.HeadingUp) { follow(MapCamera.HeadingUp) }
            }
            if (trip != null) GhostButton("END ROUTE", actions::endRoute, Modifier.height(56.dp))
        }
        MapAttribution(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 12.dp))
        if (searching) {
            PlaceSearchPanel(
                recents = state.recentPlaces,
                search = nav.search,
                near = nav.fix?.position,
                actions = actions,
                onPick = { place ->
                    searching = false
                    actions.clearPlaceSearch()
                    actions.navigateTo(place)
                    follow(followMode)
                },
                onClose = {
                    searching = false
                    actions.clearPlaceSearch()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The top card: the next turn while guiding, or the trip's state, or the phone's next turn while
 * its projection guides a route Revv has none of its own for, or an invitation to search.
 * [controls] adds the card's own buttons; on the home screen the whole panel is one button instead.
 */
@Composable
private fun TripHeader(
    nav: NavState,
    projection: ProjectionGuidance?,
    distanceSize: TextUnit,
    controls: Boolean,
    actions: HmiActions,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    turnIconSize: Dp = 110.dp,
) {
    val trip = nav.trip
    when {
        trip == null && projection != null -> ProjectionTurn(projection, distanceSize, turnIconSize, modifier)
        trip == null -> WhereTo(nav, controls, onSearch, modifier)
        trip.status == TripStatus.Guiding && trip.guidance != null -> NextTurn(trip, trip.guidance, distanceSize, turnIconSize, modifier)
        else -> TripStatusCard(trip, waitingForFix = nav.fix == null, controls, actions, modifier)
    }
}

@Composable
private fun WhereTo(nav: NavState, controls: Boolean, onSearch: () -> Unit, modifier: Modifier) {
    val content = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(80.dp).border(1.dp, Hmi.Cyan), contentAlignment = Alignment.Center) {
                PathIcon(HmiIcons.SEARCH, 34.dp, Hmi.Cyan, strokeWidth = 1.8f)
            }
            Column {
                Caption("NAVIGATION")
                HText("WHERE TO?", Modifier.padding(top = 6.dp), size = 30.sp, family = Hmi.Display)
                HText(
                    if (controls) "Search a place, address or area" else "Tap to search a place",
                    Modifier.padding(top = 6.dp),
                    size = 16.sp,
                    color = Hmi.Muted,
                )
                // On the home screen, say why there's no car on the map.
                if (!controls && nav.fix == null) Caption(accessCaption(nav.access), Modifier.padding(top = 10.dp), size = 13.sp)
            }
        }
    }
    if (controls) {
        Pressable(onSearch, modifier, border = null, contentAlignment = Alignment.CenterStart) { content() }
    } else {
        Box(modifier) { content() }
    }
}

@Composable
private fun NextTurn(trip: Trip, guidance: Guidance, distanceSize: TextUnit, iconSize: Dp, modifier: Modifier) {
    val next = guidance.next
    TurnCard(
        caption = if (trip.rerouting) "REROUTING…" else NavFormat.caption(next.turn, next.roundaboutExit),
        turn = next.turn,
        metres = guidance.metresToNext,
        road = next.road ?: trip.destination.name.takeIf { next.turn == Turn.Arrive } ?: "",
        distanceSize = distanceSize,
        iconSize = iconSize,
        modifier = modifier,
    )
}

/**
 * The next turn the phone's projection (CarPlay or Android Auto) gives, while it guides a route
 * Revv isn't following itself (the place wasn't found, or following is off). Before the phone
 * names a turn, just where it goes.
 */
@Composable
private fun ProjectionTurn(guidance: ProjectionGuidance, distanceSize: TextUnit, iconSize: Dp, modifier: Modifier) {
    val turn = guidance.turn
    if (turn == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption(guidance.source + " · ROUTE TO", maxLines = 1)
            HText(guidance.destination.ifEmpty { "Your destination" }, size = 28.sp, family = Hmi.Display, maxLines = 1)
        }
        return
    }
    TurnCard(
        caption = guidance.source + " · " + NavFormat.caption(turn, guidance.roundaboutExit).removePrefix("NEXT TURN · ").removePrefix("NEXT · "),
        turn = turn,
        metres = guidance.maneuverMeters.toDouble(),
        road = guidance.road.ifEmpty { guidance.destination.takeIf { turn == Turn.Arrive }.orEmpty() },
        distanceSize = distanceSize,
        iconSize = iconSize,
        modifier = modifier,
    )
}

@Composable
private fun TurnCard(caption: String, turn: Turn, metres: Double, road: String, distanceSize: TextUnit, iconSize: Dp, modifier: Modifier) {
    val (distance, unit) = NavFormat.distance(metres)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(iconSize).border(1.dp, Hmi.Cyan), contentAlignment = Alignment.Center) {
            PathIcon(
                NavFormat.icon(turn),
                iconSize * 64 / 110,
                Hmi.Cyan,
                // Left-hand turns are the right-hand arrows mirrored.
                modifier = Modifier.graphicsLayer { scaleX = if (turn.left) -1f else 1f },
                strokeWidth = 5f,
                viewport = 64f,
            )
        }
        Column {
            Caption(caption, maxLines = 1)
            Row(Modifier.padding(top = 6.dp)) {
                HText(distance, Modifier.alignByBaseline(), size = distanceSize, family = Hmi.Display, spacing = (-2).sp)
                HText(unit, Modifier.alignByBaseline().padding(start = 8.dp), size = 20.sp, color = Hmi.Muted)
            }
            HText(road, Modifier.padding(top = 8.dp), size = 22.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TripStatusCard(trip: Trip, waitingForFix: Boolean, controls: Boolean, actions: HmiActions, modifier: Modifier) {
    val status = trip.status
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            TripStatus.Arrived -> Caption("ARRIVED", color = Hmi.Cyan)
            is TripStatus.Failed -> Caption("NO ROUTE", color = Hmi.Red)
            else -> Caption("ROUTING TO")
        }
        HText(trip.destination.name, size = 28.sp, family = Hmi.Display, maxLines = 1)
        val detail = when (status) {
            TripStatus.Routing -> if (waitingForFix) "Waiting for your position…" else "Finding a route…"
            is TripStatus.Failed -> status.reason
            else -> trip.destination.detail
        }
        if (detail.isNotEmpty()) HText(detail, size = 16.sp, color = Hmi.Muted, maxLines = 2)
        if (controls && status is TripStatus.Failed) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("RETRY", actions::retryRoute, Modifier.height(52.dp))
                GhostButton("CANCEL", actions::endRoute, Modifier.height(52.dp))
            }
        }
    }
}

/** What's left of the trip being guided: the arrival time, already formatted, and time and distance to go. */
private class TripLeft(val eta: String, val seconds: Double, val metres: Double)

/** What's left of Revv's own route while guiding, else of the phone's; null when neither says. */
private fun tripLeft(trip: Trip?, projection: ProjectionGuidance?, timeFormat: DateTimeFormatter): TripLeft? {
    if (trip != null) {
        val guidance = trip.guidance?.takeIf { trip.status == TripStatus.Guiding } ?: return null
        val eta = LocalDateTime.now().plusSeconds(guidance.remainingSeconds.toLong())
        return TripLeft(eta.format(timeFormat), guidance.remainingSeconds, guidance.remainingMetres)
    }
    val seconds = projection?.remainingSeconds ?: return null
    val metres = projection.routeMeters ?: return null
    val eta = projection.arrivalEpochSeconds?.let { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault()) }
        ?: LocalDateTime.now().plusSeconds(seconds)
    return TripLeft(eta.format(timeFormat), seconds.toDouble(), metres.toDouble())
}

@Composable
private fun TripSummary(left: TripLeft, withUnits: Boolean, modifier: Modifier = Modifier) {
    val (time, timeUnit) = NavFormat.duration(left.seconds)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TripStat("ETA", left.eta, "", Modifier.weight(1f), Hmi.Cyan)
        TripStat("TIME", time, if (withUnits) " $timeUnit" else "", Modifier.weight(1f))
        TripStat("DIST", NavFormat.kilometres(left.metres), if (withUnits) " KM" else "", Modifier.weight(1f))
    }
}

/**
 * The phone's route guidance, for Revv's map to show: CarPlay's while Follow CarPlay's route or
 * Show CarPlay's turns (Settings › CarPlay) is on, else Android Auto's while Show Android Auto's
 * turns (Settings › Android Auto) is on; null without a route, or with both off.
 */
@Composable
fun rememberProjectionGuidance(state: HmiUiState): ProjectionGuidance? {
    val carPlayShown = state.settings.isOn(SettingsStore.CARPLAY_FOLLOW_ROUTE) || state.settings.isOn(SettingsStore.CARPLAY_SHOW_TURNS)
    val androidAutoShown = state.settings.isOn(SettingsStore.ANDROID_AUTO_SHOW_TURNS)
    val carPlay = (if (carPlayShown) state.carPlay.guidance else NO_CARPLAY_GUIDANCE).collectAsStateWithLifecycle().value
    val androidAuto = (if (androidAutoShown) state.androidAuto.guidance else NO_ANDROID_AUTO_GUIDANCE).collectAsStateWithLifecycle().value
    return carPlay?.toProjection() ?: androidAuto?.toProjection()
}

private val NO_CARPLAY_GUIDANCE = MutableStateFlow<com.vivekkaushik.revv.carplay.CarPlay.Guidance?>(null)
private val NO_ANDROID_AUTO_GUIDANCE = MutableStateFlow<com.vivekkaushik.revv.androidauto.AndroidAuto.Guidance?>(null)

@Composable
private fun TripStat(label: String, value: String, unit: String, modifier: Modifier, color: Color = Hmi.Text) {
    Column(modifier) {
        Caption(label, size = 14.sp)
        Reading(value, unit, 32.sp, Modifier.padding(top = 8.dp), color = color)
    }
}

/** Shown over an empty map: why there's no car on it, and the way to fix that. [modifier] sets its width. */
@Composable
private fun LocationNotice(access: LocationAccess, actions: HmiActions, modifier: Modifier) {
    Column(
        modifier.border(1.dp, Hmi.LineStrong).background(Hmi.MapBg.copy(alpha = 0.92f)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Caption(accessCaption(access))
        val detail = when (access) {
            LocationAccess.NeedsPermission -> "Revv needs location access to show where you are and guide you."
            LocationAccess.Off -> "Location is switched off on this head unit."
            else -> "Waiting for the first GPS fix. This can take a minute under open sky."
        }
        HText(detail, size = 18.sp, color = Hmi.Text)
        when (access) {
            LocationAccess.NeedsPermission -> AccentButton("ALLOW", actions::requestLocationPermission, Modifier.padding(top = 8.dp).height(52.dp))
            LocationAccess.Off -> AccentButton("OPEN", actions::openLocationSettings, Modifier.padding(top = 8.dp).height(52.dp))
            else -> Unit
        }
    }
}

/** The map follows the demo drive's pretend car until the head unit's own GPS takes over. */
@Composable
private fun DemoNotice(access: LocationAccess, actions: HmiActions, modifier: Modifier) {
    Row(
        modifier.border(1.dp, Hmi.Amber.copy(alpha = 0.5f)).background(Hmi.MapBg.copy(alpha = 0.9f)).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HText("DEMO DRIVE", Modifier.padding(vertical = 14.dp), size = 15.sp, color = Hmi.Amber, spacing = 2.sp)
        when (access) {
            LocationAccess.NeedsPermission -> GhostButton("USE GPS", actions::requestLocationPermission, Modifier.height(44.dp))
            LocationAccess.Off -> GhostButton("TURN ON LOCATION", actions::openLocationSettings, Modifier.height(44.dp))
            else -> Unit
        }
    }
}

private fun accessCaption(access: LocationAccess) = when (access) {
    LocationAccess.NeedsPermission -> "LOCATION ACCESS NEEDED"
    LocationAccess.Off -> "LOCATION IS OFF"
    else -> "FINDING GPS…"
}

/** OpenStreetMap's licence asks for credit wherever its data is shown. */
@Composable
private fun MapAttribution(modifier: Modifier) {
    HText("© OPENSTREETMAP · OPENMAPTILES · OPENFREEMAP", modifier, size = 11.sp, color = Hmi.Faint, spacing = 1.sp)
}

@Composable
private fun ModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        AccentButton(text, onClick, Modifier.height(56.dp))
    } else {
        GhostButton(text, onClick, Modifier.height(56.dp))
    }
}

@Composable
private fun MapButton(onClick: () -> Unit, repeatEveryMillis: Long = 0, content: @Composable () -> Unit) {
    Pressable(onClick = onClick, Modifier.size(56.dp), background = Hmi.MapBg.copy(alpha = 0.9f), repeatEveryMillis = repeatEveryMillis) { content() }
}
