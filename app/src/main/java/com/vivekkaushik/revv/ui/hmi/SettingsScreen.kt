package com.vivekkaushik.revv.ui.hmi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.obd.BluetoothAccess
import com.vivekkaushik.revv.obd.ObdAdapter
import com.vivekkaushik.revv.obd.ObdLink
import com.vivekkaushik.revv.obd.WifiEndpoint
import com.vivekkaushik.revv.settings.SettingsStore
import java.util.Locale

private enum class Category(val title: String, val icon: String) {
    Display("DISPLAY", HmiIcons.DISPLAY),
    Sound("SOUND", HmiIcons.SOUND),
    Connectivity("CONNECTIVITY", HmiIcons.BLUETOOTH),
    Vehicle("VEHICLE", HmiIcons.VEHICLE),
    Navigation("NAVIGATION", HmiIcons.MAPS),
    System("SYSTEM", HmiIcons.SETTINGS),
}

private sealed interface SettingRow {
    val name: String
    val detail: String
}

private class ToggleRow(val key: String, override val name: String, override val detail: String) : SettingRow

private class LevelRow(
    val key: String,
    override val name: String,
    override val detail: String,
    val value: Int,
    val max: Int,
) : SettingRow

/** The design repeats a read-only row's description as its value. */
private class ValueRow(override val name: String, override val detail: String, val value: String = detail) : SettingRow

private class ActionRow(
    override val name: String,
    override val detail: String,
    val button: String,
    val onClick: () -> Unit,
    /** A quieter second action shown before the main one, e.g. switching adapters. */
    val secondary: Pair<String, () -> Unit>? = null,
) : SettingRow

private val KnobEasing = CubicBezierEasing(0.3f, 1.2f, 0.5f, 1f)

@Composable
fun SettingsScreen(state: HmiUiState, actions: HmiActions) {
    var category by rememberSaveable { mutableStateOf(Category.Display) }
    var choosingAdapter by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val about = remember { About(versionName(context), freeStorage(context)) }
    val chooseAdapter = {
        actions.refreshAdapterChoices()
        choosingAdapter = true
    }
    BackHandler(enabled = choosingAdapter) { choosingAdapter = false }
    var viewingLog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = viewingLog) { viewingLog = false }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.width(420.dp).fillMaxHeight().border(1.dp, Hmi.Line).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Category.entries.forEach { entry ->
                CategoryButton(entry, selected = entry == category) {
                    category = entry
                    choosingAdapter = false
                    viewingLog = false
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().border(1.dp, Hmi.Line).padding(horizontal = 36.dp, vertical = 32.dp)) {
            when {
                choosingAdapter -> AdapterPicker(state, actions, onDone = { choosingAdapter = false })
                viewingLog -> AdapterLog(state.obdLog, onDone = { viewingLog = false })
                else -> {
                    HText(category.title, Modifier.padding(bottom = 16.dp), size = 26.sp, family = Hmi.Display)
                    rowsFor(category, state, actions, about, chooseAdapter, showLog = { viewingLog = true })
                        .forEach { row -> SettingRowView(row, state, actions) }
                }
            }
        }
    }
}

/** Lists the adapters Revv can use: Wi-Fi, each paired Bluetooth device, and a simulator in debug builds. */
@Composable
private fun AdapterPicker(state: HmiUiState, actions: HmiActions, onDone: () -> Unit) {
    val chosen = state.settings.obdAdapter
    val typedAddress = chosen?.wifiEndpoint
    var editingAddress by rememberSaveable { mutableStateOf(false) }
    // Bluetooth LE adapters aren't paired, so look for them while the picker is open.
    LaunchedEffect(state.system.canScanBle, state.system.bluetoothOn) {
        if (state.system.canScanBle && state.system.bluetoothOn) actions.scanForBleAdapters()
    }
    DisposableEffect(Unit) {
        onDispose { actions.stopBleScan() }
    }
    BackHandler(enabled = editingAddress) { editingAddress = false }
    if (editingAddress) {
        AddressEditor(
            initial = typedAddress?.toString().orEmpty(),
            onUse = { endpoint -> choose(ObdAdapter.wifiAt(endpoint), actions, onDone) },
            onCancel = { editingAddress = false },
        )
        return
    }
    val byKind = state.adapterChoices.groupBy { it.kind }
    Column(Modifier.fillMaxSize()) {
        HText("OBD-II ADAPTER", size = 26.sp, family = Hmi.Display)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "wifi") {
                PickerSection(
                    "WI-FI",
                    "Join the adapter's network first (often WiFi_OBDII), and choose Stay connected if " +
                        "Android warns it has no internet. Revv finds the adapter's address itself.",
                )
            }
            items(byKind[ObdAdapter.Kind.WiFi].orEmpty(), key = { it.kind.name + it.address }) { adapter ->
                AdapterRow(adapter, selected = adapter == chosen) { choose(adapter, actions, onDone) }
            }
            item(key = "wifi-address") {
                ChoiceRow(
                    icon = HmiIcons.WIFI,
                    title = "Wi-Fi ELM327 at a set address",
                    detail = typedAddress?.let { "Uses $it" } ?: "For adapters that don't use ${WifiEndpoint.Default}",
                    selected = typedAddress != null,
                    tag = if (typedAddress != null) "IN USE" else null,
                    onClick = { editingAddress = true },
                )
            }
            item(key = "bluetooth") {
                PickerSection("BLUETOOTH", "Pair the adapter in Android Bluetooth settings first. Its PIN is usually 1234 or 0000.")
            }
            if (!state.system.hasBluetoothPermission) {
                item(key = "permission") {
                    SettingRowView(
                        ActionRow("Bluetooth access", "Needed to see paired adapters", "ALLOW", actions::requestBluetoothPermission),
                        state,
                        actions,
                    )
                }
            } else if (!state.system.bluetoothOn) {
                // Android lists no paired devices while Bluetooth is off.
                item(key = "off") {
                    HText("Bluetooth is off.", Modifier.padding(vertical = 12.dp), size = 19.sp, color = Hmi.Muted)
                }
            } else if (byKind[ObdAdapter.Kind.Bluetooth].isNullOrEmpty()) {
                item(key = "none") {
                    HText("No paired devices yet.", Modifier.padding(vertical = 12.dp), size = 19.sp, color = Hmi.Muted)
                }
            }
            items(byKind[ObdAdapter.Kind.Bluetooth].orEmpty(), key = { it.kind.name + it.address }) { adapter ->
                AdapterRow(adapter, selected = adapter == chosen) { choose(adapter, actions, onDone) }
            }
            item(key = "ble") {
                PickerSection(
                    "BLUETOOTH LE",
                    "For BLE adapters such as the Vgate iCar Pro BLE, OBDLink CX and Veepeak BLE+. They don't " +
                        "need pairing: plug the adapter in and Revv finds it here.",
                )
            }
            val bleAdapters = byKind[ObdAdapter.Kind.BluetoothLe].orEmpty()
            item(key = "ble-status") {
                when {
                    !state.system.canScanBle -> SettingRowView(
                        ActionRow("Find BLE adapters", "Needs permission to look for nearby devices", "ALLOW", actions::requestBleScanPermission),
                        state,
                        actions,
                    )
                    !state.system.bluetoothOn -> SettingRowView(
                        ActionRow("Bluetooth is off", "Turn it on to find Bluetooth LE adapters", "OPEN", actions::openBluetoothSettings),
                        state,
                        actions,
                    )
                    state.system.locationBlocksBleScan -> SettingRowView(
                        ActionRow("Location is off", "Android needs it on to find Bluetooth LE devices", "OPEN", actions::openLocationSettings),
                        state,
                        actions,
                    )
                    else -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val status = when {
                            state.bleScanning -> "Searching for Bluetooth LE adapters…"
                            bleAdapters.isEmpty() -> "No Bluetooth LE devices found"
                            else -> "${bleAdapters.size} found"
                        }
                        HText(status, size = 19.sp, color = Hmi.Muted)
                        if (!state.bleScanning) GhostButton("SCAN AGAIN", actions::scanForBleAdapters, Modifier.height(52.dp))
                    }
                }
            }
            items(bleAdapters, key = { it.kind.name + it.address }) { adapter ->
                AdapterRow(adapter, selected = adapter == chosen) { choose(adapter, actions, onDone) }
            }
            val simulated = byKind[ObdAdapter.Kind.Simulated].orEmpty()
            if (simulated.isNotEmpty()) {
                item(key = "testing") { PickerSection("TESTING", "Answers like a real ELM327 from the demo drive.") }
                items(simulated, key = { it.kind.name + it.address }) { adapter ->
                    AdapterRow(adapter, selected = adapter == chosen) { choose(adapter, actions, onDone) }
                }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("JOIN WI-FI", actions::openWifiSettings, Modifier.height(56.dp))
            GhostButton("PAIR BLUETOOTH", actions::openBluetoothSettings, Modifier.height(56.dp))
            if (chosen != null) {
                GhostButton(
                    "FORGET ADAPTER",
                    onClick = {
                        actions.forgetObdAdapter()
                        onDone()
                    },
                    modifier = Modifier.height(56.dp),
                )
            }
            AccentButton("DONE", onDone, Modifier.height(56.dp))
        }
    }
}

/** The adapter conversation, newest last, for working out why a car won't talk. */
@Composable
private fun AdapterLog(lines: List<String>, onDone: () -> Unit) {
    val list = rememberLazyListState()
    // Follows the newest line, like a terminal.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        HText("ADAPTER LOG", size = 26.sp, family = Hmi.Display)
        HText(
            "What Revv and the adapter said on recent connection attempts, also in logcat as RevvObd. With Save adapter " +
                "logs on, it's kept on this device too, one file a day, in Android/data/com.vivekkaushik.revv/files/logs.",
            Modifier.padding(top = 6.dp),
            size = 15.sp,
            color = Hmi.Muted,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp), state = list) {
            if (lines.isEmpty()) {
                item { HText("Nothing yet: Revv logs here once it starts connecting.", size = 16.sp, color = Hmi.Muted) }
            }
            items(lines) { line -> HText(line, size = 14.sp, lineHeight = 22.sp) }
        }
        Row(Modifier.padding(top = 16.dp)) {
            AccentButton("DONE", onDone, Modifier.height(56.dp))
        }
    }
}

private fun choose(adapter: ObdAdapter, actions: HmiActions, onDone: () -> Unit) {
    actions.chooseObdAdapter(adapter)
    onDone()
}

@Composable
private fun PickerSection(title: String, hint: String) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Caption(title)
        HText(hint, Modifier.padding(top = 6.dp), size = 15.sp, color = Hmi.Muted)
    }
}

@Composable
private fun AdapterRow(adapter: ObdAdapter, selected: Boolean, onClick: () -> Unit) {
    val (icon, detail) = when (adapter.kind) {
        ObdAdapter.Kind.Bluetooth, ObdAdapter.Kind.BluetoothLe -> HmiIcons.BLUETOOTH to adapter.address
        ObdAdapter.Kind.WiFi -> HmiIcons.WIFI to "Tries ${WifiEndpoint.Default} and the network's gateway"
        ObdAdapter.Kind.Simulated -> HmiIcons.VEHICLE to null
    }
    val tag = when {
        selected -> "IN USE"
        adapter.kind != ObdAdapter.Kind.WiFi && adapter.kind != ObdAdapter.Kind.Simulated &&
            BluetoothAccess.looksLikeObd(adapter.name) -> "OBD-II?"
        else -> null
    }
    ChoiceRow(icon, adapter.name, detail, selected, tag, onClick)
}

@Composable
private fun ChoiceRow(icon: String, title: String, detail: String?, selected: Boolean, tag: String?, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(76.dp),
        background = if (selected) Hmi.Cyan.copy(alpha = 0.10f) else Color.Transparent,
        pressedBackground = Hmi.CyanTint,
        border = if (selected) Hmi.Cyan else Hmi.Line,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PathIcon(icon, 24.dp, if (selected) Hmi.Cyan else Hmi.Muted)
            Column(Modifier.weight(1f)) {
                HText(title, size = 20.sp, weight = FontWeight.Medium, maxLines = 1)
                if (detail != null) HText(detail, size = 14.sp, color = Hmi.Muted, spacing = 1.sp, maxLines = 1)
            }
            if (tag != null) Caption(tag, color = if (selected) Hmi.Cyan else Hmi.Muted)
        }
    }
}

/** Keypad entry for a Wi-Fi adapter's IP address and port, laid out like the phone dialer. */
@Composable
private fun AddressEditor(initial: String, onUse: (WifiEndpoint) -> Unit, onCancel: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HText("WI-FI ADDRESS", size = 26.sp, family = Hmi.Display)
            HText(
                "The adapter's IP address, then : and its port. Leave the port out and Revv uses " +
                    "${WifiEndpoint.Default.port}. Both are usually printed in the adapter's manual.",
                size = 16.sp,
                color = Hmi.Muted,
            )
            Row(Modifier.fillMaxWidth().height(72.dp).edgeLine(), verticalAlignment = Alignment.CenterVertically) {
                HText(
                    address.ifEmpty { WifiEndpoint.Default.toString() },
                    Modifier.weight(1f),
                    size = 34.sp,
                    color = if (address.isEmpty()) Hmi.Faint else Hmi.Text,
                    family = Hmi.Display,
                    spacing = 2.sp,
                    maxLines = 1,
                )
                Pressable(
                    onClick = {
                        address = address.dropLast(1)
                        invalid = false
                    },
                    modifier = Modifier.size(52.dp),
                    border = null,
                ) {
                    PathIcon(HmiIcons.BACKSPACE, 28.dp, Hmi.Muted, strokeWidth = 1.8f)
                }
            }
            if (invalid) HText("Enter an address like ${WifiEndpoint.Default}", size = 16.sp, color = Hmi.Red)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("CANCEL", onCancel, Modifier.height(56.dp))
                SolidButton(
                    "USE ADDRESS",
                    onClick = { WifiEndpoint.parse(address)?.let(onUse) ?: run { invalid = true } },
                    modifier = Modifier.height(56.dp).width(300.dp),
                )
            }
        }
        KeyPad(
            ADDRESS_KEYS,
            onKey = { key ->
                address = WifiEndpoint.type(address, key)
                invalid = false
            },
            modifier = Modifier.width(480.dp).fillMaxHeight(),
        )
    }
}

private val ADDRESS_KEYS = listOf(
    "1" to "", "2" to "", "3" to "",
    "4" to "", "5" to "", "6" to "",
    "7" to "", "8" to "", "9" to "",
    "." to "DOT", "0" to "", ":" to "PORT",
)

@Composable
private fun CategoryButton(category: Category, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Hmi.Text else Hmi.Muted
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(76.dp),
        background = if (selected) Hmi.Cyan.copy(alpha = 0.10f) else Color.Transparent,
        pressedBackground = Hmi.CyanTint,
        border = if (selected) Hmi.Cyan else null,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PathIcon(category.icon, 24.dp, color)
            HText(category.title, Modifier.weight(1f), size = 18.sp, color = color, spacing = 2.sp)
            HText("›", size = 18.sp, color = color.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun SettingRowView(row: SettingRow, state: HmiUiState, actions: HmiActions) {
    Row(
        Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft).padding(vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f)) {
            HText(row.name, size = 21.sp, weight = FontWeight.Medium)
            HText(row.detail, Modifier.padding(top = 4.dp), size = 15.sp, color = Hmi.Muted)
        }
        when (row) {
            is ToggleRow -> Toggle(state.settings.isOn(row.key)) { actions.toggleSetting(row.key) }
            is LevelRow -> Level(row.value, row.max) { direction -> actions.changeLevel(row.key, direction) }
            is ValueRow -> HText(row.value, size = 19.sp, color = Hmi.Muted)
            is ActionRow -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.secondary?.let { (label, onClick) -> GhostButton(label, onClick, Modifier.height(52.dp)) }
                AccentButton(row.button, row.onClick, Modifier.height(52.dp))
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean, onClick: () -> Unit) {
    val knobOffset by animateDpAsState(if (on) 42.dp else 5.dp, tween(250, easing = KnobEasing), label = "knob")
    Pressable(
        onClick = onClick,
        modifier = Modifier.size(76.dp, 40.dp),
        background = if (on) Hmi.CyanTint else Color.Transparent,
        pressedBackground = if (on) Hmi.CyanTint else Color.Transparent,
        border = if (on) Hmi.Cyan else Color.White.copy(alpha = 0.2f),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset, y = 5.dp)
                .size(28.dp)
                .background(if (on) Hmi.Cyan else Hmi.Muted),
        )
    }
}

@Composable
private fun Level(value: Int, max: Int, onStep: (Int) -> Unit) {
    val lit = if (max > 0) Math.round(value * 10f / max) else 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Pressable(onClick = { onStep(-1) }, Modifier.size(52.dp)) { HText("−", size = 24.sp) }
        Row(Modifier.size(220.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(10) { cell ->
                Box(Modifier.weight(1f).fillMaxHeight().background(if (cell < lit) Hmi.Cyan else Hmi.Line))
            }
        }
        Pressable(onClick = { onStep(1) }, Modifier.size(52.dp)) { HText("+", size = 24.sp) }
        HText(value.toString(), Modifier.width(56.dp), size = 20.sp, align = TextAlign.End)
    }
}

private class About(val version: String, val freeStorage: String)

private fun rowsFor(
    category: Category,
    state: HmiUiState,
    actions: HmiActions,
    about: About,
    chooseAdapter: () -> Unit,
    showLog: () -> Unit,
): List<SettingRow> {
    val settings = state.settings
    val system = state.system
    return when (category) {
        Category.Display -> listOf(
            LevelRow(SettingsStore.BRIGHTNESS, "Brightness", "Screen backlight", settings.level(SettingsStore.BRIGHTNESS), 100),
            ToggleRow("night", "Auto night mode", "Dim with ambient light sensor"),
            ToggleRow(SettingsStore.REDUCED_MOTION, "Reduced motion", "Fewer animations while driving"),
            ValueRow("Theme", "Cluster · dark"),
        )
        Category.Sound -> listOf(
            LevelRow(SettingsStore.MEDIA_VOLUME, "Media volume", "Spotify, radio, phone", system.mediaVolume, system.mediaVolumeMax),
            LevelRow(SettingsStore.NAV_VOLUME, "Navigation volume", "Turn prompts", settings.level(SettingsStore.NAV_VOLUME), 30),
            ToggleRow("autoVol", "Speed-sensitive volume", "Raise volume with road noise"),
            ToggleRow(SettingsStore.TOUCH_FEEDBACK, "Touch feedback", "Click on every tap"),
        )
        Category.Connectivity -> listOf(
            ActionRow("Bluetooth", if (system.bluetoothOn) "On" else "Off", "OPEN", actions::openBluetoothSettings),
            ActionRow("Wi-Fi", "Networks and hotspots", "OPEN", actions::openWifiSettings),
            ToggleRow("hotspot", "Phone hotspot", "Use phone data for maps"),
            pairedDevicesRow(system, actions),
        )
        Category.Vehicle -> listOfNotNull(
            adapterRow(state, actions, chooseAdapter),
            settings.obdAdapter?.let { ActionRow("Adapter log", "What the adapter said, for when a car won't connect", "VIEW", showLog) },
            settings.obdAdapter?.let { ToggleRow(SettingsStore.SAVE_OBD_LOG, "Save adapter logs", "One file a day on this device, for troubleshooting later") },
            ToggleRow(SettingsStore.DEMO_DRIVE, "Demo drive", "Simulated car data while no OBD-II adapter is set up"),
            ValueRow("Model", "Swift VXi 2015 · 5MT"),
            ToggleRow("tpms", "Tyre pressure alerts", "Warn below 28 psi"),
            ToggleRow("shiftL", "Shift lights", "Above 4,000 rpm"),
        )
        Category.Navigation -> listOfNotNull(
            when {
                !system.hasLocationPermission ->
                    ActionRow("Location", "Needed to show the car on the map", "ALLOW", actions::requestLocationPermission)
                !system.locationOn -> ActionRow("Location", "Switched off on this head unit", "OPEN", actions::openLocationSettings)
                else -> ValueRow("Location", "The map follows this head unit's GPS", "Allowed")
            },
            ToggleRow(SettingsStore.AVOID_TOLLS, "Avoid tolls", "Prefer free roads"),
            // Kept from the design, but OpenStreetMap routing has no traffic feed to use.
            ToggleRow("traffic", "Live traffic", "Not available yet · routes use typical speeds"),
            ValueRow("Units", "Kilometres"),
            ValueRow("Map style", "Wireframe"),
            ValueRow("Map data", "OpenStreetMap contributors · OpenFreeMap · Valhalla · Photon", "OpenStreetMap"),
            state.recentPlaces.takeIf { it.isNotEmpty() }?.let { places ->
                ActionRow("Recent places", "${places.size} kept on this head unit", "CLEAR", actions::clearRecentPlaces)
            },
        )
        Category.System -> listOf(
            if (system.isDefaultHome) {
                ValueRow("Home app", "Opens when you press home", "Revv")
            } else {
                ActionRow("Home app", "Revv isn't the default yet", "SET", actions::requestDefaultHome)
            },
            if (system.hasMediaAccess) {
                ValueRow("Media access", "Now playing on the home screen", "Allowed")
            } else {
                ActionRow("Media access", "Needed for the media card", "ALLOW", actions::requestMediaAccess)
            },
            ActionRow("Android settings", "Apps, display, network", "OPEN", actions::openSystemSettings),
            ValueRow("Software", "Revv ${about.version}"),
            ValueRow("Storage", "${about.freeStorage} free"),
        )
    }
}

/** The OBD-II adapter's connection state, with the one action that moves it forward. */
private fun adapterRow(state: HmiUiState, actions: HmiActions, chooseAdapter: () -> Unit): SettingRow {
    val adapter = state.settings.obdAdapter ?: return ActionRow(
        "OBD-II adapter",
        "Not set up · pair an ELM327, then choose it here",
        "CHOOSE",
        chooseAdapter,
    )
    val status = state.obd
    val detail = when (status.link) {
        ObdLink.Off -> adapter.name
        ObdLink.NeedsPermission -> "Allow Bluetooth access to connect"
        ObdLink.BluetoothOff -> "Bluetooth is off"
        ObdLink.NoWifi -> "Join the adapter's Wi-Fi network first"
        ObdLink.Connecting -> "Connecting to ${adapter.wifiEndpoint ?: adapter.name}…"
        ObdLink.NoEcu -> "Connected · car not answering, ignition off?" +
            (status.batteryVolts?.let { " · %.1f V".format(Locale.ROOT, it) } ?: "")
        ObdLink.Live -> listOfNotNull("Live", status.protocol, status.endpoint).joinToString(" · ")
        ObdLink.Retrying -> "Reconnecting · ${status.problem ?: "link lost"}"
    }
    val name = "OBD-II · ${adapter.name}"
    return when (status.link) {
        // Fixing the link comes first, but switching adapters must stay possible too.
        ObdLink.NeedsPermission -> ActionRow(name, detail, "ALLOW", actions::requestBluetoothPermission, "CHANGE" to chooseAdapter)
        ObdLink.BluetoothOff -> ActionRow(name, detail, "OPEN", actions::openBluetoothSettings, "CHANGE" to chooseAdapter)
        ObdLink.NoWifi -> ActionRow(name, detail, "JOIN", actions::openWifiSettings, "CHANGE" to chooseAdapter)
        else -> ActionRow(name, detail, "CHANGE", chooseAdapter)
    }
}

/** How many devices are paired, or what keeps Revv from counting them. */
private fun pairedDevicesRow(system: SystemState, actions: HmiActions): SettingRow {
    val count = system.pairedDevices
    return when {
        !system.hasBluetoothPermission ->
            ActionRow("Paired devices", "Allow Bluetooth access to see them", "ALLOW", actions::requestBluetoothPermission)
        count == null -> ValueRow("Paired devices", "Bluetooth is off", "—")
        else -> ValueRow("Paired devices", if (count == 1) "1 device" else "$count devices")
    }
}

private fun versionName(context: Context): String {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return info.versionName.orEmpty()
}

private fun freeStorage(context: Context): String =
    Formatter.formatShortFileSize(context, StatFs(context.filesDir.path).availableBytes)
