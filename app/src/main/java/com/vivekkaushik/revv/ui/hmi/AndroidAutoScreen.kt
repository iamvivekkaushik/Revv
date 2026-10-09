package com.vivekkaushik.revv.ui.hmi

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.andrerinas.openheadunit.embed.EmbeddedAndroidAutoView
import com.vivekkaushik.revv.androidauto.AndroidAuto
import com.vivekkaushik.revv.androidauto.AndroidAuto.State
import com.vivekkaushik.revv.settings.SettingsStore

/**
 * The Auto screen with Android Auto chosen (Settings › Android Auto › Phone, or the PHONE card
 * here). Android Auto itself fills the left panel, inside Revv's chrome, and the side column
 * beside it holds its status, controls, the link and the phone switcher; [androidAutoRight] swaps
 * the two. With [wide] on, Android Auto takes the whole screen and the controls move to
 * Settings › Android Auto. With [settings], the side column shows Android Auto's settings
 * instead; Android Auto keeps its size either way, so switching never reconnects it. The compact
 * layout (display sizes above 130%) leaves the link choice to Settings › Android Auto. The gear
 * to its settings sits in the overlay header, beside the clock. The session lives in [AndroidAuto]
 * for Revv's whole run; this screen only attaches its view, so leaving it keeps the session and
 * the music going.
 */
@Composable
fun AndroidAutoAutoScreen(
    androidAuto: AndroidAuto,
    actions: HmiActions,
    wide: Boolean,
    projectionLabel: String?,
    onChooseApp: () -> Unit,
    settings: (@Composable (Modifier) -> Unit)? = null,
    androidAutoRight: Boolean = false,
) {
    val state by androidAuto.state.collectAsState()
    val link by androidAuto.settings.collectAsState()
    if (wide) {
        AndroidAutoView(androidAuto, Modifier.fillMaxSize())
        return
    }
    val compact = LocalCompact.current
    // While waiting with a wireless link, Connect wakes the chosen phone over Bluetooth.
    val canWake = state.wireless && link.phoneAddress != null
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 24.dp else 28.dp)) {
        if (!androidAutoRight) AndroidAutoView(androidAuto, Modifier.weight(1.7f).fillMaxHeight())
        val side = Modifier.weight(1f).fillMaxHeight()
        when {
            settings != null -> settings(side.border(1.dp, Hmi.Line).padding(horizontal = if (compact) 20.dp else 28.dp, vertical = 24.dp))
            compact -> CompactControls(state, canWake, androidAuto, actions, projectionLabel, onChooseApp, side)
            else -> Controls(state, canWake, androidAuto, actions, projectionLabel, onChooseApp, side)
        }
        if (androidAutoRight) AndroidAutoView(androidAuto, Modifier.weight(1.7f).fillMaxHeight())
    }
}

/**
 * Android Auto over all of Revv, header and dock included, from the full-screen button beside
 * its settings. Back brings it into the Auto screen again; until the phone's picture is up, a
 * button does too.
 */
@Composable
fun AndroidAutoFullScreen(androidAuto: AndroidAuto, onExit: () -> Unit) {
    val state by androidAuto.state.collectAsState()
    val live = state.phase == AndroidAuto.PHASE_CONNECTED && state.videoActive
    Box(Modifier.fillMaxSize().opaqueToTouch().background(Hmi.Black)) {
        AndroidAutoPanel(androidAuto, Modifier.fillMaxSize())
        // The view writes what to do in the middle; Revv's headline and the way out go below it.
        if (!live) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HText(state.headline(), size = 22.sp, color = Hmi.Muted, family = Hmi.Display)
                GhostButton("EXIT FULL SCREEN", onExit, Modifier.height(56.dp))
            }
        }
    }
}

/** Android Auto itself, in Revv's frame; the view shows its own status line while there is no picture. */
@Composable
private fun AndroidAutoView(androidAuto: AndroidAuto, modifier: Modifier) {
    Box(modifier.border(1.dp, Hmi.Line).padding(1.dp)) {
        AndroidAutoPanel(androidAuto, Modifier.fillMaxSize())
    }
}

/** The side column: Android Auto's status and session actions, the link, and the phone switcher. */
@Composable
private fun Controls(
    state: State,
    canWake: Boolean,
    androidAuto: AndroidAuto,
    actions: HmiActions,
    projectionLabel: String?,
    onChooseApp: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).padding(32.dp)) {
            Caption("ANDROID AUTO", Modifier.padding(bottom = 16.dp))
            HText(state.headline(), size = 28.sp, family = Hmi.Display, lineHeight = 34.sp)
            HText(state.explanation(canWake), Modifier.padding(top = 12.dp), size = 16.sp, color = Hmi.Muted, lineHeight = 24.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SessionAction(state, canWake, androidAuto, Modifier.weight(1f).height(64.dp))
                SecondAction(state, canWake, androidAuto, Modifier.weight(1f).height(64.dp))
            }
        }
        Column(Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("LINK", Modifier.weight(1f), size = 14.sp)
                Caption(if (state.videoActive) "VIDEO LIVE" else "", size = 14.sp, color = Hmi.Cyan)
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinkChooser(androidAuto, Modifier.weight(1f))
            }
        }
        ProjectionSourceCard(SettingsStore.AUTO_ANDROID_AUTO, actions, projectionLabel, onChooseApp, compact = false)
    }
}

/**
 * The side column in the compact layout (display sizes above 130%), where it is too narrow for the
 * link buttons: the status with its actions stacked, then the phone switcher. The link is chosen in
 * Settings › Android Auto instead. It scrolls if a long explanation needs more room.
 */
@Composable
private fun CompactControls(
    state: State,
    canWake: Boolean,
    androidAuto: AndroidAuto,
    actions: HmiActions,
    projectionLabel: String?,
    onChooseApp: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("ANDROID AUTO")
            HText(state.headline(), size = 24.sp, family = Hmi.Display, lineHeight = 30.sp)
            HText(state.explanation(canWake), size = 16.sp, color = Hmi.Muted, lineHeight = 24.sp)
            SessionAction(state, canWake, androidAuto, Modifier.padding(top = 8.dp).fillMaxWidth().height(60.dp))
            SecondAction(state, canWake, androidAuto, Modifier.fillMaxWidth().height(60.dp))
        }
        ProjectionSourceCard(SettingsStore.AUTO_ANDROID_AUTO, actions, projectionLabel, onChooseApp, compact = true)
    }
}

/**
 * What the driver can do about the session right now: connect (which, while waiting with a
 * wireless link, wakes the chosen phone over Bluetooth), or disconnect.
 */
@Composable
private fun SessionAction(state: State, canWake: Boolean, androidAuto: AndroidAuto, modifier: Modifier) {
    when (state.phase) {
        AndroidAuto.PHASE_IDLE, AndroidAuto.PHASE_FAILED -> SolidButton("CONNECT", androidAuto::retry, modifier)
        AndroidAuto.PHASE_WAITING -> if (state.phoneExited || canWake) {
            SolidButton("CONNECT", androidAuto::retry, modifier)
        } else {
            GhostButton("TURN OFF", androidAuto::stop, modifier)
        }
        else -> GhostButton("DISCONNECT", androidAuto::stop, modifier)
    }
}

/** Beside the session action: the assistant once connected, or the way to stop waiting. */
@Composable
private fun SecondAction(state: State, canWake: Boolean, androidAuto: AndroidAuto, modifier: Modifier) {
    when {
        state.phase == AndroidAuto.PHASE_CONNECTED -> GhostButton("ASSISTANT", androidAuto::assistant, modifier)
        state.phase == AndroidAuto.PHASE_WAITING && (state.phoneExited || canWake) -> GhostButton("TURN OFF", androidAuto::stop, modifier)
    }
}

/** USB only, or wireless over Wi-Fi Direct or the car hotspot besides; the choice is saved and the link re-armed. */
@Composable
private fun RowScope.LinkChooser(androidAuto: AndroidAuto, each: Modifier) {
    val settings by androidAuto.settings.collectAsState()
    LinkChoice("USB", settings.wireless == AndroidAuto.WIRELESS_OFF, each) { androidAuto.configure(AndroidAuto.WIRELESS_OFF) }
    LinkChoice("WI-FI DIRECT", settings.wireless == AndroidAuto.WIRELESS_WIFI_DIRECT, each) { androidAuto.configure(AndroidAuto.WIRELESS_WIFI_DIRECT) }
    LinkChoice("CAR HOTSPOT", settings.wireless == AndroidAuto.WIRELESS_CAR_HOTSPOT, each) { androidAuto.configure(AndroidAuto.WIRELESS_CAR_HOTSPOT) }
}

@Composable
private fun LinkChoice(label: String, chosen: Boolean, modifier: Modifier, onChoose: () -> Unit) {
    if (chosen) AccentButton(label, onChoose, modifier.height(52.dp)) else GhostButton(label, onChoose, modifier.height(52.dp))
}

/**
 * The card at the foot of either projection's side column: which phone the Auto screen shows,
 * CarPlay or Android Auto ([current]), and Start projection's other app for head units with one
 * of their own.
 */
@Composable
internal fun ProjectionSourceCard(current: Int, actions: HmiActions, projectionLabel: String?, onChooseApp: () -> Unit, compact: Boolean) {
    val pad = if (compact) 24.dp else 32.dp
    Column(
        Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = pad, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Caption("PHONE", size = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            SourceChoice("CARPLAY", current == SettingsStore.AUTO_CARPLAY, Modifier.weight(1f)) { actions.setAutoSource(SettingsStore.AUTO_CARPLAY) }
            SourceChoice("ANDROID AUTO", current == SettingsStore.AUTO_ANDROID_AUTO, Modifier.weight(1f)) { actions.setAutoSource(SettingsStore.AUTO_ANDROID_AUTO) }
        }
        if (compact) {
            Caption("OTHER APP · " + (projectionLabel?.uppercase() ?: "AUTOMATIC"), size = 12.sp, color = Hmi.Muted, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("START", actions::startProjection, Modifier.weight(1f).height(56.dp))
                GhostButton("CHOOSE", onChooseApp, Modifier.weight(1f).height(56.dp))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caption("OTHER APP", size = 12.sp, color = Hmi.Muted)
                    HText(projectionLabel?.uppercase() ?: "AUTOMATIC", Modifier.padding(top = 6.dp), size = 14.sp, color = Hmi.Muted, spacing = 2.sp, maxLines = 1)
                }
                GhostButton("START", actions::startProjection, Modifier.height(52.dp))
                GhostButton("CHOOSE", onChooseApp, Modifier.height(52.dp))
            }
        }
    }
}

@Composable
private fun SourceChoice(label: String, chosen: Boolean, modifier: Modifier, onChoose: () -> Unit) {
    if (chosen) AccentButton(label, onChoose, modifier.height(52.dp)) else GhostButton(label, onChoose, modifier.height(52.dp))
}

internal fun State.headline(): String = when (phase) {
    AndroidAuto.PHASE_IDLE -> "Android Auto is off"
    AndroidAuto.PHASE_STARTING -> "Starting Android Auto"
    AndroidAuto.PHASE_WAITING -> if (phoneExited) "Android Auto was closed" else "Waiting for your phone"
    AndroidAuto.PHASE_CONNECTING -> "Connecting to your phone"
    AndroidAuto.PHASE_CONNECTED -> "Android Auto connected"
    AndroidAuto.PHASE_RECONNECTING -> "Reconnecting"
    AndroidAuto.PHASE_FAILED -> "Android Auto stopped"
    else -> phase
}

internal fun State.explanation(canWake: Boolean = false): String = when (phase) {
    AndroidAuto.PHASE_IDLE -> "Connect to wait for a phone over USB" + (if (wireless) " or Wi-Fi." else ".")
    AndroidAuto.PHASE_WAITING -> when {
        phoneExited -> "Android Auto was exited on the phone. Connect asks for it again."
        canWake -> "Plug an Android phone in, or tap Connect to wake the chosen phone over Bluetooth so it joins over Wi-Fi."
        wireless -> "Plug an Android phone in, or let a paired one join over Wi-Fi; choosing the phone in Settings › Android Auto lets Revv wake it."
        else -> "Plug an Android phone in over USB and allow Revv to use it when Android asks. Wireless Android Auto is set up in its settings."
    }
    AndroidAuto.PHASE_CONNECTED -> if (videoActive) "Your phone's maps, calls, messages and media, right here." else detail
    else -> detail
}

/**
 * The view Android Auto is drawn into. Once it has a size, the session starts (or carries on) at
 * that size; later size changes reconnect it if the phone needs another resolution, and the view
 * takes the phone's touches itself.
 */
@Composable
private fun AndroidAutoPanel(androidAuto: AndroidAuto, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { EmbeddedAndroidAutoView(context) }
    DisposableEffect(view, androidAuto) {
        var attached = false
        fun sized(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            if (attached) {
                androidAuto.resize(width, height)
                return
            }
            val metrics = context.resources.displayMetrics
            androidAuto.attach(width, height, metrics.widthPixels, metrics.heightPixels, metrics)
            attached = true
        }
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (!attached || width != oldRight - oldLeft || height != oldBottom - oldTop) sized(width, height)
        }
        view.addOnLayoutChangeListener(listener)
        if (view.isAttachedToWindow) sized(view.width, view.height)
        onDispose {
            view.removeOnLayoutChangeListener(listener)
            androidAuto.detach()
        }
    }
    AndroidView({ view }, modifier)
}
