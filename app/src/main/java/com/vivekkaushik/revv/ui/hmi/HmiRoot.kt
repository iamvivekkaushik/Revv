package com.vivekkaushik.revv.ui.hmi

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import com.vivekkaushik.revv.phone.CallStage
import com.vivekkaushik.revv.phone.ActiveCall
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vivekkaushik.revv.nav.NavState
import com.vivekkaushik.revv.obd.ObdReadings
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private enum class DockItem(val label: String, val icon: String, val app: HmiApp?) {
    Phone("PHONE", HmiIcons.PHONE, HmiApp.Phone),
    Auto("AUTO", HmiIcons.AUTO, HmiApp.Auto),
    Maps("MAPS", HmiIcons.MAPS, HmiApp.Maps),
    Home("HOME", HmiIcons.HOME, null),
    Vehicle("VEHICLE", HmiIcons.VEHICLE, HmiApp.Vehicle),
    Camera("REAR CAM", HmiIcons.CAMERA, HmiApp.Camera),
    Apps("APPS", HmiIcons.APPS, HmiApp.Apps),
}

/**
 * The whole "Swift HMI v4" screen: home, the app layered over it, and the dock on top.
 * [obdReadings] changes several times a second, so it feeds the cluster directly instead of
 * recomposing everything through [state].
 */
@Composable
fun HmiRoot(state: HmiUiState, obdReadings: StateFlow<ObdReadings?>, navigation: StateFlow<NavState>, actions: HmiActions) {
    val settings = state.settings
    val ignition = rememberIgnition(
        reducedMotion = settings.reducedMotion,
        demoDrive = settings.demoDrive,
        reversing = state.screen.app == HmiApp.Camera,
        adapterSetUp = settings.obdAdapter != null,
        obdStatus = state.obd,
        obdReadings = obdReadings,
    )
    val now by rememberCurrentTime()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = ClockFormats.is24Hour(settings, DateFormat.is24HourFormat(context))
    val datePattern = ClockFormats.datePattern(settings)
    val timeFormat = remember(is24Hour, locale) { DateTimeFormatter.ofPattern(ClockFormats.timePattern(is24Hour), locale) }
    val clock = remember(now, timeFormat) { now.format(timeFormat) }
    val date = remember(now, locale, datePattern) { now.format(DateTimeFormatter.ofPattern(datePattern, locale)).uppercase(locale) }

    val ready = ignition.live.isReady
    LaunchedEffect(ready) {
        if (ready) actions.onHmiReady()
    }
    DemoCarTicker(ignition.live, actions)

    // A launcher never finishes on back; it steps back through the open apps to home. Screens
    // with pages of their own (Settings) close those first with their own handlers.
    BackHandler { actions.back() }

    val menuSound = remember(context) { MenuSound(context) }
    DisposableEffect(menuSound) { onDispose { menuSound.release() } }
    menuSound.enabled = buildSet {
        if (settings.menuSound) add(SoundGroup.Menu)
        if (settings.dialerSound) add(SoundGroup.Dialer)
        if (settings.keyboardSound) add(SoundGroup.Keyboard)
    }
    CompositionLocalProvider(
        LocalTouchFeedback provides settings.touchFeedback,
        LocalMenuSound provides menuSound,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Hmi.Black)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        ) {
            DesignCanvas(sizePercent = settings.displaySize) {
                HomeScreen(
                    state = state,
                    live = ignition.live,
                    clock = clock,
                    date = date,
                    now = now,
                    navigation = navigation,
                    timeFormat = timeFormat,
                    actions = actions,
                    onReplayIgnition = {
                        ignition.start(skipSequence = settings.reducedMotion)
                        actions.goHome()
                    },
                )
                AppLayer(state, ignition.live, clock, now, navigation, timeFormat, actions)
                state.call?.let { call ->
                    CallBar(call, actions::hangUp, Modifier.align(Alignment.TopCenter))
                }
                Dock(
                    current = state.screen.app,
                    visible = ready,
                    vehicleAlert = ignition.live.figures.health == Health.Alert,
                    actions = actions,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun AppLayer(
    state: HmiUiState,
    live: LiveTelemetry,
    clock: String,
    now: LocalDateTime,
    navigation: StateFlow<NavState>,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
) {
    key(state.screen.resetCount) {
        // Keep drawing the app that is closing while it fades out.
        val lastApp = remember { LastValue<HmiApp>() }
        state.screen.app?.let { lastApp.value = it }
        val shown = state.screen.app ?: lastApp.value
        val rise = with(LocalDensity.current) { 30.dp.roundToPx() }
        AnimatedVisibility(
            visible = state.screen.app != null,
            enter = fadeIn(tween(400, easing = Hmi.Ease)) + slideInVertically(tween(500, easing = Hmi.Glide)) { rise },
            exit = fadeOut(tween(400, easing = Hmi.Ease)) + slideOutVertically(tween(500, easing = Hmi.Glide)) { rise },
        ) {
            if (shown != null) AppOverlay(shown, state, live, clock, now, navigation, timeFormat, actions)
        }
    }
}

private class LastValue<T : Any> {
    var value: T? = null
}

/**
 * While the cluster runs the demo drive, tells navigation how far the pretend car got each second,
 * so the map moves at the speed on the gauges. Pauses while Revv is off screen, like the cluster.
 */
@Composable
private fun DemoCarTicker(live: LiveTelemetry, actions: HmiActions) {
    val demo = live.source == DataSource.Demo
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(demo, lifecycle) {
        if (!demo) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(DEMO_TICK_MILLIS)
                val now = SystemClock.elapsedRealtime()
                actions.advanceDemoDrive((now - last) / 1000f, live.speedKmh)
                last = now
            }
        }
    }
}

private const val DEMO_TICK_MILLIS = 1_000L

/** A strip across the top while a call is on: who, how it is going, and a button to end it. */
@Composable
private fun CallBar(call: ActiveCall, onHangUp: () -> Unit, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(call.answeredAt) {
        while (call.answeredAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val status = when (call.stage) {
        CallStage.Dialling -> "CALLING"
        CallStage.Ringing -> "RINGING"
        CallStage.Ending -> "ENDING CALL"
        CallStage.Connected -> {
            val seconds = ((now - (call.answeredAt ?: now)) / 1000).coerceAtLeast(0)
            "ON CALL · %d:%02d".format(seconds / 60, seconds % 60)
        }
    }
    Row(
        modifier
            .padding(top = 6.dp)
            .background(Hmi.Bg)
            .border(1.dp, Hmi.Cyan)
            .padding(start = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(10.dp).background(Hmi.Cyan))
        HText(status, size = 16.sp, color = Hmi.Cyan, spacing = 2.sp)
        HText(call.name ?: call.number, size = 22.sp, weight = FontWeight.Medium, maxLines = 1)
        Pressable(
            onClick = onHangUp,
            Modifier.height(56.dp),
            background = Hmi.Red,
            pressedBackground = Hmi.Red.copy(alpha = 0.7f),
            border = null,
        ) {
            HText("END CALL", Modifier.padding(horizontal = 28.dp), size = 16.sp, weight = FontWeight.Bold, color = Color.White, spacing = 2.sp)
        }
    }
}

@Composable
private fun Dock(current: HmiApp?, visible: Boolean, vehicleAlert: Boolean, actions: HmiActions, modifier: Modifier) {
    Row(
        modifier
            .reveal(visible, 800, 500, riseBy = 40.dp)
            .border(1.dp, Hmi.LineStrong)
            .background(Hmi.Bg.copy(alpha = 0.9f))
            .padding(1.dp),
    ) {
        DockItem.entries.forEach { item ->
            val selected = item.app == current
            val tint = if (selected) Hmi.Cyan else Hmi.Muted
            Pressable(
                onClick = { item.app?.let(actions::open) ?: actions.goHome() },
                modifier = Modifier
                    .width(if (item == DockItem.Home) 160.dp else 132.dp)
                    .height(76.dp)
                    .edgeLine(bottom = false),
                background = if (selected) Hmi.Cyan.copy(alpha = 0.12f) else Color.Transparent,
                pressedBackground = Hmi.CyanPressed,
                border = null,
            ) {
                Box {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PathIcon(item.icon, 26.dp, tint)
                        HText(item.label, size = 13.sp, color = tint, spacing = 2.sp)
                    }
                    if (item == DockItem.Vehicle && vehicleAlert) {
                        AlertBadge(Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-6).dp), 20.dp)
                    }
                }
            }
        }
    }
}

/** The wall-clock time, refreshed on every system minute tick and on clock or time zone changes. */
@Composable
private fun rememberCurrentTime(): State<LocalDateTime> {
    val context = LocalContext.current
    val time = remember { mutableStateOf(LocalDateTime.now()) }
    LifecycleStartEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                time.value = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        time.value = LocalDateTime.now()
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    return time
}
