package com.vivekkaushik.revv.ui.hmi

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
import com.vivekkaushik.revv.vehicle.DemoData
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.StateFlow

/** Full-screen container for the HMI's apps: a HOME button, the app's title and the clock. */
@Composable
fun AppOverlay(
    app: HmiApp,
    state: HmiUiState,
    live: LiveTelemetry,
    clock: String,
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
    Box(modifier.fillMaxSize().background(Hmi.Bg).blueprintGrid()) {
        Row(
            Modifier.padding(start = 56.dp, end = 56.dp, top = 40.dp).fillMaxWidth().height(44.dp),
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
            HText(app.title.uppercase(), size = 18.sp, color = Hmi.Muted, spacing = 4.sp)
            HText(clock, size = 22.sp, weight = FontWeight.Medium)
        }
        // The bottom 120px belong to the dock, which stays on top of every app.
        Box(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 112.dp, bottom = 160.dp)) {
            screens.SaveableStateProvider(app) {
                when (app) {
                    HmiApp.Phone -> PhoneScreen(actions)
                    HmiApp.Auto -> AutoScreen(actions)
                    HmiApp.Maps -> MapsScreen(state, navigation, timeFormat, actions)
                    HmiApp.Vehicle -> VehicleScreen(live)
                    HmiApp.Camera -> CameraScreen(live)
                    HmiApp.Apps -> AppsScreen(state.apps, actions)
                    HmiApp.Settings -> SettingsScreen(state, actions)
                    HmiApp.Radio -> StubScreen(app.title, "NO RADIO APP FOUND ON THIS HEAD UNIT")
                }
            }
        }
    }
}

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
private fun AutoScreen(actions: HmiActions) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().border(1.dp, Hmi.Line).padding(56.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(96.dp).border(1.dp, Hmi.Cyan), contentAlignment = Alignment.Center) {
                PathIcon(HmiIcons.AUTO, 48.dp, Hmi.Cyan)
            }
            HText("PHONE PROJECTION", size = 36.sp, family = Hmi.Display, lineHeight = 43.sp)
            HText(
                "${DemoData.PHONE} is connected over wireless Android Auto. Your phone's maps, calls, " +
                    "messages and media take over this screen while projecting.",
                Modifier.widthIn(max = 600.dp),
                size = 20.sp,
                color = Hmi.Muted,
                lineHeight = 30.sp,
            )
            SolidButton("START PROJECTION", actions::startProjection, Modifier.padding(top = 8.dp).size(360.dp, 72.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).padding(32.dp)) {
                Caption("PAIRED PHONES", Modifier.padding(bottom = 16.dp))
                DemoData.pairedPhones.forEach { phone ->
                    Row(
                        Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft).padding(vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(if (phone.connected) Hmi.Cyan else Color.White.copy(alpha = 0.2f)))
                        Column(Modifier.weight(1f)) {
                            HText(phone.name, size = 24.sp, weight = FontWeight.Medium)
                            HText(phone.detail, Modifier.padding(top = 4.dp), size = 15.sp, color = Hmi.Muted)
                        }
                        Caption(if (phone.connected) "CONNECTED" else "PAIRED", color = if (phone.connected) Hmi.Cyan else Hmi.Muted)
                    }
                }
            }
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

@Composable
private fun Fact(label: String, value: String, modifier: Modifier, color: Color = Hmi.Text) {
    Column(modifier) {
        Caption(label, size = 14.sp)
        HText(value, Modifier.padding(top = 8.dp), size = 24.sp, color = color)
    }
}
