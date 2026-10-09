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
            // The Auto screen is named for the phone it shows.
            val projection = app == HmiApp.Auto
            val androidAuto = state.settings.androidAutoChosen
            val title = when {
                !projection -> app.title.uppercase()
                androidAuto -> "ANDROID AUTO"
                else -> "CARPLAY"
            }
            HText(title, size = 18.sp, color = Hmi.Muted, spacing = 4.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // The projection's full screen and settings, kept out of the phone's picture.
                if (projection) {
                    Pressable({ actions.setAutoFullScreen(true) }, Modifier.size(44.dp)) {
                        PathIcon(HmiIcons.FULL_SCREEN, 22.dp, Hmi.Muted, strokeWidth = 2f)
                    }
                    Pressable(if (androidAuto) actions::openAndroidAutoSettings else actions::openCarPlaySettings, Modifier.size(44.dp)) {
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
            app == HmiApp.Maps || app == HmiApp.Auto -> MAPS_BOTTOM_MARGIN
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
    // The projection runs inside this screen, unless it is full screen over all of Revv (see
    // HmiRoot): one view at a time.
    if (state.screen.autoFullScreen) return
    if (state.settings.androidAutoChosen) {
        AndroidAutoAutoScreen(
            state.androidAuto,
            actions,
            wide = state.settings.isOn(SettingsStore.ANDROID_AUTO_WIDE),
            projectionLabel = chosen?.label,
            onChooseApp = { choosingApp = true },
            // The driver may keep Android Auto's settings beside it instead of its status and controls.
            settings = if (state.settings.isOn(SettingsStore.ANDROID_AUTO_SETTINGS_BESIDE)) {
                { modifier -> AndroidAutoSettings(state, actions, modifier, title = "SETTINGS", besideAndroidAuto = true) }
            } else {
                null
            },
            androidAutoRight = state.settings.isOn(SettingsStore.ANDROID_AUTO_ON_RIGHT),
        )
        return
    }
    CarPlayAutoScreen(
        state.carPlay,
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
}

