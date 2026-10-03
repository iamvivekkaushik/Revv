package com.vivekkaushik.revv.ui.hmi

import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import com.vivekkaushik.revv.system.CarPlayCompanion
import com.vivekkaushik.revv.system.CarPlayCompanion.State

/**
 * The Auto screen when Revv CarPlay is installed. CarPlay itself fills the left panel, inside
 * Revv's chrome, and the right column holds its status, controls and the Android Auto shortcut;
 * with [wide] on, CarPlay takes the whole screen and the controls move to Settings › CarPlay.
 * The gear to the companion's settings sits in the overlay header, beside the clock.
 * Revv keeps the companion bound for its whole run (see LauncherViewModel.carPlay); this screen
 * only attaches its view, so leaving it keeps the session and the music going.
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun CarPlayAutoScreen(companion: CarPlayCompanion, actions: HmiActions, wide: Boolean, projectionLabel: String?, onChooseApp: () -> Unit) {
    val context = LocalContext.current
    val state by companion.state.collectAsState()
    val open: () -> Unit = { CarPlayCompanion.launchIntent(context)?.let(context::startActivity) }
    if (wide) {
        Box(Modifier.fillMaxSize().border(1.dp, Hmi.Line).padding(1.dp)) {
            CarPlayPanel(companion, Modifier.fillMaxSize())
            if (state !is State.Session) Cover(state)
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.weight(1.7f).fillMaxHeight().border(1.dp, Hmi.Line).padding(1.dp)) {
            CarPlayPanel(companion, Modifier.fillMaxSize())
            if (state !is State.Session) Cover(state)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).padding(32.dp)) {
                Caption("CARPLAY", Modifier.padding(bottom = 16.dp))
                HText(state.headline(), size = 28.sp, family = Hmi.Display, lineHeight = 34.sp)
                HText(state.explanation(), Modifier.padding(top = 12.dp), size = 16.sp, color = Hmi.Muted, lineHeight = 24.sp)
                Spacer(Modifier.weight(1f))
                (state as? State.Session)?.let { session ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SessionAction(session, companion, open, Modifier.weight(1f).height(64.dp))
                        if (session.phase != CarPlayCompanion.PHASE_SETUP_REQUIRED && session.phase != CarPlayCompanion.PHASE_IDLE && session.phase != CarPlayCompanion.PHASE_FAILED) {
                            GhostButton("SIRI", companion::siri, Modifier.weight(1f).height(64.dp))
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 24.dp)) {
                val session = state as? State.Session
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption("LINK", Modifier.weight(1f), size = 14.sp)
                    Caption(if (session?.videoActive == true) "VIDEO LIVE" else "", size = 14.sp, color = Hmi.Cyan)
                }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinkChooser(session, companion, Modifier.weight(1f))
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
}

/** What the driver can do about the session right now: finish setup, connect, or disconnect. */
@Composable
private fun SessionAction(session: State.Session, companion: CarPlayCompanion, open: () -> Unit, modifier: Modifier) {
    when (session.phase) {
        CarPlayCompanion.PHASE_SETUP_REQUIRED -> SolidButton("FINISH SETUP", open, modifier)
        CarPlayCompanion.PHASE_IDLE, CarPlayCompanion.PHASE_FAILED -> SolidButton("CONNECT", companion::retry, modifier)
        else -> GhostButton("DISCONNECT", companion::stop, modifier)
    }
}

/** USB, Wi-Fi Direct or the car hotspot; the choice is saved in the companion, which reconnects over it. */
@Composable
private fun RowScopeOrAny.LinkChooser(session: State.Session?, companion: CarPlayCompanion, each: Modifier) {
    val usb = session != null && !session.wireless
    val direct = session != null && session.wireless && session.hotspotMode != CarPlayCompanion.HOTSPOT_MANUAL
    val hotspot = session != null && session.wireless && session.hotspotMode == CarPlayCompanion.HOTSPOT_MANUAL
    LinkChoice("USB", usb, each) { companion.configure(wireless = false) }
    LinkChoice("WI-FI DIRECT", direct, each) { companion.configure(wireless = true, hotspotMode = CarPlayCompanion.HOTSPOT_P2P) }
    LinkChoice("CAR HOTSPOT", hotspot, each) { companion.configure(wireless = true, hotspotMode = CarPlayCompanion.HOTSPOT_MANUAL) }
}

// LinkChooser is used inside Rows only; this alias keeps its call sites short.
private typealias RowScopeOrAny = androidx.compose.foundation.layout.RowScope

@Composable
private fun LinkChoice(label: String, chosen: Boolean, modifier: Modifier, onChoose: () -> Unit) {
    if (chosen) AccentButton(label, onChoose, modifier.height(52.dp)) else GhostButton(label, onChoose, modifier.height(52.dp))
}

/** Without the companion's own view the surface may keep its last picture; cover it. */
@Composable
private fun BoxScope.Cover(state: State) {
    Box(Modifier.fillMaxSize().background(Hmi.Black), contentAlignment = Alignment.Center) {
        HText(state.headline(), size = 22.sp, color = Hmi.Muted, family = Hmi.Display)
    }
}

internal fun State.headline(): String = when (this) {
    State.Unavailable -> "Revv CarPlay not available"
    State.Connecting -> "Reaching Revv CarPlay…"
    is State.Refused -> "Revv CarPlay declined"
    is State.Session -> when (phase) {
        CarPlayCompanion.PHASE_SETUP_REQUIRED -> "One-time setup"
        CarPlayCompanion.PHASE_IDLE -> "CarPlay is off"
        CarPlayCompanion.PHASE_STARTING -> "Starting CarPlay"
        CarPlayCompanion.PHASE_CONNECTING -> "Looking for your iPhone"
        CarPlayCompanion.PHASE_CONNECTED -> "CarPlay connected"
        CarPlayCompanion.PHASE_RECONNECTING -> "Reconnecting"
        CarPlayCompanion.PHASE_FAILED -> "CarPlay stopped"
        else -> phase
    }
}

internal fun State.explanation(): String = when (this) {
    State.Unavailable -> "Install the Revv CarPlay companion app, Android 11 or newer."
    State.Connecting -> "Binding to the companion app."
    is State.Refused -> when (error) {
        "unsupported" -> "This head unit runs Android 10 or older; the companion needs Android 11."
        else -> "The companion could not show CarPlay here ($error)."
    }
    is State.Session -> when (phase) {
        CarPlayCompanion.PHASE_SETUP_REQUIRED -> missing.joinToString(" ") {
            when (it) {
                CarPlayCompanion.SETUP_IDENTITY -> "Import your CarPlay identity files in Revv CarPlay."
                CarPlayCompanion.SETUP_VPN -> "Allow the VPN connection Revv CarPlay uses for USB."
                CarPlayCompanion.SETUP_WIRELESS_PERMISSIONS -> "Allow Bluetooth and nearby devices for wireless CarPlay."
                CarPlayCompanion.SETUP_HOTSPOT -> "Under Connection setup in Revv CarPlay, pick Wi-Fi Direct or save the car hotspot's name and password."
                else -> it
            }
        }.ifBlank { detail }
        CarPlayCompanion.PHASE_CONNECTED -> if (videoActive) "Your iPhone's maps, calls, messages and media, right here." else detail
        else -> detail
    }
}

/**
 * The SurfaceView CarPlay is drawn into. Its host token goes to the companion, which returns a
 * SurfacePackage; from then on the companion draws here, and Revv forwards this view's touches.
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun CarPlayPanel(companion: CarPlayCompanion, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember { SurfaceView(context) }
    // The notification shade takes focus and shows the system bars, which shrinks this panel for
    // as long as it is down; the companion must not reconnect CarPlay for that.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, companion) { companion.setHostFocused(focused) }
    DisposableEffect(view, companion) {
        var attached = false
        fun attach() {
            val token = view.hostToken ?: return
            val display = view.display ?: return
            val metrics = context.resources.displayMetrics
            companion.attach(token, display.displayId, view.width, view.height, metrics.widthPixels, metrics.heightPixels)
            attached = true
        }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                if (attached) companion.resize(width, height) else attach()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                attached = false
                companion.detach()
            }
        }
        companion.onSurfacePackage = { surfacePackage -> view.setChildSurfacePackage(surfacePackage) }
        // The companion's hierarchy is behind this window in input order, so touches land here.
        view.setOnTouchListener { v, event -> companion.touch(event, v.width, v.height); true }
        view.holder.addCallback(callback)
        if (view.holder.surface?.isValid == true && view.width > 0) attach()
        onDispose {
            view.setOnTouchListener(null)
            view.holder.removeCallback(callback)
            companion.onSurfacePackage = null
            companion.detach()
        }
    }
    AndroidView({ view }, modifier)
}
