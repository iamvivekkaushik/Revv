package com.vivekkaushik.revv.ui.hmi

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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.StateFlow

private const val HOME_ZOOM = 15.5
private const val SCREEN_ZOOM = 16.0
private const val HEADING_UP_TILT = 45.0

/** The map panel on the right of the home screen: the car, the route and the next turn. */
@Composable
fun NavPanel(
    navigation: StateFlow<NavState>,
    covered: Boolean,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
    modifier: Modifier = Modifier,
) {
    val nav by navigation.collectAsStateWithLifecycle()
    val trip = nav.trip
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
                .background(Brush.verticalGradient(0f to Hmi.MapBg, 0.45f to Hmi.MapBg, 1f to Color.Transparent)),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(300.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Hmi.MapBg, 1f to Hmi.MapBg)),
        )
        TripHeader(nav, distanceSize = 64.sp, controls = false, actions = actions, onSearch = {}, Modifier.padding(start = 36.dp, end = 36.dp, top = 40.dp))
        val guidance = trip?.guidance
        if (trip?.status == TripStatus.Guiding && guidance != null) {
            TripSummary(
                guidance,
                timeFormat,
                withUnits = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 36.dp, end = 36.dp, bottom = 130.dp)
                    .fillMaxWidth()
                    .border(1.dp, Hmi.LineStrong)
                    .background(Hmi.MapBg.copy(alpha = 0.85f))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
            )
        }
        MapAttribution(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp))
        // Anywhere on the panel opens the full Navigation screen.
        Pressable(
            onClick = { actions.open(HmiApp.Maps) },
            modifier = Modifier.fillMaxSize(),
            pressedBackground = Hmi.Cyan.copy(alpha = 0.04f),
            border = null,
        ) {}
        Box(Modifier.fillMaxHeight().width(1.dp).background(Hmi.Line))
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
    var followMode by rememberSaveable { mutableStateOf(MapCamera.NorthUp) }
    var panned by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableDoubleStateOf(SCREEN_ZOOM) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val bearing = remember { mutableFloatStateOf(0f) }
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
            headingUpTilt = HEADING_UP_TILT,
            headingUpShift = 0.4,
            onCameraFreed = { panned = true },
            onZoomChanged = { zoom = it },
            onBearingChanged = { bearing.floatValue = it },
            modifier = Modifier.fillMaxSize(),
        )
        TripHeader(
            nav,
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
        val guidance = trip?.guidance
        if (trip?.status == TripStatus.Guiding && guidance != null) {
            TripSummary(
                guidance,
                timeFormat,
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
            MapButton(onClick = { zoom = (zoom + 1).coerceAtMost(19.0) }) { HText("+", size = 24.sp, spacing = 1.sp) }
            MapButton(onClick = { zoom = (zoom - 1).coerceAtLeast(4.0) }) { HText("−", size = 24.sp, spacing = 1.sp) }
            // Points north as the map turns; a tap puts north back at the top.
            MapButton(onClick = { follow(MapCamera.NorthUp) }) {
                HText("N", Modifier.graphicsLayer { rotationZ = -bearing.floatValue }, size = 14.sp, color = Hmi.Cyan, spacing = 1.sp)
            }
        }
        if (nav.demo) DemoNotice(nav.access, actions, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
        if (nav.fix == null) LocationNotice(nav.access, actions, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (panned) AccentButton("RECENTER", { panned = false }, Modifier.height(56.dp))
            // Not in the design: hands off to the head unit's own navigation app, e.g. for traffic.
            GhostButton("OPEN MAPS APP", actions::openNavigationApp, Modifier.height(56.dp))
            ModeButton("2D NORTH-UP", selected = !panned && followMode == MapCamera.NorthUp) { follow(MapCamera.NorthUp) }
            ModeButton("HEADING-UP", selected = !panned && followMode == MapCamera.HeadingUp) { follow(MapCamera.HeadingUp) }
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
 * The top card: the next turn while guiding, or the trip's state, or an invitation to search.
 * [controls] adds the card's own buttons; on the home screen the whole panel is one button instead.
 */
@Composable
private fun TripHeader(
    nav: NavState,
    distanceSize: TextUnit,
    controls: Boolean,
    actions: HmiActions,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = nav.trip
    when {
        trip == null -> WhereTo(nav, controls, onSearch, modifier)
        trip.status == TripStatus.Guiding && trip.guidance != null -> NextTurn(trip, trip.guidance, distanceSize, modifier)
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
private fun NextTurn(trip: Trip, guidance: Guidance, distanceSize: TextUnit, modifier: Modifier) {
    val next = guidance.next
    val (distance, unit) = NavFormat.distance(guidance.metresToNext)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(110.dp).border(1.dp, Hmi.Cyan), contentAlignment = Alignment.Center) {
            PathIcon(
                NavFormat.icon(next.turn),
                64.dp,
                Hmi.Cyan,
                // Left-hand turns are the right-hand arrows mirrored.
                modifier = Modifier.graphicsLayer { scaleX = if (next.turn.left) -1f else 1f },
                strokeWidth = 5f,
                viewport = 64f,
            )
        }
        Column {
            Caption(if (trip.rerouting) "REROUTING…" else NavFormat.caption(next.turn, next.roundaboutExit), maxLines = 1)
            Row(Modifier.padding(top = 6.dp)) {
                HText(distance, Modifier.alignByBaseline(), size = distanceSize, family = Hmi.Display, spacing = (-2).sp)
                HText(unit, Modifier.alignByBaseline().padding(start = 8.dp), size = 20.sp, color = Hmi.Muted)
            }
            HText(
                next.road ?: trip.destination.name.takeIf { next.turn == Turn.Arrive } ?: "",
                Modifier.padding(top = 8.dp),
                size = 22.sp,
                maxLines = 1,
            )
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

@Composable
private fun TripSummary(guidance: Guidance, timeFormat: DateTimeFormatter, withUnits: Boolean, modifier: Modifier = Modifier) {
    val eta = LocalDateTime.now().plusSeconds(guidance.remainingSeconds.toLong()).format(timeFormat)
    val (time, timeUnit) = NavFormat.duration(guidance.remainingSeconds)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TripStat("ETA", eta, "", Modifier.weight(1f), Hmi.Cyan)
        TripStat("TIME", time, if (withUnits) " $timeUnit" else "", Modifier.weight(1f))
        TripStat("DIST", NavFormat.kilometres(guidance.remainingMetres), if (withUnits) " KM" else "", Modifier.weight(1f))
    }
}

@Composable
private fun TripStat(label: String, value: String, unit: String, modifier: Modifier, color: Color = Hmi.Text) {
    Column(modifier) {
        Caption(label, size = 14.sp)
        Reading(value, unit, 32.sp, Modifier.padding(top = 8.dp), color = color)
    }
}

/** Shown over an empty map: why there's no car on it, and the way to fix that. */
@Composable
private fun LocationNotice(access: LocationAccess, actions: HmiActions, modifier: Modifier) {
    Column(
        modifier.width(560.dp).border(1.dp, Hmi.LineStrong).background(Hmi.MapBg.copy(alpha = 0.92f)).padding(28.dp),
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
private fun MapButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Pressable(onClick = onClick, Modifier.size(56.dp), background = Hmi.MapBg.copy(alpha = 0.9f)) { content() }
}
