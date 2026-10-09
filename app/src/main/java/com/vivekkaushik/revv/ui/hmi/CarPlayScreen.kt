package com.vivekkaushik.revv.ui.hmi

import android.view.Surface
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shilapi.xcertplay.embed.EmbeddedCarPlayView
import com.vivekkaushik.revv.carplay.CarPlay
import com.vivekkaushik.revv.carplay.CarPlay.State

/**
 * The Auto screen. CarPlay itself fills the left panel, inside Revv's chrome, and the side column
 * beside it holds its status, controls and the Android Auto shortcut; [carPlayRight] swaps the
 * two. With [wide] on, CarPlay takes the whole screen and the controls move to Settings › CarPlay.
 * With [settings], the side column shows CarPlay's settings (Settings › CarPlay) instead; CarPlay
 * keeps its size either way, so switching never reconnects it. The compact layout (display sizes
 * above 130%) leaves the link choice to Settings › CarPlay. The gear to CarPlay's settings sits in
 * the overlay header, beside the clock. The session lives in [CarPlay] for Revv's whole run; this
 * screen only attaches its view, so leaving it keeps the session and the music going.
 */
@Composable
fun CarPlayAutoScreen(
    carPlay: CarPlay,
    actions: HmiActions,
    wide: Boolean,
    projectionLabel: String?,
    onChooseApp: () -> Unit,
    settings: (@Composable (Modifier) -> Unit)? = null,
    carPlayRight: Boolean = false,
) {
    val state by carPlay.state.collectAsState()
    if (wide) {
        CarPlayView(carPlay, Modifier.fillMaxSize())
        return
    }
    val compact = LocalCompact.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 24.dp else 28.dp)) {
        if (!carPlayRight) CarPlayView(carPlay, Modifier.weight(1.7f).fillMaxHeight())
        val side = Modifier.weight(1f).fillMaxHeight()
        when {
            settings != null -> settings(side.border(1.dp, Hmi.Line).padding(horizontal = if (compact) 20.dp else 28.dp, vertical = 24.dp))
            compact -> CompactControls(state, carPlay, actions, onChooseApp, side)
            else -> Controls(state, carPlay, actions, projectionLabel, onChooseApp, side)
        }
        if (carPlayRight) CarPlayView(carPlay, Modifier.weight(1.7f).fillMaxHeight())
    }
}

/**
 * CarPlay over all of Revv, header and dock included, from the full-screen button beside its
 * settings. Revv's icon in CarPlay, or back, brings it back into the Auto screen (see
 * LauncherViewModel); until the iPhone's picture is up, a button does too.
 */
@Composable
fun CarPlayFullScreen(carPlay: CarPlay, onExit: () -> Unit) {
    val state by carPlay.state.collectAsState()
    val live = state.phase == CarPlay.PHASE_CONNECTED && state.videoActive
    Box(Modifier.fillMaxSize().opaqueToTouch().background(Hmi.Black)) {
        CarPlayPanel(carPlay, Modifier.fillMaxSize())
        if (!live) {
            Column(
                Modifier.align(Alignment.Center).padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HText(state.headline(), size = 22.sp, color = Hmi.Muted, family = Hmi.Display)
                GhostButton("EXIT FULL SCREEN", onExit, Modifier.height(56.dp))
            }
        }
    }
}

/** CarPlay itself, in Revv's frame; the view shows its own status line while there is no picture. */
@Composable
private fun CarPlayView(carPlay: CarPlay, modifier: Modifier) {
    Box(modifier.border(1.dp, Hmi.Line).padding(1.dp)) {
        CarPlayPanel(carPlay, Modifier.fillMaxSize())
    }
}

/** The side column: CarPlay's status and session actions, the link, and the Android Auto shortcut. */
@Composable
private fun Controls(
    state: State,
    carPlay: CarPlay,
    actions: HmiActions,
    projectionLabel: String?,
    onChooseApp: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).padding(32.dp)) {
            Caption("CARPLAY", Modifier.padding(bottom = 16.dp))
            HText(state.headline(), size = 28.sp, family = Hmi.Display, lineHeight = 34.sp)
            HText(state.explanation(), Modifier.padding(top = 12.dp), size = 16.sp, color = Hmi.Muted, lineHeight = 24.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SessionAction(state, carPlay, actions, Modifier.weight(1f).height(64.dp))
                if (state.phase != CarPlay.PHASE_SETUP_REQUIRED && state.phase != CarPlay.PHASE_IDLE && state.phase != CarPlay.PHASE_FAILED) {
                    GhostButton("SIRI", carPlay::siri, Modifier.weight(1f).height(64.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("LINK", Modifier.weight(1f), size = 14.sp)
                Caption(if (state.videoActive) "VIDEO LIVE" else "", size = 14.sp, color = Hmi.Cyan)
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinkChooser(state, carPlay, Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Caption("ANDROID AUTO", size = 14.sp)
                HText("OPENS · " + (projectionLabel?.uppercase() ?: "AUTOMATIC"), Modifier.padding(top = 8.dp), size = 14.sp, color = Hmi.Muted, spacing = 2.sp, maxLines = 1)
            }
            GhostButton("START", actions::startProjection, Modifier.height(56.dp))
            GhostButton("CHOOSE", onChooseApp, Modifier.height(56.dp))
        }
    }
}

/**
 * The side column in the compact layout (display sizes above 130%), where it is too narrow for the
 * link buttons: the status with its actions stacked, then Android Auto. The link is chosen in
 * Settings › CarPlay instead. It scrolls if a long explanation needs more room.
 */
@Composable
private fun CompactControls(
    state: State,
    carPlay: CarPlay,
    actions: HmiActions,
    onChooseApp: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("CARPLAY")
            HText(state.headline(), size = 24.sp, family = Hmi.Display, lineHeight = 30.sp)
            HText(state.explanation(), size = 16.sp, color = Hmi.Muted, lineHeight = 24.sp)
            SessionAction(state, carPlay, actions, Modifier.padding(top = 8.dp).fillMaxWidth().height(60.dp))
            if (state.phase != CarPlay.PHASE_SETUP_REQUIRED && state.phase != CarPlay.PHASE_IDLE && state.phase != CarPlay.PHASE_FAILED) {
                GhostButton("SIRI", carPlay::siri, Modifier.fillMaxWidth().height(60.dp))
            }
        }
        Column(Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("ANDROID AUTO", size = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("START", actions::startProjection, Modifier.weight(1f).height(56.dp))
                GhostButton("CHOOSE", onChooseApp, Modifier.weight(1f).height(56.dp))
            }
        }
    }
}

/**
 * What the driver can do about the session right now: finish setup, connect, or disconnect. Setup
 * that takes a prompt (permissions, the VPN consent) is asked for here; the rest is in Settings › CarPlay.
 */
@Composable
private fun SessionAction(state: State, carPlay: CarPlay, actions: HmiActions, modifier: Modifier) {
    when (state.phase) {
        CarPlay.PHASE_SETUP_REQUIRED -> if (state.missing.any { it == CarPlay.SETUP_VPN || it == CarPlay.SETUP_WIRELESS_PERMISSIONS }) {
            SolidButton("FINISH SETUP", actions::finishCarPlaySetup, modifier)
        } else {
            SolidButton("OPEN SETTINGS", actions::openCarPlaySettings, modifier)
        }
        CarPlay.PHASE_FAILED -> if (state.resetWifiDirect) {
            SolidButton("RESET & CONNECT", carPlay::resetWifiDirect, modifier)
        } else {
            SolidButton("CONNECT", carPlay::retry, modifier)
        }
        CarPlay.PHASE_IDLE -> SolidButton("CONNECT", carPlay::retry, modifier)
        else -> GhostButton("DISCONNECT", carPlay::stop, modifier)
    }
}

/** USB, Wi-Fi Direct or the car hotspot; the choice is saved, and a running session reconnects over it. */
@Composable
private fun RowScope.LinkChooser(state: State, carPlay: CarPlay, each: Modifier) {
    val usb = !state.wireless
    val direct = state.wireless && state.hotspotMode != CarPlay.HOTSPOT_MANUAL
    val hotspot = state.wireless && state.hotspotMode == CarPlay.HOTSPOT_MANUAL
    LinkChoice("USB", usb, each) { carPlay.configure(wireless = false) }
    LinkChoice("WI-FI DIRECT", direct, each) { carPlay.configure(wireless = true, hotspotMode = CarPlay.HOTSPOT_P2P) }
    LinkChoice("CAR HOTSPOT", hotspot, each) { carPlay.configure(wireless = true, hotspotMode = CarPlay.HOTSPOT_MANUAL) }
}

@Composable
private fun LinkChoice(label: String, chosen: Boolean, modifier: Modifier, onChoose: () -> Unit) {
    if (chosen) AccentButton(label, onChoose, modifier.height(52.dp)) else GhostButton(label, onChoose, modifier.height(52.dp))
}

internal fun State.headline(): String = when (phase) {
    CarPlay.PHASE_SETUP_REQUIRED -> "One-time setup"
    CarPlay.PHASE_IDLE -> "CarPlay is off"
    CarPlay.PHASE_STARTING -> "Starting CarPlay"
    CarPlay.PHASE_CONNECTING -> "Looking for your iPhone"
    CarPlay.PHASE_CONNECTED -> "CarPlay connected"
    CarPlay.PHASE_RECONNECTING -> "Reconnecting"
    CarPlay.PHASE_FAILED -> if (resetWifiDirect) "Wi-Fi Direct is busy" else "CarPlay stopped"
    else -> phase
}

internal fun State.explanation(): String = when (phase) {
    CarPlay.PHASE_SETUP_REQUIRED -> missing.joinToString(" ") {
        when (it) {
            CarPlay.SETUP_IDENTITY -> "Import your CarPlay identity in Settings › CarPlay."
            CarPlay.SETUP_VPN -> "Allow the VPN connection Revv uses for USB CarPlay."
            CarPlay.SETUP_WIRELESS_PERMISSIONS -> "Allow Bluetooth and nearby devices for wireless CarPlay."
            CarPlay.SETUP_HOTSPOT -> "In Settings › CarPlay, pick Wi-Fi Direct or save the car hotspot's name and password."
            else -> it
        }
    }.ifBlank { detail }
    CarPlay.PHASE_CONNECTED -> if (videoActive) "Your iPhone's maps, calls, messages and media, right here." else detail
    CarPlay.PHASE_FAILED -> if (resetWifiDirect) "$detail Reset & connect ends it and connects CarPlay." else detail
    else -> detail
}

/**
 * The view CarPlay is drawn into. Once it has a size, the session starts (or carries on) at that
 * size; later size changes reconnect it, and the view takes the iPhone's touches itself.
 */
@Composable
private fun CarPlayPanel(carPlay: CarPlay, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { EmbeddedCarPlayView(context) }
    // The notification shade takes focus and shows the system bars, which shrinks this panel for
    // as long as it is down; CarPlay must not reconnect for that.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, carPlay) { carPlay.setHostFocused(focused) }
    DisposableEffect(view, carPlay) {
        var attached = false
        fun sized(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            if (attached) {
                carPlay.resize(width, height)
                return
            }
            val metrics = context.resources.displayMetrics
            val rotation = view.display?.rotation ?: Surface.ROTATION_0
            carPlay.attach(width, height, metrics.widthPixels, metrics.heightPixels, rotation)
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
            carPlay.detach()
        }
    }
    AndroidView({ view }, modifier)
}
