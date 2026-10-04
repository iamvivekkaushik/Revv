package com.vivekkaushik.revv.ui.hmi

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import com.vivekkaushik.revv.nav.NavState
import com.vivekkaushik.revv.settings.SettingsStore
import com.vivekkaushik.revv.phone.PhoneState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.StateFlow

/** Full-screen container for the HMI's apps: a HOME button, the app's title and the clock. */
@Composable
fun AppOverlay(
    app: HmiApp,
    state: HmiUiState,
    live: LiveTelemetry,
    clock: String,
    now: LocalDateTime,
    navigation: StateFlow<NavState>,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
    modifier: Modifier = Modifier,
) {
    // Each app keeps its place (settings page, scroll position) while back can return to it,
    // and starts afresh once it has left the stack.
    val screens = rememberSaveableStateHolder()
    val stack = state.screen.previous + app
    val kept = remember { HashSet<HmiApp>() }
    SideEffect {
        kept.filterNot { it in stack }.forEach(screens::removeState)
        kept.clear()
        kept += stack
    }
    val compact = LocalCompact.current
    val side = if (compact) 32.dp else 56.dp
    // The home screen stays underneath; taps between an app's controls mustn't reach it.
    Box(modifier.fillMaxSize().opaqueToTouch().background(Hmi.Bg).blueprintGrid()) {
        Row(
            Modifier.padding(start = side, end = side, top = if (compact) 16.dp else 40.dp).fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Pressable(onClick = actions::goHome, Modifier.height(44.dp)) {
                Row(
                    Modifier.padding(start = 14.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PathIcon(HmiIcons.BACK, 18.dp, Hmi.Text, strokeWidth = 2f)
                    HText("HOME", size = 16.sp, spacing = 2.sp)
                }
            }
            val carPlay = app == HmiApp.Auto && state.carPlay != null
            HText(if (carPlay) "CARPLAY" else app.title.uppercase(), size = 18.sp, color = Hmi.Muted, spacing = 4.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // CarPlay's settings, kept out of the CarPlay picture.
                if (carPlay) {
                    Pressable(actions::openCarPlaySettings, Modifier.size(44.dp)) {
                        PathIcon(HmiIcons.GEAR, 22.dp, Hmi.Muted)
                    }
                }
                HText(clock, size = 22.sp, weight = FontWeight.Medium)
            }
        }
        // The bottom 120px belong to the dock, which stays on top of every app. Maps and CarPlay
        // run right up to it, and so does everything in the compact layout, which needs the room.
        val bottom = when {
            compact -> COMPACT_BOTTOM_MARGIN
            app == HmiApp.Maps || (app == HmiApp.Auto && state.carPlay != null) -> MAPS_BOTTOM_MARGIN
            else -> 160.dp
        }
        Box(Modifier.fillMaxSize().padding(start = side, end = side, top = if (compact) 76.dp else 112.dp, bottom = bottom)) {
            screens.SaveableStateProvider(app) {
                when (app) {
                    HmiApp.Phone -> PhoneScreen(state.phone, now, timeFormat, actions)
                    HmiApp.Auto -> AutoScreen(state, actions)
                    HmiApp.Maps -> MapsScreen(state, navigation, timeFormat, actions)
                    HmiApp.Vehicle -> VehicleScreen(live, state.settings.car, actions)
                    HmiApp.Camera -> CameraScreen(live, state.settings.rearCameraId, state.settings.rearCameraRotation, actions)
                    HmiApp.Apps -> AppsScreen(state.apps, actions)
                    HmiApp.Settings -> SettingsScreen(state, actions)
                    HmiApp.Radio -> StubScreen(app.title, "NO RADIO APP FOUND ON THIS HEAD UNIT")
                }
            }
        }
    }
}

/** The dock is 78dp tall and sits 28dp up, so this leaves the map a 16dp gap above it. */
private val MAPS_BOTTOM_MARGIN = 122.dp

/** The compact dock is 62dp tall and sits 12dp up, so this leaves a 14dp gap above it. */
internal val COMPACT_BOTTOM_MARGIN = 88.dp

@Composable
private fun StubScreen(name: String, message: String) {
    Column(
        Modifier.fillMaxSize().border(1.dp, Hmi.Line),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        HText(name, size = 28.sp, family = Hmi.Display, spacing = 2.sp)
        Caption(message, size = 16.sp)
    }
}

@Composable
private fun AutoScreen(state: HmiUiState, actions: HmiActions) {
    val phone = state.phone
    val canSeeBluetooth = state.system.hasBluetoothPermission
    var choosingApp by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = choosingApp) { choosingApp = false }
    val chosen = state.settings.projectionApp?.let { key -> state.apps.firstOrNull { it.key == key } }
    if (choosingApp) {
        Column(Modifier.fillMaxSize().border(1.dp, Hmi.Line).padding(horizontal = 36.dp, vertical = 32.dp)) {
            AppChooser(
                title = "PROJECTION APP",
                hint = "The app Start projection opens on this screen.",
                apps = state.apps,
                chosen = state.settings.projectionApp,
                defaultTitle = "Automatic",
                defaultDetail = "Android Auto, or this head unit's own projection app",
                onChoose = actions::setProjectionApp,
                onDone = { choosingApp = false },
            )
        }
        return
    }
    // With the RevvCarPlay companion installed, CarPlay itself runs inside this screen.
    val carPlay = state.carPlay
    if (carPlay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        CarPlayAutoScreen(
            carPlay,
            actions,
            wide = state.settings.isOn(SettingsStore.CARPLAY_WIDE),
            projectionLabel = chosen?.label,
            onChooseApp = { choosingApp = true },
            // The driver may keep CarPlay's settings beside it instead of its status and controls.
            settings = if (state.settings.isOn(SettingsStore.CARPLAY_SETTINGS_BESIDE)) {
                { modifier -> CarPlaySettings(state, actions, modifier, title = "SETTINGS", besideCarPlay = true) }
            } else {
                null
            },
            carPlayRight = state.settings.isOn(SettingsStore.CARPLAY_ON_RIGHT),
        )
        return
    }
    // Compact (display sizes above 130%) drops the icon and the facts row, which only decorate.
    val compact = LocalCompact.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.weight(if (compact) 1.2f else 1f).fillMaxHeight().border(1.dp, Hmi.Line).padding(if (compact) 32.dp else 56.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            if (!compact) {
                Box(Modifier.size(96.dp).border(1.dp, Hmi.Cyan), contentAlignment = Alignment.Center) {
                    PathIcon(HmiIcons.AUTO, 48.dp, Hmi.Cyan)
                }
            }
            HText("PHONE PROJECTION", size = if (compact) 28.sp else 36.sp, family = Hmi.Display, lineHeight = if (compact) 34.sp else 43.sp)
            val connected = phone.link?.takeIf { it.connected }
            HText(
                if (connected != null) {
                    "${connected.name} is connected. Your phone's maps, calls, messages and media take over this screen while projecting."
                } else {
                    "Connect your phone to bring its maps, calls, messages and media to this screen."
                },
                Modifier.widthIn(max = 600.dp),
                size = if (compact) 18.sp else 20.sp,
                color = Hmi.Muted,
                lineHeight = if (compact) 26.sp else 30.sp,
            )
            val buttonHeight = if (compact) 64.dp else 72.dp
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SolidButton("START PROJECTION", actions::startProjection, Modifier.size(if (compact) 300.dp else 360.dp, buttonHeight))
                GhostButton("CHOOSE APP", { choosingApp = true }, Modifier.height(buttonHeight))
            }
            HText(
                "OPENS · " + (chosen?.label?.uppercase() ?: "AUTOMATIC"),
                size = 14.sp,
                color = Hmi.Muted,
                spacing = 2.sp,
                maxLines = 1,
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).padding(32.dp)) {
                Caption("PAIRED PHONES", Modifier.padding(bottom = 16.dp))
                when {
                    !canSeeBluetooth -> {
                        HText("Revv needs Bluetooth access to see them", size = 18.sp, color = Hmi.Muted)
                        AccentButton("ALLOW", actions::requestBluetoothPermission, Modifier.padding(top = 20.dp).height(52.dp))
                    }
                    phone.pairedPhones.isEmpty() -> HText("None paired over Bluetooth yet", size = 18.sp, color = Hmi.Muted)
                }
                phone.pairedPhones.take(PAIRED_SHOWN).forEach { paired ->
                    Row(
                        Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft).padding(vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(if (paired.connected) Hmi.Cyan else Color.White.copy(alpha = 0.2f)))
                        Column(Modifier.weight(1f)) {
                            HText(paired.name, size = 24.sp, weight = FontWeight.Medium, maxLines = 1)
                            HText(
                                if (paired.connected) "Calls and audio over Bluetooth" else "Not connected",
                                Modifier.padding(top = 4.dp),
                                size = 15.sp,
                                color = Hmi.Muted,
                            )
                        }
                        Caption(if (paired.connected) "CONNECTED" else "PAIRED", color = if (paired.connected) Hmi.Cyan else Hmi.Muted)
                    }
                }
            }
            if (!compact) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Fact("CONNECTION", "Wireless", Modifier.weight(1f))
                    Fact("AUTO START", "On", Modifier.weight(1f), Hmi.Cyan)
                    Fact("USB PORT", "Ready", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun Fact(label: String, value: String, modifier: Modifier, color: Color = Hmi.Text) {
    Column(modifier) {
        Caption(label, size = 14.sp)
        HText(value, Modifier.padding(top = 8.dp), size = 24.sp, color = color)
    }
}

/** As many paired phones as fit above the facts row. */
private const val PAIRED_SHOWN = 4
