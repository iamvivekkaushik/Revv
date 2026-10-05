package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.apps.LauncherApp
import androidx.compose.ui.layout.onPlaced
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.media.NowPlaying
import com.vivekkaushik.revv.media.formatDuration
import com.vivekkaushik.revv.nav.NavState
import com.vivekkaushik.revv.obd.ObdLink
import com.vivekkaushik.revv.phone.CallType
import com.vivekkaushik.revv.phone.PhoneState
import com.vivekkaushik.revv.phone.PhoneSync
import com.vivekkaushik.revv.settings.HmiSettings
import com.vivekkaushik.revv.settings.SettingsStore
import com.vivekkaushik.revv.vehicle.DriveSimulator
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val MajorTick = Color(0xCCE6EDF3)
private val MinorTick = Color.White.copy(alpha = 0.25f)

@Composable
fun HomeScreen(
    state: HmiUiState,
    live: LiveTelemetry,
    clock: String,
    date: String,
    now: LocalDateTime,
    navigation: StateFlow<NavState>,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
    onReplayIgnition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = live.phase
    val ready = phase >= DriveSimulator.PHASE_READY
    val framed = phase >= DriveSimulator.PHASE_FRAME
    val gridAlpha by animateFloatAsState(if (framed) 1f else 0f, tween(1200, easing = Hmi.Ease), label = "grid")

    Box(modifier.fillMaxSize().background(Hmi.Bg).blueprintGrid { gridAlpha }) {
        if (LocalCompact.current) {
            CompactHome(state, live, clock, date, now, navigation, timeFormat, actions, onReplayIgnition, ready, framed)
            return@Box
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1150f)
                    .fillMaxHeight()
                    .padding(start = 56.dp, top = 40.dp, end = 40.dp, bottom = 130.dp),
            ) {
                StatusBar(
                    state = state,
                    live = live,
                    clock = clock,
                    date = date,
                    actions = actions,
                    onReplayIgnition = onReplayIgnition,
                    modifier = Modifier.fillMaxWidth().height(44.dp).reveal(ready, 800, 200),
                )
                Spacer(Modifier.height(28.dp))
                Cluster(live, framed, state.settings.car.gears, state.settings.isOn(SettingsStore.FULL_RPM), Modifier.weight(1f).fillMaxWidth())
                val cards = homeCards(state.settings)
                if (cards.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    HomeCards(cards, state, live, now, timeFormat, actions, Modifier.fillMaxWidth().height(220.dp).reveal(ready, 800, 300, riseBy = 24.dp))
                }
            }
            if (state.settings.showMapPanel) {
                NavPanel(
                    navigation,
                    covered = state.screen.app != null,
                    timeFormat = timeFormat,
                    actions = actions,
                    modifier = Modifier.weight(770f).fillMaxHeight().reveal(ready, 1000, 400),
                    carPlay = rememberCarPlayGuidance(state),
                )
            }
        }
    }
}

/**
 * The home screen at display sizes above 130%, with as little as 1200×675 design pixels: the status
 * bar across the top, then the cluster, its gauges stacked, beside the map with the media card under
 * it. The fuel and phone cards give way to the map; without the map they sit under the cluster.
 */
@Composable
private fun CompactHome(
    state: HmiUiState,
    live: LiveTelemetry,
    clock: String,
    date: String,
    now: LocalDateTime,
    navigation: StateFlow<NavState>,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
    onReplayIgnition: () -> Unit,
    ready: Boolean,
    framed: Boolean,
) {
    val settings = state.settings
    Column(
        Modifier.fillMaxSize().padding(start = 32.dp, top = 16.dp, end = 32.dp, bottom = COMPACT_BOTTOM_MARGIN),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusBar(
            state = state,
            live = live,
            clock = clock,
            date = date,
            actions = actions,
            onReplayIgnition = onReplayIgnition,
            modifier = Modifier.fillMaxWidth().height(44.dp).reveal(ready, 800, 200),
        )
        if (settings.showMapPanel) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Cluster(live, framed, settings.car.gears, settings.isOn(SettingsStore.FULL_RPM), Modifier.weight(1.2f).fillMaxHeight(), stacked = true)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NavPanel(
                        navigation,
                        covered = state.screen.app != null,
                        timeFormat = timeFormat,
                        actions = actions,
                        modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).reveal(ready, 1000, 400),
                        carPlay = rememberCarPlayGuidance(state),
                    )
                    if (settings.showMediaCard) {
                        MediaCard(
                            state.nowPlaying,
                            state.system.hasMediaAccess,
                            state.phone.link?.name,
                            settings.mediaSwipeToSeek,
                            actions,
                            Modifier.fillMaxWidth().height(COMPACT_CARD_HEIGHT).reveal(ready, 800, 300, riseBy = 24.dp),
                        )
                    }
                }
            }
        } else {
            Cluster(live, framed, settings.car.gears, settings.isOn(SettingsStore.FULL_RPM), Modifier.weight(1f).fillMaxWidth())
            val cards = homeCards(settings)
            if (cards.isNotEmpty()) {
                HomeCards(cards, state, live, now, timeFormat, actions, Modifier.fillMaxWidth().height(COMPACT_CARD_HEIGHT).reveal(ready, 800, 300, riseBy = 24.dp))
            }
        }
    }
}

private val COMPACT_CARD_HEIGHT = 168.dp

private enum class HomeCard { Fuel, Phone, Media }

/** The cards under the cluster, as chosen in Settings › Home. */
private fun homeCards(settings: HmiSettings): List<HomeCard> = buildList {
    if (settings.showFuelWidget) add(HomeCard.Fuel)
    if (settings.showPhoneCard) add(HomeCard.Phone)
    if (settings.showMediaCard) add(HomeCard.Media)
}

@Composable
private fun HomeCards(
    cards: List<HomeCard>,
    state: HmiUiState,
    live: LiveTelemetry,
    now: LocalDateTime,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
    modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 24.dp else 28.dp)) {
        cards.forEach { card ->
            when (card) {
                HomeCard.Fuel -> {
                    val shortcut = state.settings.fuelWidgetApp?.let { key -> state.apps.firstOrNull { it.key == key } }
                    if (shortcut != null) {
                        AppShortcutCard(shortcut, actions, Modifier.weight(1f).fillMaxHeight())
                    } else {
                        FuelCard(live, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                HomeCard.Phone -> PhoneCard(state.phone, now, timeFormat, actions, Modifier.weight(1f).fillMaxHeight())
                HomeCard.Media -> MediaCard(
                    state.nowPlaying,
                    state.system.hasMediaAccess,
                    state.phone.link?.name,
                    state.settings.mediaSwipeToSeek,
                    actions,
                    Modifier.weight(1.4f).fillMaxHeight(),
                )
            }
        }
    }
}

/** A home card's inner margins; the compact layout's cards are shorter. */
@Composable
private fun cardPadding(): PaddingValues =
    if (LocalCompact.current) PaddingValues(horizontal = 24.dp, vertical = 18.dp) else PaddingValues(horizontal = 28.dp, vertical = 22.dp)

@Composable
private fun StatusBar(
    state: HmiUiState,
    live: LiveTelemetry,
    clock: String,
    date: String,
    actions: HmiActions,
    onReplayIgnition: () -> Unit,
    modifier: Modifier,
) {
    val figures = live.figures
    val obdDot = when (state.obd.link) {
        ObdLink.Live -> Hmi.Cyan
        ObdLink.Connecting, ObdLink.NoEcu, ObdLink.Retrying -> Hmi.Amber
        else -> Hmi.Faint
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HText(clock, size = 26.sp, weight = FontWeight.Medium)
            HText(date, size = 18.sp, color = Hmi.Muted, spacing = 1.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (!state.system.isDefaultHome) {
                AccentButton("SET AS HOME", actions::requestDefaultHome, Modifier.height(40.dp))
            }
            if (live.source == DataSource.Demo) {
                // The car's figures are simulated while this shows.
                HText("DEMO", size = 18.sp, color = Hmi.Amber, spacing = 1.sp)
            }
            figures.outsideTemperature?.let { temperature ->
                HText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Hmi.Text)) { append(temperature) }
                        append(" ")
                        append(figures.outsidePlace)
                    },
                    size = 18.sp,
                    color = Hmi.Muted,
                    spacing = 1.sp,
                )
            }
            HText("BT", size = 18.sp, color = if (state.system.bluetoothOn) Hmi.Cyan else Hmi.Muted, spacing = 1.sp)
            HText(
                buildAnnotatedString {
                    append("OBD-II ")
                    withStyle(SpanStyle(color = obdDot)) { append("●") }
                },
                size = 18.sp,
                color = Hmi.Muted,
                spacing = 1.sp,
            )
            Pressable(onClick = { actions.open(HmiApp.Settings) }, Modifier.size(40.dp)) {
                PathIcon(HmiIcons.SETTINGS, 18.dp, Hmi.Muted, strokeWidth = 1.8f)
            }
            Pressable(onClick = onReplayIgnition, Modifier.size(40.dp)) {
                PathIcon(HmiIcons.POWER, 18.dp, Hmi.Cyan, strokeWidth = 2f)
            }
        }
    }
}

/**
 * [stacked] puts RPM and the gear under the speed instead of beside it, for a narrow cluster;
 * [fullRpm] shows the revs in rpm rather than thousands.
 */
@Composable
private fun Cluster(live: LiveTelemetry, visible: Boolean, gears: Int, fullRpm: Boolean, modifier: Modifier, stacked: Boolean = false) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(600, easing = Hmi.Ease), label = "frame")
    val frame = modifier
        .graphicsLayer { this.alpha = alpha }
        .border(1.dp, Hmi.Line)
        .drawWithContent {
            drawContent()
            drawCornerBrackets(live.introSeconds, live.phase)
        }
    if (stacked) {
        Column(frame) {
            SpeedPanel(live, Modifier.weight(1f).fillMaxWidth().edgeLine())
            Row(Modifier.fillMaxWidth().height(STACKED_GAUGES_HEIGHT)) {
                RpmPanel(live, fullRpm, Modifier.weight(1f).fillMaxHeight().edgeLine(bottom = false))
                GearPanel(live, gears, Modifier.weight(1f).fillMaxHeight(), spread = true)
            }
        }
        return
    }
    Row(frame) {
        SpeedPanel(live, Modifier.weight(1f).fillMaxHeight().edgeLine(bottom = false))
        Column(Modifier.width(400.dp).fillMaxHeight()) {
            RpmPanel(live, fullRpm, Modifier.weight(1f).fillMaxWidth().edgeLine())
            GearPanel(live, gears, Modifier.fillMaxWidth())
        }
    }
}

/** The RPM and gear row under the speed in a stacked cluster. */
private val STACKED_GAUGES_HEIGHT = 212.dp

/** The four cyan corner marks, which fly in from 318px arms to 18px as the cluster powers up. */
private fun DrawScope.drawCornerBrackets(introSeconds: Float, phase: Int) {
    if (phase == DriveSimulator.PHASE_DARK) return
    val thickness = 2.dp.toPx()
    val outset = 1.dp.toPx()
    for (corner in 0 until 4) {
        val progress = ((introSeconds - 0.2f - corner * 0.12f) / 0.5f).coerceIn(0f, 1f)
        val arm = (18f + (1f - progress) * 300f).dp.toPx()
        val left = corner % 2 == 0
        val top = corner < 2
        val horizontalX = if (left) -outset else size.width + outset - arm
        val horizontalY = if (top) -outset else size.height + outset - thickness
        drawRect(Hmi.Cyan, Offset(horizontalX, horizontalY), Size(arm, thickness))
        val verticalX = if (left) -outset else size.width + outset - thickness
        val verticalY = if (top) -outset else size.height + outset - arm
        drawRect(Hmi.Cyan, Offset(verticalX, verticalY), Size(thickness, arm))
    }
}

@Composable
private fun SpeedPanel(live: LiveTelemetry, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        // The digits shrink from the design's 150 when the panel is squeezed, so they never run into the side readings or the scale.
        val digitSize = minOf(150f, maxHeight.value * 0.4f, (maxWidth.value - 80f - 20f - 190f) / 2.7f).coerceAtLeast(60f)
        val compact = LocalCompact.current
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 32.dp else 40.dp, vertical = if (compact) 24.dp else 36.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption("SPEED · KM/H")
                Caption("MAX 200")
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SpeedReadout(live, digitSize, Modifier.width((digitSize * 2.7f).dp))
                Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EconomyLine(live)
                    CoolantLine(live)
                }
            }
            SpeedScale({ live.speedFraction }, Modifier.fillMaxWidth().height(44.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "50", "100", "150", "200").forEach { HText(it, size = 14.sp, color = Hmi.Muted) }
            }
        }
    }
}

/** With no demo and no car answering there's nothing to show, so dashes, like the other readings. */
private fun LiveTelemetry.hasNoData() = source == DataSource.None && isReady

@Composable
private fun SpeedReadout(live: LiveTelemetry, digitSize: Float, modifier: Modifier) {
    HText(
        if (live.hasNoData()) "--" else live.speedKmh.toString(),
        modifier,
        size = digitSize.sp,
        family = Hmi.Display,
        spacing = (-digitSize / 30f).sp,
        lineHeight = (digitSize * 0.9f).sp,
        maxLines = 1,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun EconomyLine(live: LiveTelemetry) {
    val tenthsValue = live.kmPerLitreTenths
    StatLine(if (tenthsValue < 0) "--" else tenths(tenthsValue), " KM/L")
}

@Composable
private fun CoolantLine(live: LiveTelemetry) {
    StatLine(live.figures.coolant, " °C COOLANT")
}

@Composable
private fun StatLine(value: String, unit: String) {
    Row {
        HText(value, Modifier.alignByBaseline(), size = 30.sp, weight = FontWeight.Medium)
        HText(unit, Modifier.alignByBaseline(), size = 18.sp, color = Hmi.Muted)
    }
}

@Composable
private fun SpeedScale(fraction: () -> Float, modifier: Modifier) {
    Canvas(modifier.graphicsLayer()) {
        val stroke = 1.5.dp.toPx()
        for (kmh in 0..200 step 5) {
            val major = kmh % 50 == 0
            val top = when {
                major -> 0f
                kmh % 10 == 0 -> 18.dp.toPx()
                else -> 30.dp.toPx()
            }
            val x = kmh / 200f * size.width
            drawLine(if (major) MajorTick else MinorTick, Offset(x, top), Offset(x, size.height), stroke)
        }
        val bar = 6.dp.toPx()
        drawRect(Hmi.Cyan, Offset(0f, size.height - bar), Size(size.width * fraction().coerceIn(0f, 1f), bar))
    }
}

/** [full] shows the revs in rpm (900), else in thousands (0.9) as the caption says. */
@Composable
private fun RpmPanel(live: LiveTelemetry, full: Boolean, modifier: Modifier) {
    val compact = LocalCompact.current
    Column(
        modifier.padding(horizontal = if (compact) 28.dp else 32.dp, vertical = if (compact) 20.dp else 36.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(if (full) "RPM" else "RPM ×1000")
            Caption(if (full) "RED 6000" else "RED 6.0", color = Hmi.Red)
        }
        RpmReadout(live, full)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RpmSegments({ live.rpmFraction }, Modifier.fillMaxWidth().height(if (compact) 32.dp else 40.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (if (full) FULL_RPM_MARKS else RPM_MARKS).forEach { HText(it, size = 13.sp, color = Hmi.Muted) }
            }
        }
    }
}

@Composable
private fun RpmReadout(live: LiveTelemetry, full: Boolean) {
    val text = when {
        live.hasNoData() -> "--"
        full -> live.rpm.toString()
        else -> tenths(live.rpmTenths)
    }
    HText(text, size = 64.sp, family = Hmi.Display, spacing = (-2).sp, lineHeight = 64.sp)
}

private val RPM_MARKS = listOf("0", "2", "4", "6", "8")
private val FULL_RPM_MARKS = listOf("0", "2000", "4000", "6000", "8000")

/** Sixteen bars; the last four are the redline. */
@Composable
private fun RpmSegments(fraction: () -> Float, modifier: Modifier) {
    Canvas(modifier.graphicsLayer()) {
        val count = 16
        val gap = 4.dp.toPx()
        val width = (size.width - gap * (count - 1)) / count
        val lit = Math.round(fraction() * count)
        for (i in 0 until count) {
            val color = when {
                i >= lit -> Hmi.LineSoft
                i >= 12 -> Hmi.Red
                else -> Hmi.Cyan
            }
            drawRect(color, Offset(i * (width + gap), 0f), Size(width, size.height))
        }
    }
}

/** [spread] pushes the caption to the top and the gears to the bottom, to line up beside the RPM panel. */
@Composable
private fun GearPanel(live: LiveTelemetry, gears: Int, modifier: Modifier, spread: Boolean = false) {
    val selected = live.gearIndex
    val strip = remember(gears) {
        listOf(DriveSimulator.GEAR_REVERSE to "R") + (1..gears).map { it to "$it" } + (DriveSimulator.GEAR_NEUTRAL to "N")
    }
    val compact = LocalCompact.current
    Column(
        if (compact) {
            modifier.padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 18.dp)
        } else {
            modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 28.dp)
        },
        verticalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("GEAR")
            Caption("$gears-SPEED")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            strip.forEach { (index, label) ->
                val on = index == selected
                val accent = if (index == DriveSimulator.GEAR_REVERSE) Hmi.Red else Hmi.Cyan
                Box(
                    Modifier
                        .weight(1f)
                        .height(54.dp)
                        .background(if (on) accent else Color.Transparent)
                        .border(1.dp, if (on) accent else Hmi.Line),
                    contentAlignment = Alignment.Center,
                ) {
                    HText(label, size = 20.sp, family = Hmi.Display, color = if (on) Hmi.Bg else Hmi.Muted)
                }
            }
        }
    }
}

/** The fuel widget's stand-in when the driver has picked an app for it: tap to open the app. */
@Composable
private fun AppShortcutCard(app: LauncherApp, actions: HmiActions, modifier: Modifier) {
    val source = remember { LaunchSource() }
    Pressable(
        onClick = { actions.launch(app, source.bounds()) },
        modifier = modifier.onPlaced { source.coordinates = it },
        pressedBackground = Hmi.CyanTint,
        border = Hmi.Line,
        pressedBorder = Hmi.Cyan,
    ) {
        Column(Modifier.fillMaxSize().padding(cardPadding()), verticalArrangement = Arrangement.SpaceBetween) {
            Caption("SHORTCUT")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AppIcon(app, 64.dp)
                HText(app.label, Modifier.weight(1f), size = 26.sp, weight = FontWeight.Medium, maxLines = 2)
            }
            HText("TAP TO OPEN", size = 15.sp, color = Hmi.Muted, spacing = 2.sp, maxLines = 1)
        }
    }
}

@Composable
private fun FuelCard(live: LiveTelemetry, modifier: Modifier) {
    val figures = live.figures
    Column(
        modifier.border(1.dp, Hmi.Line).padding(cardPadding()),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Caption("FUEL · RANGE")
        Reading(figures.range, "KM", 48.sp, valueSpacing = (-2).sp, unitSize = 18.sp, unitGap = 8.dp)
        Row(Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(10) { bar ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (bar < figures.fuelBars) Hmi.Cyan else Hmi.Line),
                )
            }
        }
        HText(figures.fuelDetail, size = 15.sp, color = Hmi.Muted, maxLines = 1)
    }
}

@Composable
private fun PhoneCard(phone: PhoneState, now: LocalDateTime, timeFormat: DateTimeFormatter, actions: HmiActions, modifier: Modifier) {
    val lastCall = phone.recents.firstOrNull()
    Column(
        modifier.border(1.dp, Hmi.Line).padding(cardPadding()),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("PHONE")
            Caption(PhoneFormat.badge(phone.link), Modifier.weight(1f, fill = false).padding(start = 16.dp), maxLines = 1)
        }
        val button = Modifier.fillMaxWidth().height(if (LocalCompact.current) 48.dp else 52.dp)
        val name = phone.link?.name.orEmpty()
        val openPhone = { actions.open(HmiApp.Phone) }
        when {
            lastCall == null -> when (phone.sync) {
                PhoneSync.NeedsPermission -> {
                    LastCall("Calls and contacts", "TAP ALLOW TO CONNECT", Hmi.Muted)
                    AccentButton("ALLOW", actions::requestBluetoothPermission, button)
                }
                PhoneSync.NoPhone -> {
                    LastCall("No phone connected", "PAIR ONE OVER BLUETOOTH", Hmi.Muted)
                    AccentButton("BLUETOOTH", actions::openBluetoothSettings, button)
                }
                PhoneSync.Reading -> {
                    LastCall(name, "READING CALLS…", Hmi.Muted)
                    AccentButton("OPEN PHONE", openPhone, button)
                }
                PhoneSync.AwaitingApproval -> {
                    LastCall(name, "ALLOW ACCESS ON THE PHONE", Hmi.Amber)
                    AccentButton("OPEN PHONE", openPhone, button)
                }
                PhoneSync.Failed -> {
                    LastCall(name, "COULDN'T READ CALLS", Hmi.Red)
                    AccentButton("TRY AGAIN", actions::readPhoneAgain, button)
                }
                PhoneSync.Synced -> {
                    LastCall("No recent calls", "NOTHING IN ITS CALL HISTORY", Hmi.Muted)
                    AccentButton("OPEN PHONE", openPhone, button)
                }
            }
            else -> {
                val missed = lastCall.type == CallType.Missed
                LastCall(lastCall.label, PhoneFormat.detail(lastCall, now, timeFormat), if (missed) Hmi.Red else Hmi.Muted)
                AccentButton(
                    if (lastCall.type == CallType.Outgoing) "CALL AGAIN" else "CALL BACK",
                    // A withheld number can't be called back; the phone screen has the rest.
                    { if (lastCall.number.isNotBlank()) actions.call(lastCall.number) else actions.open(HmiApp.Phone) },
                    button,
                )
            }
        }
    }
}

@Composable
private fun LastCall(title: String, detail: String, detailColor: Color) {
    Column {
        HText(title, size = 24.sp, weight = FontWeight.Medium, maxLines = 1)
        HText(detail, Modifier.padding(top = 4.dp), size = 15.sp, color = detailColor, maxLines = 1)
    }
}

@Composable
private fun MediaCard(
    nowPlaying: NowPlaying?,
    hasAccess: Boolean,
    phoneName: String?,
    swipeToSeek: Boolean,
    actions: HmiActions,
    modifier: Modifier,
) {
    val seek = remember(nowPlaying?.packageName, nowPlaying?.title) { SeekState() }
    Row(
        modifier.border(1.dp, Hmi.Line).padding(cardPadding()),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight().swipeToSeek(nowPlaying.takeIf { swipeToSeek && hasAccess }, seek, actions::seekTo),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            when {
                !hasAccess -> {
                    Caption("MEDIA")
                    TrackText("Media access", "TAP ALLOW TO CONNECT")
                    Spacer(Modifier.height(SEEK_TOUCH_HEIGHT))
                }
                nowPlaying == null -> {
                    Caption("MEDIA")
                    TrackText("Nothing playing", "PRESS PLAY TO OPEN MUSIC")
                    Spacer(Modifier.height(SEEK_TOUCH_HEIGHT))
                }
                else -> {
                    // The phone's music, over Bluetooth, goes by the phone's name rather than "Bluetooth".
                    val source = if (nowPlaying.fromPhone) phoneName ?: nowPlaying.appLabel else nowPlaying.appLabel
                    Caption("MEDIA · ${source.uppercase()}", maxLines = 1)
                    TrackText(
                        title = nowPlaying.title,
                        subtitle = nowPlaying.subtitle.uppercase(),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = actions::openPlayer,
                        ),
                    )
                    PlaybackProgress(nowPlaying, seek, actions::seekTo)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pressable(
                onClick = {
                    when {
                        !hasAccess -> actions.requestMediaAccess()
                        nowPlaying == null -> actions.openMusic()
                        else -> actions.playPause()
                    }
                },
                modifier = Modifier.size(96.dp, 60.dp),
                background = Hmi.Text,
                pressedBackground = Hmi.TextPressed,
                border = null,
            ) {
                if (hasAccess) {
                    val icon = if (nowPlaying?.isPlaying == true) HmiIcons.PAUSE else HmiIcons.PLAY
                    PathIcon(icon, 26.dp, Hmi.Bg, filled = true)
                } else {
                    HText("ALLOW", size = 15.sp, weight = FontWeight.Bold, color = Hmi.Bg, spacing = 2.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canSkipBack = nowPlaying?.canSkipPrevious == true
                val canSkipAhead = nowPlaying?.canSkipNext == true
                Pressable(onClick = { if (canSkipBack) actions.skipToPrevious() }, Modifier.size(44.dp)) {
                    PathIcon(HmiIcons.PREVIOUS, 18.dp, if (canSkipBack) Hmi.Text else Hmi.Faint, filled = true)
                }
                Pressable(onClick = { if (canSkipAhead) actions.skipToNext() }, Modifier.size(44.dp)) {
                    PathIcon(HmiIcons.NEXT, 18.dp, if (canSkipAhead) Hmi.Text else Hmi.Faint, filled = true)
                }
            }
        }
    }
}

@Composable
private fun TrackText(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        HText(title, size = 26.sp, weight = FontWeight.Medium, maxLines = 1)
        HText(subtitle, size = 16.sp, color = Hmi.Muted, maxLines = 1)
    }
}

/** The seek bar's touch area: easy to hit while driving, no taller than the buttons beside it. */
private val SEEK_TOUCH_HEIGHT = 32.dp

/** How long a seek shows where it asked for if the player never says where it went. */
private const val SEEK_HOLD_MILLIS = 2_000L

/**
 * Where the track is. When the player can seek, a finger on the bar shows where it would jump
 * to, and letting go jumps there; the bar then holds that spot until the player reports it.
 */
/**
 * Where a seek is heading: under the finger while scrubbing the bar or swiping the card, then
 * where it asked for until the player reports it.
 */
@Stable
private class SeekState {
    var scrubbing by mutableStateOf<Long?>(null)
    var asked by mutableStateOf<Long?>(null)
}

/** How much of the track a swipe across the whole card covers, at most; the bar reaches any of it. */
private const val SWIPE_SPAN_MILLIS = 5 * 60_000L

/**
 * Swiping left or right scrubs back or ahead from where the track is, across [nowPlaying]'s
 * length or [SWIPE_SPAN_MILLIS] for the card's width; letting go jumps there. Taps pass through.
 * Off with a null [nowPlaying] or a player that can't seek.
 */
@Composable
private fun Modifier.swipeToSeek(nowPlaying: NowPlaying?, seek: SeekState, onSeek: (Long) -> Unit): Modifier {
    val enabled = nowPlaying != null && nowPlaying.canSeek && nowPlaying.durationMs > 0
    val track by rememberUpdatedState(nowPlaying)
    if (!enabled) return this
    return pointerInput(nowPlaying?.packageName, nowPlaying?.title) {
        var from = 0L
        var moved = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                val now = track ?: return@detectHorizontalDragGestures
                from = seek.asked ?: now.positionAt(SystemClock.elapsedRealtime())
                moved = 0f
                seek.scrubbing = from
            },
            onHorizontalDrag = { change, dx ->
                val now = track ?: return@detectHorizontalDragGestures
                change.consume()
                moved += dx
                val span = minOf(now.durationMs, SWIPE_SPAN_MILLIS)
                seek.scrubbing = (from + moved / size.width * span).toLong().coerceIn(0, now.durationMs)
            },
            onDragEnd = {
                seek.scrubbing?.let {
                    seek.asked = it
                    onSeek(it)
                }
                seek.scrubbing = null
            },
            onDragCancel = { seek.scrubbing = null },
        )
    }
}

@Composable
private fun PlaybackProgress(nowPlaying: NowPlaying, seek: SeekState, onSeek: (Long) -> Unit) {
    val playing by rememberPlaybackPosition(nowPlaying)
    val duration = nowPlaying.durationMs
    val seekable = nowPlaying.canSeek && duration > 0
    // The player's next report says where it went.
    LaunchedEffect(nowPlaying.positionUpdatedAt, nowPlaying.positionMs) { seek.asked = null }
    LaunchedEffect(seek.asked) {
        if (seek.asked != null) {
            delay(SEEK_HOLD_MILLIS)
            seek.asked = null
        }
    }
    val scrubbing = seek.scrubbing
    val position = scrubbing ?: seek.asked ?: playing
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val active = scrubbing != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HText(formatDuration(position), size = 15.sp, color = if (active) Hmi.Cyan else Hmi.Text)
        BoxWithConstraints(
            Modifier.weight(1f).height(SEEK_TOUCH_HEIGHT).then(
                if (!seekable) {
                    Modifier
                } else {
                    Modifier.pointerInput(duration, seek) {
                        fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * duration).toLong()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var target: Long? = at(down.position.x)
                            seek.scrubbing = target
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        target = null
                                        break
                                    }
                                    if (!change.pressed) break
                                    target = at(change.position.x)
                                    seek.scrubbing = target
                                    change.consume()
                                }
                            } finally {
                                // Cancelled, say by the screen changing: no jump.
                                seek.scrubbing = null
                            }
                            target?.let {
                                seek.asked = it
                                onSeek(it)
                            }
                        }
                    }
                },
            ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(if (active) 6.dp else 4.dp).background(Hmi.Line)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(if (active) Hmi.Cyan else Hmi.Text))
            }
            if (seekable) {
                val thumb = if (active) 20.dp else 12.dp
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction - thumb / 2)
                        .size(thumb)
                        .clip(CircleShape)
                        .background(if (active) Hmi.Cyan else Hmi.Text),
                )
            }
        }
        HText(if (duration > 0) formatDuration(duration) else "--:--", size = 15.sp, color = Hmi.Muted)
    }
}

@Composable
private fun rememberPlaybackPosition(nowPlaying: NowPlaying): State<Long> =
    produceState(nowPlaying.positionAt(SystemClock.elapsedRealtime()), nowPlaying) {
        value = nowPlaying.positionAt(SystemClock.elapsedRealtime())
        while (nowPlaying.isPlaying) {
            delay(500)
            value = nowPlaying.positionAt(SystemClock.elapsedRealtime())
        }
    }
