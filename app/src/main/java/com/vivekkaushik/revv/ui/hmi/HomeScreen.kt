package com.vivekkaushik.revv.ui.hmi

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
                Cluster(live, framed, state.settings.car.gears, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(28.dp))
                Row(
                    Modifier.fillMaxWidth().height(220.dp).reveal(ready, 800, 300, riseBy = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    FuelCard(live, Modifier.weight(1f).fillMaxHeight())
                    PhoneCard(state.phone, now, timeFormat, actions, Modifier.weight(1f).fillMaxHeight())
                    MediaCard(state.nowPlaying, state.system.hasMediaAccess, state.phone.link?.name, actions, Modifier.weight(1.4f).fillMaxHeight())
                }
            }
            NavPanel(
                navigation,
                covered = state.screen.app != null,
                timeFormat = timeFormat,
                actions = actions,
                modifier = Modifier.weight(770f).fillMaxHeight().reveal(ready, 1000, 400),
            )
        }
    }
}

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

@Composable
private fun Cluster(live: LiveTelemetry, visible: Boolean, gears: Int, modifier: Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(600, easing = Hmi.Ease), label = "frame")
    Row(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .border(1.dp, Hmi.Line)
            .drawWithContent {
                drawContent()
                drawCornerBrackets(live.introSeconds, live.phase)
            },
    ) {
        SpeedPanel(live, Modifier.weight(1f).fillMaxHeight().edgeLine(bottom = false))
        Column(Modifier.width(400.dp).fillMaxHeight()) {
            RpmPanel(live, Modifier.weight(1f).fillMaxWidth().edgeLine())
            GearPanel(live, gears, Modifier.fillMaxWidth())
        }
    }
}

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
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 36.dp), verticalArrangement = Arrangement.SpaceBetween) {
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

@Composable
private fun RpmPanel(live: LiveTelemetry, modifier: Modifier) {
    Column(modifier.padding(horizontal = 32.dp, vertical = 36.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("RPM ×1000")
            Caption("RED 6.0", color = Hmi.Red)
        }
        RpmReadout(live)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RpmSegments({ live.rpmFraction }, Modifier.fillMaxWidth().height(40.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "2", "4", "6", "8").forEach { HText(it, size = 13.sp, color = Hmi.Muted) }
            }
        }
    }
}

@Composable
private fun RpmReadout(live: LiveTelemetry) {
    HText(if (live.hasNoData()) "--" else tenths(live.rpmTenths), size = 64.sp, family = Hmi.Display, spacing = (-2).sp, lineHeight = 64.sp)
}

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

@Composable
private fun GearPanel(live: LiveTelemetry, gears: Int, modifier: Modifier) {
    val selected = live.gearIndex
    val strip = remember(gears) {
        listOf(DriveSimulator.GEAR_REVERSE to "R") + (1..gears).map { it to "$it" } + (DriveSimulator.GEAR_NEUTRAL to "N")
    }
    Column(
        modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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

@Composable
private fun FuelCard(live: LiveTelemetry, modifier: Modifier) {
    val figures = live.figures
    Column(
        modifier.border(1.dp, Hmi.Line).padding(horizontal = 28.dp, vertical = 22.dp),
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
        modifier.border(1.dp, Hmi.Line).padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("PHONE")
            Caption(PhoneFormat.badge(phone.link), Modifier.weight(1f, fill = false).padding(start = 16.dp), maxLines = 1)
        }
        val button = Modifier.fillMaxWidth().height(52.dp)
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
private fun MediaCard(nowPlaying: NowPlaying?, hasAccess: Boolean, phoneName: String?, actions: HmiActions, modifier: Modifier) {
    Row(
        modifier.border(1.dp, Hmi.Line).padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            when {
                !hasAccess -> {
                    Caption("MEDIA")
                    TrackText("Media access", "TAP ALLOW TO CONNECT")
                    Spacer(Modifier.height(19.dp))
                }
                nowPlaying == null -> {
                    Caption("MEDIA")
                    TrackText("Nothing playing", "PRESS PLAY TO OPEN MUSIC")
                    Spacer(Modifier.height(19.dp))
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
                    PlaybackProgress(nowPlaying)
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

@Composable
private fun PlaybackProgress(nowPlaying: NowPlaying) {
    val position by rememberPlaybackPosition(nowPlaying)
    val duration = nowPlaying.durationMs
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HText(formatDuration(position), size = 15.sp)
        Box(Modifier.weight(1f).height(4.dp).background(Hmi.Line)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Hmi.Text))
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
