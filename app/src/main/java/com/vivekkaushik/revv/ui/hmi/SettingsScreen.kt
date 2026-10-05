package com.vivekkaushik.revv.ui.hmi

import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.vivekkaushik.revv.nav.GoogleApiKey
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.net.Uri
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vivekkaushik.revv.engine.EngineLayout
import com.vivekkaushik.revv.engine.EngineSoundSettings
import com.vivekkaushik.revv.engine.ExhaustNote
import com.vivekkaushik.revv.obd.BluetoothAccess
import com.vivekkaushik.revv.obd.ObdAdapter
import com.vivekkaushik.revv.obd.ObdLink
import com.vivekkaushik.revv.obd.WifiEndpoint
import com.vivekkaushik.revv.settings.HmiSettings
import com.vivekkaushik.revv.settings.SettingsStore
import com.vivekkaushik.revv.system.NightMode
import com.vivekkaushik.revv.system.NightSchedule
import com.vivekkaushik.revv.vehicle.CarColour
import com.vivekkaushik.revv.system.CarPlayCompanion
import com.vivekkaushik.revv.vehicle.CarSetup
import com.vivekkaushik.revv.vehicle.GearSource
import com.vivekkaushik.revv.vehicle.TyreSize
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Category(val title: String, val icon: String) {
    Display("DISPLAY", HmiIcons.DISPLAY),
    Home("HOME", HmiIcons.HOME),
    Sound("SOUND", HmiIcons.SOUND),
    Connectivity("CONNECTIVITY", HmiIcons.BLUETOOTH),
    CarPlay("CARPLAY", HmiIcons.AUTO),
    Vehicle("VEHICLE", HmiIcons.VEHICLE),
    Navigation("NAVIGATION", HmiIcons.MAPS),
    System("SYSTEM", HmiIcons.SETTINGS),
}

private sealed interface SettingRow {
    val name: String
    val detail: String
    /** A short tag beside the name, e.g. "EXPERIMENTAL". */
    val badge: String? get() = null
}

private class ToggleRow(
    val key: String,
    override val name: String,
    override val detail: String,
    override val badge: String? = null,
) : SettingRow

private class LevelRow(
    val key: String,
    override val name: String,
    override val detail: String,
    val value: Int,
    val max: Int,
) : SettingRow

/** A whole number stepped within [range], e.g. how many gears. */
private class StepperRow(
    override val name: String,
    override val detail: String,
    val value: Int,
    val range: IntRange,
    val step: Int = 1,
    /** Whether holding − or + keeps stepping. */
    val holdToRepeat: Boolean = true,
    val onChange: (Int) -> Unit,
) : SettingRow

/** The design repeats a read-only row's description as its value. */
private class ValueRow(override val name: String, override val detail: String, val value: String = detail) : SettingRow

/** A setting with a short list of named options, stepped with − and +. */
private class OptionRow(
    override val name: String,
    override val detail: String,
    val options: List<String>,
    val selected: Int,
    val onChange: (Int) -> Unit,
) : SettingRow

/** An on/off setting kept outside Revv's own settings, e.g. one of RevvCarPlay's. */
private class SwitchRow(override val name: String, override val detail: String, val on: Boolean, val onToggle: (Boolean) -> Unit) : SettingRow

/** A caption over the rows that follow, with an optional note beside it. */
private class HeaderRow(override val name: String, override val detail: String = "") : SettingRow

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
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = ClockFormats.is24Hour(state.settings, DateFormat.is24HourFormat(context))
    val clock = remember(is24Hour, locale) { DateTimeFormatter.ofPattern(ClockFormats.timePattern(is24Hour), locale) }
    val chooseAdapter = {
        actions.refreshAdapterChoices()
        choosingAdapter = true
    }
    BackHandler(enabled = choosingAdapter) { choosingAdapter = false }
    var viewingLog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = viewingLog) { viewingLog = false }
    var editingCar by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editingCar) { editingCar = false }
    var settingUpGears by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingUpGears) { settingUpGears = false }
    var viewingLicenses by rememberSaveable { mutableStateOf(false) }
    var choosingFuelApp by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = choosingFuelApp) { choosingFuelApp = false }
    var editingGoogleKey by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editingGoogleKey) { editingGoogleKey = false }
    // The Auto screen sends a driver here to finish CarPlay's setup.
    LaunchedEffect(state.screen.carPlaySettingsRequested) {
        if (state.screen.carPlaySettingsRequested) {
            category = Category.CarPlay
            actions.carPlaySettingsShown()
        }
    }
    // A page of CarPlay's own (car hotspot, iPhone) is open.
    var carPlayPage by remember { mutableStateOf(false) }
    // Compact (display sizes above 130%): a narrower category list, hidden while a page such as an
    // editor is open, so the page gets the whole width; back closes it as before.
    val compact = LocalCompact.current
    val pageOpen = choosingAdapter || viewingLog || editingCar || settingUpGears || viewingLicenses || choosingFuelApp || editingGoogleKey ||
        (category == Category.CarPlay && carPlayPage)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 24.dp else 28.dp)) {
        if (!compact || !pageOpen) {
            Column(
                Modifier
                    .width(if (compact) 300.dp else 420.dp)
                    .fillMaxHeight()
                    .border(1.dp, Hmi.Line)
                    .verticalScroll(rememberScrollState())
                    .padding(if (compact) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // CarPlay settings exist only with the companion app installed.
                Category.entries.filter { it != Category.CarPlay || state.carPlay != null }.forEach { entry ->
                    CategoryButton(entry, selected = entry == category) {
                        category = entry
                        choosingAdapter = false
                        viewingLog = false
                        editingCar = false
                        settingUpGears = false
                        choosingFuelApp = false
                        viewingLicenses = false
                        editingGoogleKey = false
                    }
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Hmi.Line)
                .padding(horizontal = if (compact) 28.dp else 36.dp, vertical = if (compact) 24.dp else 32.dp),
        ) {
            when {
                choosingAdapter -> AdapterPicker(state, actions, onDone = { choosingAdapter = false })
                viewingLog -> AdapterLog(state.obdLog, onDone = { viewingLog = false })
                editingCar -> CarPanel(state, actions, onDone = { editingCar = false })
                settingUpGears -> GearIndicatorPanel(state, actions, onDone = { settingUpGears = false })
                viewingLicenses -> OpenSourceLicenses(onDone = { viewingLicenses = false })
                choosingFuelApp -> FuelWidgetPicker(state, actions, onDone = { choosingFuelApp = false })
                editingGoogleKey -> ApiKeyEditor(
                    initial = state.settings.googleApiKey.orEmpty(),
                    onCancel = { editingGoogleKey = false },
                    onSave = { key ->
                        actions.setGoogleApiKey(key)
                        editingGoogleKey = false
                    },
                )
                category == Category.CarPlay && state.carPlay != null ->
                    CarPlaySettings(state, actions, Modifier.weight(1f), title = category.title, onPageChange = { carPlayPage = it })
                else -> {
                    HText(category.title, Modifier.padding(bottom = 16.dp), size = 26.sp, family = Hmi.Display)
                    val rows = rowsFor(
                        category,
                        state,
                        actions,
                        about,
                        clock,
                        chooseAdapter,
                        showLog = { viewingLog = true },
                        editCar = { editingCar = true },
                        setUpGears = { settingUpGears = true },
                        chooseFuelApp = { choosingFuelApp = true },
                        showLicenses = { viewingLicenses = true },
                        editGoogleKey = { editingGoogleKey = true },
                    )
                    // Vehicle has more rows than fit.
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(rows) { row -> SettingRowView(row, state, actions) }
                    }
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
                    repeatEveryMillis = 70,
                    sound = UiSound.DialDelete,
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
        modifier = Modifier.fillMaxWidth().height(if (LocalCompact.current) 64.dp else 76.dp),
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
    if (row is HeaderRow) {
        Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(row.name, Modifier.weight(1f), color = Hmi.Cyan)
            if (row.detail.isNotEmpty()) Caption(row.detail, size = 13.sp)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // In a narrow list, e.g. beside CarPlay, wide controls go under the text instead of squeezing it.
        val wideControl = row is LevelRow || row is OptionRow || row is ActionRow || row is ValueRow
        if (wideControl && maxWidth < STACK_ROWS_BELOW) {
            Column(
                Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft).padding(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RowText(row)
                RowControl(row, state, actions)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft).padding(vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                RowText(row, Modifier.weight(1f))
                RowControl(row, state, actions)
            }
        }
    }
}

/** Below this width a settings list puts wide controls under their text. */
private val STACK_ROWS_BELOW = 560.dp

@Composable
private fun RowText(row: SettingRow, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // The badge keeps its width; in a narrow list the name wraps instead.
            HText(row.name, Modifier.weight(1f, fill = false), size = 21.sp, weight = FontWeight.Medium)
            row.badge?.let { badge ->
                Caption(
                    badge,
                    Modifier.border(1.dp, Hmi.Amber.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Hmi.Amber,
                    size = 12.sp,
                    maxLines = 1,
                )
            }
        }
        HText(row.detail, Modifier.padding(top = 4.dp), size = 15.sp, color = Hmi.Muted)
    }
}

@Composable
private fun RowControl(row: SettingRow, state: HmiUiState, actions: HmiActions) {
    when (row) {
        is ToggleRow -> Toggle(state.settings.isOn(row.key)) { actions.toggleSetting(row.key) }
        is SwitchRow -> Toggle(row.on) { row.onToggle(!row.on) }
        is HeaderRow -> Unit
        is LevelRow -> Level(row.value, row.max) { direction -> actions.changeLevel(row.key, direction) }
        is StepperRow -> Stepper(row.value, row.range, row.step, row.holdToRepeat, row.onChange)
        is ValueRow -> HText(row.value, size = 19.sp, color = Hmi.Muted)
        is OptionRow -> OptionStepper(row.options, row.selected, row.onChange)
        is ActionRow -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.secondary?.let { (label, onClick) -> GhostButton(label, onClick, Modifier.height(52.dp)) }
            AccentButton(row.button, row.onClick, Modifier.height(52.dp))
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
        Pressable(onClick = { onStep(-1) }, Modifier.size(52.dp), repeatEveryMillis = 120) { HText("−", size = 24.sp) }
        // Shorter in a narrow list.
        Row(Modifier.weight(1f, fill = false).width(220.dp).height(14.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(10) { cell ->
                Box(Modifier.weight(1f).fillMaxHeight().background(if (cell < lit) Hmi.Cyan else Hmi.Line))
            }
        }
        Pressable(onClick = { onStep(1) }, Modifier.size(52.dp), repeatEveryMillis = 120) { HText("+", size = 24.sp) }
        HText(value.toString(), Modifier.width(56.dp), size = 20.sp, align = TextAlign.End)
    }
}

@Composable
private fun OptionStepper(options: List<String>, selected: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val canLower = selected > 0
        val canRaise = selected < options.lastIndex
        Pressable(onClick = { if (canLower) onChange(selected - 1) }, Modifier.size(52.dp)) {
            HText("<", size = 24.sp, color = if (canLower) Hmi.Text else Hmi.Faint)
        }
        // Narrower in a narrow list.
        HText(
            options.getOrElse(selected) { "" },
            Modifier.weight(1f, fill = false).width(260.dp),
            size = 18.sp,
            family = Hmi.Display,
            align = TextAlign.Center,
            maxLines = 1,
        )
        Pressable(onClick = { if (canRaise) onChange(selected + 1) }, Modifier.size(52.dp)) {
            HText(">", size = 24.sp, color = if (canRaise) Hmi.Text else Hmi.Faint)
        }
    }
}

@Composable
private fun Stepper(value: Int, range: IntRange, step: Int, holdToRepeat: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val canLower = value > range.first
        val canRaise = value < range.last
        Pressable(onClick = { if (canLower) onChange(value - step) }, Modifier.size(52.dp), repeatEveryMillis = if (holdToRepeat) 250 else 0) {
            HText("−", size = 24.sp, color = if (canLower) Hmi.Text else Hmi.Faint)
        }
        HText(value.toString(), Modifier.width(72.dp), size = 24.sp, family = Hmi.Display, align = TextAlign.Center)
        Pressable(onClick = { if (canRaise) onChange(value + step) }, Modifier.size(52.dp), repeatEveryMillis = if (holdToRepeat) 250 else 0) {
            HText("+", size = 24.sp, color = if (canRaise) Hmi.Text else Hmi.Faint)
        }
    }
}

private class About(val version: String, val freeStorage: String)

private fun rowsFor(
    category: Category,
    state: HmiUiState,
    actions: HmiActions,
    about: About,
    clock: DateTimeFormatter,
    chooseAdapter: () -> Unit,
    showLog: () -> Unit,
    editCar: () -> Unit,
    setUpGears: () -> Unit,
    chooseFuelApp: () -> Unit,
    showLicenses: () -> Unit,
    editGoogleKey: () -> Unit,
): List<SettingRow> {
    val settings = state.settings
    val system = state.system
    return when (category) {
        Category.Display -> listOf(
            brightnessRow(state, actions),
            StepperRow("Display size", "Scale of everything on screen, in percent. Above 130, a compact layout with fewer panels", settings.displaySize, SettingsStore.DISPLAY_SIZES.first()..SettingsStore.DISPLAY_SIZES.last(), step = 10, holdToRepeat = false, onChange = actions::setDisplaySize),
            OptionRow("Time format", "Clock on the home screen and trip times", ClockFormats.timeLabels, settings.timeFormat) {
                actions.setChoice(SettingsStore.TIME_FORMAT, it)
            },
            OptionRow("Date format", "Date on the home screen", ClockFormats.dateLabels, settings.dateFormat) {
                actions.setChoice(SettingsStore.DATE_FORMAT, it)
            },
            autoNightRow(state, actions, clock),
            ToggleRow(SettingsStore.REDUCED_MOTION, "Reduced motion", "Fewer animations while driving"),
            ValueRow("Theme", "Cluster · dark"),
        )
        Category.Home -> listOf(
            ToggleRow(SettingsStore.HOME_FUEL, "Fuel widget", "Fuel range, or the app chosen below"),
            fuelWidgetRow(state, actions, chooseFuelApp),
            ToggleRow(SettingsStore.HOME_PHONE, "Phone card", "Last call and call back"),
            ToggleRow(SettingsStore.HOME_MEDIA, "Media card", "Now playing"),
            ToggleRow(SettingsStore.HOME_MAP, "Map panel", "Navigation beside the cluster"),
        )
        Category.Sound -> listOf(
            LevelRow(SettingsStore.MEDIA_VOLUME, "Media volume", "Spotify, radio, phone", system.mediaVolume, system.mediaVolumeMax),
            LevelRow(SettingsStore.NAV_VOLUME, "Navigation volume", "Turn prompts", settings.level(SettingsStore.NAV_VOLUME), 30),
            ToggleRow("autoVol", "Speed-sensitive volume", "Raise volume with road noise"),
            ToggleRow(SettingsStore.TOUCH_FEEDBACK, "Touch feedback", "Click on every tap"),
            ToggleRow(SettingsStore.MENU_SOUND, "Menu sounds", "A short blip when you tap a button"),
            ToggleRow(SettingsStore.DIALER_SOUND, "Dialer sounds", "A touch-tone for each key on the number pad"),
            ToggleRow(SettingsStore.KEYBOARD_SOUND, "Keyboard sounds", "A soft tick for each key on the keyboard"),
        ) + engineSoundRows(settings, actions)
        Category.Connectivity -> listOf(
            ActionRow("Bluetooth", if (system.bluetoothOn) "On" else "Off", "OPEN", actions::openBluetoothSettings),
            ActionRow("Wi-Fi", "Networks and hotspots", "OPEN", actions::openWifiSettings),
            ToggleRow("hotspot", "Phone hotspot", "Use phone data for maps"),
            pairedDevicesRow(system, actions),
        )
        // Shown by CarPlaySettings, which keeps its own pages.
        Category.CarPlay -> emptyList()
        Category.Vehicle -> listOfNotNull(
            adapterRow(state, actions, chooseAdapter),
            settings.obdAdapter?.let { ActionRow("Adapter log", "What the adapter said, for when a car won't connect", "VIEW", showLog) },
            settings.obdAdapter?.let { ToggleRow(SettingsStore.SAVE_OBD_LOG, "Save adapter logs", "One file a day on this device, for troubleshooting later") },
            ToggleRow(SettingsStore.DEMO_DRIVE, "Demo drive", "Simulated car data while no OBD-II adapter is set up"),
            ActionRow("Car", "${settings.car.name} · ${settings.car.colour.label} · ${settings.car.gears} gears", "EDIT", editCar),
            ActionRow("Gear indicator", gearIndicatorDetail(settings.car, state.learntGears), "SET UP", setUpGears),
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
            OptionRow("Search and routes", placeSearchDetail(settings), PLACE_SEARCHES, settings.placeSearch) {
                actions.setChoice(SettingsStore.PLACE_SEARCH, it)
            },
            googleKeyRow(settings, actions, editGoogleKey),
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
            ActionRow("Open source licenses", "Libraries, fonts and map data Revv is built with", "VIEW", showLicenses),
            ValueRow("Storage", "${about.freeStorage} free"),
        )
    }
}

/**
 * Settings › CarPlay, with its own pages (car hotspot details, the iPhone): shown in Settings, and
 * beside CarPlay on the Auto screen when the driver wants it there ([besideCarPlay]). [title] heads
 * the list; [onPageChange] hears whether one of the pages is open.
 */
@Composable
fun CarPlaySettings(
    state: HmiUiState,
    actions: HmiActions,
    modifier: Modifier = Modifier,
    title: String? = null,
    besideCarPlay: Boolean = false,
    onPageChange: (Boolean) -> Unit = {},
) {
    val companion = state.carPlay ?: return
    val context = LocalContext.current
    var editingHotspot by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = editingHotspot) { editingHotspot = false }
    var choosingIPhone by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = choosingIPhone) { choosingIPhone = false }
    // Removing CarPlay's identity takes a second tap: CarPlay can't start again until another is imported.
    var confirmingIdentityRemoval by rememberSaveable { mutableStateOf(false) }
    val carPlay by companion.state.collectAsState()
    val settings by companion.settings.collectAsState()
    val refused by companion.settingsRefused.collectAsState()
    // Permissions are granted on Android's pages for RevvCarPlay; read the settings again on return.
    LifecycleResumeEffect(companion) {
        companion.refreshSettings()
        onPauseOrDispose { }
    }
    val page = editingHotspot || choosingIPhone
    val currentOnPageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(page) { currentOnPageChange(page) }
    DisposableEffect(Unit) { onDispose { currentOnPageChange(false) } }
    BoxWithConstraints(modifier) {
        // Beside CarPlay the column can be too small for the hotspot editor; it opens in Settings then.
        val roomForEditor = maxWidth >= HOTSPOT_EDITOR_MIN_WIDTH || maxHeight >= STACKED_EDITOR_HEIGHT
        Column(Modifier.fillMaxSize()) {
            when {
                editingHotspot -> HotspotEditor(
                    initialName = settings?.hotspotSsid.orEmpty(),
                    onCancel = { editingHotspot = false },
                    onSave = { name, password ->
                        companion.saveHotspot(name, password)
                        editingHotspot = false
                    },
                )
                choosingIPhone -> IPhonePicker(
                    state,
                    actions,
                    chosen = settings?.phoneAddress,
                    onChoose = { address, name -> companion.choosePhone(address, name) },
                    onDone = { choosingIPhone = false },
                )
                else -> {
                    title?.let { HText(it, Modifier.padding(bottom = 16.dp), size = 26.sp, family = Hmi.Display) }
                    val rows = carPlayRows(
                        state, actions, carPlay, settings, refused,
                        openCarPlay = { CarPlayCompanion.launchIntent(context)?.let(context::startActivity) },
                        openCarPlayAppInfo = { CarPlayCompanion.appInfoIntent(context)?.let(context::startActivity) },
                        openCarPlayWriteSettings = { CarPlayCompanion.writeSettingsIntent(context)?.let(context::startActivity) },
                        editHotspot = { if (besideCarPlay && !roomForEditor) actions.openCarPlaySettings() else editingHotspot = true },
                        chooseIPhone = { choosingIPhone = true },
                        confirmingIdentityRemoval = confirmingIdentityRemoval,
                        setConfirmingIdentityRemoval = { confirmingIdentityRemoval = it },
                    )
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(rows) { row -> SettingRowView(row, state, actions) }
                    }
                }
            }
        }
    }
}

/**
 * Settings › CarPlay: the session, then RevvCarPlay's own settings, which the companion keeps no
 * screen for. Display and audio changes reconnect a running session, a moment after the last one.
 */
private fun carPlayRows(
    state: HmiUiState,
    actions: HmiActions,
    carPlay: CarPlayCompanion.State?,
    settings: CarPlayCompanion.Settings?,
    refused: Boolean,
    openCarPlay: () -> Unit,
    openCarPlayAppInfo: () -> Unit,
    openCarPlayWriteSettings: () -> Unit,
    editHotspot: () -> Unit,
    chooseIPhone: () -> Unit,
    confirmingIdentityRemoval: Boolean,
    setConfirmingIdentityRemoval: (Boolean) -> Unit,
): List<SettingRow> {
    val companion = state.carPlay
    val session = carPlay as? CarPlayCompanion.State.Session
    val top = listOfNotNull(
        ValueRow("Status", carPlay?.explanation() ?: "Open the Auto screen to connect", carPlay?.headline() ?: "—"),
        when (session?.phase) {
            null -> null
            // Identity and hotspot details are set below; only the companion can ask for its permissions and VPN.
            CarPlayCompanion.PHASE_SETUP_REQUIRED -> if (session.missing.any { it in COMPANION_PROMPTS }) {
                ActionRow("Session", "Allow RevvCarPlay's one-time prompts in the companion app", "FINISH SETUP", openCarPlay)
            } else {
                null
            }
            CarPlayCompanion.PHASE_IDLE, CarPlayCompanion.PHASE_FAILED ->
                ActionRow("Session", "Start CarPlay again; the Auto screen shows it", "CONNECT", onClick = { companion?.retry() })
            else -> ActionRow("Session", "End the CarPlay connection", "DISCONNECT", onClick = { companion?.stop() })
        },
        ToggleRow(SettingsStore.CARPLAY_WIDE, "Wide screen", "CarPlay fills the Auto screen; its status and link controls stay here"),
        ToggleRow(SettingsStore.CARPLAY_SETTINGS_BESIDE, "Settings beside CarPlay", "Without wide screen, the Auto screen's side column shows these settings"),
        ToggleRow(SettingsStore.CARPLAY_ON_RIGHT, "CarPlay on the right", "Without wide screen, CarPlay sits right of its side column instead of left"),
        ToggleRow(
            SettingsStore.CARPLAY_FOLLOW_ROUTE,
            "Follow CarPlay's route",
            "Revv's map routes to where CarPlay guides, found by the destination's name with Navigation's place search; " +
                "without a match it shows CarPlay's turns",
            badge = "EXPERIMENTAL",
        ),
        // Without following, CarPlay's own turns may still show, with no route of Revv's.
        ToggleRow(
            SettingsStore.CARPLAY_SHOW_TURNS,
            "Show CarPlay's turns",
            "Revv's map shows CarPlay's next turn and ETA, without looking the place up or routing itself",
            badge = "EXPERIMENTAL",
        ).takeUnless { state.settings.isOn(SettingsStore.CARPLAY_FOLLOW_ROUTE) },
    )
    if (settings == null) {
        return top + if (refused) {
            ValueRow("RevvCarPlay settings", "RevvCarPlay takes settings only from a Revv signed with the same key", "Unavailable")
        } else {
            ValueRow("RevvCarPlay settings", "Waiting for RevvCarPlay", "—")
        }
    }
    fun set(name: String, value: Int) = companion?.setSetting(name, value)
    fun set(name: String, value: Boolean) = companion?.setSetting(name, value)
    val link = when {
        !settings.wireless -> 0
        settings.hotspotMode == CarPlayCompanion.HOTSPOT_MANUAL -> 2
        else -> 1
    }
    return top + listOfNotNull(
        HeaderRow("SETUP"),
        when {
            settings.identityInstalled && confirmingIdentityRemoval -> ActionRow(
                "Remove identity?",
                "CarPlay won't start again until another is imported",
                "REMOVE",
                onClick = {
                    companion?.removeIdentity()
                    setConfirmingIdentityRemoval(false)
                },
                secondary = "KEEP" to { setConfirmingIdentityRemoval(false) },
            )
            settings.identityInstalled -> ActionRow("Identity", "Installed · the iPhone can authenticate this head unit", "REPLACE", actions::importCarPlayIdentity,
                secondary = "REMOVE" to { setConfirmingIdentityRemoval(true) })
            else ->
                ActionRow("Identity", "None installed · pick identity.pk8 and certificate.p7b", "IMPORT", actions::importCarPlayIdentity)
        },
        ActionRow("Permissions", "Nearby devices, location and microphone, granted to RevvCarPlay", "OPEN", openCarPlayAppInfo),
        HeaderRow("CONNECTION"),
        OptionRow("Link", "How the iPhone connects; a running session reconnects over the new link", listOf("USB", "Wi-Fi Direct", "Car hotspot"), link) { index ->
            when (index) {
                0 -> companion?.configure(wireless = false)
                1 -> companion?.configure(wireless = true, hotspotMode = CarPlayCompanion.HOTSPOT_P2P)
                else -> companion?.configure(wireless = true, hotspotMode = CarPlayCompanion.HOTSPOT_MANUAL)
            }
        },
        ActionRow(
            "Car hotspot",
            if (settings.hotspotReady) "${settings.hotspotSsid} · for the Car hotspot link" else "Name and password not saved yet",
            if (settings.hotspotReady) "EDIT" else "SET UP",
            editHotspot,
        ),
        // Over the car hotspot, RevvCarPlay switches the head unit's hotspot on when it may.
        when {
            link != 2 -> null
            !settings.hotspotSwitchAllowed -> ActionRow(
                "Hotspot switch",
                "Let RevvCarPlay turn this head unit's hotspot on: allow Modify system settings for it",
                "ALLOW",
                openCarPlayWriteSettings,
            )
            settings.hotspotOn == true -> ValueRow("Head unit hotspot", "RevvCarPlay turns it on for each CarPlay connection", "On")
            else -> ActionRow(
                "Head unit hotspot",
                if (settings.hotspotOn == false) "Off · RevvCarPlay turns it on when CarPlay connects" else "RevvCarPlay turns it on when CarPlay connects",
                "TURN ON",
                onClick = { companion?.turnOnHotspot() },
            )
        },
        ActionRow("iPhone", if (settings.phoneAddress != null) "${settings.phoneName} · for wireless CarPlay" else "None chosen · wireless CarPlay needs one", "CHOOSE", chooseIPhone),
        HeaderRow("DISPLAY", "CHANGES RECONNECT CARPLAY"),
        OptionRow("CarPlay size", "Size of CarPlay's icons and text", listOf("Large", "Medium", "Small"), CARPLAY_SIZES.indexOf(settings.carPlaySize).coerceAtLeast(0)) {
            set(CarPlayCompanion.SETTING_CARPLAY_SIZE, CARPLAY_SIZES[it])
        },
        OptionRow("Resolution", "Lower eases the load on a slow head unit", listOf("Native", "80 %", "60 %"), RESOLUTIONS.indexOf(settings.resolution).coerceAtLeast(0)) {
            set(CarPlayCompanion.SETTING_RESOLUTION, RESOLUTIONS[it])
        },
        OptionRow("Frame rate", "60 fps moves smoother, 30 fps is lighter", listOf("30 fps", "60 fps"), if (settings.frameRate == 60) 1 else 0) {
            set(CarPlayCompanion.SETTING_FRAME_RATE, if (it == 1) 60 else 30)
        },
        SwitchRow("Efficient video", "HEVC; leave off for the widest head-unit support", settings.hevc) { set(CarPlayCompanion.SETTING_HEVC, it) },
        SwitchRow("Right-hand drive", "CarPlay's controls closer to the driver", settings.rightHandDrive) { set(CarPlayCompanion.SETTING_RIGHT_HAND_DRIVE, it) },
        HeaderRow("AUDIO", "CHANGES RECONNECT CARPLAY"),
        SwitchRow("Audio focus", "CarPlay music takes audio focus; turn off if sound goes missing", settings.audioFocus) { set(CarPlayCompanion.SETTING_AUDIO_FOCUS, it) },
        StepperRow("Media stream", "0 routes music automatically; a tone plays on the stream you pick", settings.mediaStream, 0..20, holdToRepeat = false) {
            set(CarPlayCompanion.SETTING_MEDIA_STREAM, it)
        },
        StepperRow("Navigation stream", "Where turn prompts play; 0 routes them automatically", settings.navigationStream, 0..20, holdToRepeat = false) {
            set(CarPlayCompanion.SETTING_NAVIGATION_STREAM, it)
        },
        OptionRow("Music buffer", "More rides out a weak link, at a little delay", listOf("300 ms", "500 ms", "1000 ms"), MUSIC_BUFFERS.indexOf(settings.musicBufferMillis).coerceAtLeast(0)) {
            set(CarPlayCompanion.SETTING_MUSIC_BUFFER, MUSIC_BUFFERS[it])
        },
        if (settings.advancedAudioAvailable) {
            SwitchRow("Advanced channel mapping", "Route by usage and content type instead of stream type", settings.advancedAudio) { set(CarPlayCompanion.SETTING_ADVANCED_AUDIO, it) }
        } else {
            null
        },
        HeaderRow("LOCATION"),
        SwitchRow("Location to iPhone", "This head unit's GPS for the iPhone's maps, when it asks", settings.locationReporting) { set(CarPlayCompanion.SETTING_LOCATION_REPORTING, it) },
        if (settings.locationReporting && !settings.locationPermitted) {
            ActionRow("Location permission", "RevvCarPlay may not read the location yet; allow precise location for it", "ALLOW", openCarPlayAppInfo)
        } else {
            null
        },
        HeaderRow("SUPPORT"),
        ActionRow("Diagnostic report", "Saved to Downloads/Revv/CarPlay on this head unit; nothing is sent", "SAVE", onClick = { companion?.saveReport() }),
    )
}

/** Setup only the companion's own screen can do: its permission prompts and the VPN consent. */
private val COMPANION_PROMPTS = setOf(CarPlayCompanion.SETUP_VPN, CarPlayCompanion.SETUP_WIRELESS_PERMISSIONS)
private val CARPLAY_SIZES = listOf(CarPlayCompanion.SIZE_LARGE, CarPlayCompanion.SIZE_MEDIUM, CarPlayCompanion.SIZE_SMALL)
private val RESOLUTIONS = listOf(10, 8, 6)
private val MUSIC_BUFFERS = listOf(300, 500, 1000)

/** The car hotspot's name and password, typed exactly: Wi-Fi details have lower case and symbols. */
@Composable
private fun HotspotEditor(initialName: String, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var password by rememberSaveable { mutableStateOf("") }
    var typingPassword by rememberSaveable { mutableStateOf(initialName.isNotEmpty()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    val error = hotspotError(name, password)
    val fields: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HText("CAR HOTSPOT", size = 26.sp, family = Hmi.Display)
            HText(
                "Copy these from the car's hotspot settings; 5 GHz works best. The password is kept by RevvCarPlay only, so type it again to change anything.",
                size = 16.sp,
                color = Hmi.Muted,
                lineHeight = 22.sp,
            )
            CredentialField("NAME", name.ifEmpty { "–" }, selected = !typingPassword) { typingPassword = false }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CredentialField(
                    "PASSWORD",
                    if (password.isEmpty()) "–" else if (showPassword) password else "•".repeat(password.length),
                    selected = typingPassword,
                    modifier = Modifier.weight(1f),
                ) { typingPassword = true }
                GhostButton(if (showPassword) "HIDE" else "SHOW", { showPassword = !showPassword }, Modifier.height(64.dp))
            }
            if (tried && error != null) HText(error, size = 16.sp, color = Hmi.Red)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("CANCEL", onCancel, Modifier.height(56.dp))
                SolidButton(
                    "SAVE",
                    onClick = { tried = true; if (error == null) onSave(name.trim(), password) },
                    modifier = Modifier.height(56.dp).weight(1f, fill = false).width(240.dp),
                )
            }
        }
    }
    val keyboard: @Composable (Modifier) -> Unit = { modifier ->
        FullKeyboard(
            onKey = { key -> if (typingPassword) password = (password + key).take(63) else name = (name + key).take(32) },
            onSpace = { if (typingPassword) password = (password + " ").take(63) else name = (name + " ").take(32) },
            onBackspace = { if (typingPassword) password = password.dropLast(1) else name = name.dropLast(1) },
            modifier = modifier,
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when {
            maxWidth >= NARROW_EDITOR -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                fields(Modifier.weight(1f).fillMaxHeight())
                keyboard(Modifier.width(760.dp).fillMaxHeight())
            }
            // Beside CarPlay the column is narrow but tall: the keyboard goes under the fields.
            maxHeight >= STACKED_EDITOR_HEIGHT -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                fields(Modifier.fillMaxWidth().weight(1f))
                keyboard(Modifier.fillMaxWidth().height(NARROW_KEYBOARD_HEIGHT))
            }
            // Large display sizes: side by side, sharing the width.
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                fields(Modifier.weight(1f).fillMaxHeight())
                keyboard(Modifier.weight(1.4f).fillMaxHeight())
            }
        }
    }
}

/** Below this width the hotspot editor's keyboard no longer gets its full width beside the fields. */
private val NARROW_EDITOR = 1100.dp
private val NARROW_KEYBOARD_HEIGHT = 380.dp

/** The hotspot editor stacks its keyboard under the fields only with this much height. */
private val STACKED_EDITOR_HEIGHT = 740.dp

/** Narrower than this, side by side, the hotspot editor's fields get too cramped. */
private val HOTSPOT_EDITOR_MIN_WIDTH = 1000.dp

@Composable
private fun CredentialField(label: String, value: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        background = if (selected) Hmi.Cyan.copy(alpha = 0.10f) else Color.Transparent,
        pressedBackground = Hmi.CyanTint,
        border = if (selected) Hmi.Cyan else Hmi.Line,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(label, Modifier.width(120.dp), size = 13.sp)
            HText(value, Modifier.weight(1f), size = 22.sp, maxLines = 1)
        }
    }
}

/** RevvCarPlay checks these too; the same rules here say what is wrong before anything is sent. */
private fun hotspotError(name: String, password: String): String? = when {
    name.isBlank() -> "Enter the car hotspot's name."
    name.trim().encodeToByteArray().size > 32 -> "The name can be 32 bytes at most."
    password.isNotEmpty() && password.length !in 8..63 -> "The password needs 8 to 63 characters, or none for an open hotspot."
    else -> null
}

/** The paired devices to pick the iPhone for wireless CarPlay from. */
@Composable
private fun IPhonePicker(state: HmiUiState, actions: HmiActions, chosen: String?, onChoose: (String, String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val system = state.system
    val devices = remember(system.bluetoothOn, system.hasBluetoothPermission, system.pairedDevices) { pairedDevices(context) }
    Column(Modifier.fillMaxSize()) {
        HText("IPHONE", size = 26.sp, family = Hmi.Display)
        HText("The paired iPhone wireless CarPlay connects to. Pair it in Bluetooth settings first.", Modifier.padding(top = 8.dp), size = 16.sp, color = Hmi.Muted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !system.hasBluetoothPermission -> item(key = "permission") {
                    SettingRowView(ActionRow("Bluetooth access", "Needed to list paired phones", "ALLOW", actions::requestBluetoothPermission), state, actions)
                }
                !system.bluetoothOn -> item(key = "off") { HText("Bluetooth is off.", Modifier.padding(vertical = 12.dp), size = 19.sp, color = Hmi.Muted) }
                devices.isEmpty() -> item(key = "none") { HText("No paired devices yet.", Modifier.padding(vertical = 12.dp), size = 19.sp, color = Hmi.Muted) }
            }
            items(devices, key = { it.second }) { (name, address) ->
                // The end of the address tells two devices of the same name apart.
                val twin = devices.count { it.first == name } > 1
                ChoiceRow(
                    icon = HmiIcons.PHONE,
                    title = name,
                    detail = if (twin) "…${address.takeLast(5)}" else null,
                    selected = address == chosen,
                    tag = if (address == chosen) "IN USE" else null,
                    onClick = {
                        onChoose(address, name)
                        onDone()
                    },
                )
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("BLUETOOTH SETTINGS", actions::openBluetoothSettings, Modifier.height(56.dp))
            SolidButton("DONE", onClick = onDone, modifier = Modifier.height(56.dp).weight(1f, fill = false).width(240.dp))
        }
    }
}

/** Name and address of each paired Bluetooth device, by name; empty without Bluetooth access. */
@SuppressLint("MissingPermission")
private fun pairedDevices(context: Context): List<Pair<String, String>> = runCatching {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .map { (it.name ?: "Paired device") to it.address }
        .sortedBy { it.first.lowercase() }
}.getOrDefault(emptyList())

/** The screen's backlight, a system setting Revv needs Android's leave to change. */
private fun brightnessRow(state: HmiUiState, actions: HmiActions): SettingRow {
    val system = state.system
    if (!system.canChangeBrightness) {
        return ActionRow("Brightness", "Screen backlight · needs access to modify system settings", "ALLOW", actions::requestBrightnessAccess)
    }
    val detail = when (nightMode(state)) {
        NightMode.Off -> "Screen backlight"
        NightMode.Sunset -> if (system.nightSchedule.night) "Night level · kept for every night" else "Day level · kept for every day"
        // Under adaptive brightness, Android takes the level set as the one to adapt from.
        NightMode.LightSensor -> "Screen backlight · the light sensor adjusts from here"
    }
    return LevelRow(SettingsStore.BRIGHTNESS, "Brightness", detail, system.brightness, 100)
}

/** How the screen dims at night: not at all, from sunset to sunrise, or by the light sensor if there is one. */
private fun autoNightRow(state: HmiUiState, actions: HmiActions, clock: DateTimeFormatter): SettingRow {
    val system = state.system
    if (!system.canChangeBrightness) {
        return ActionRow("Auto night mode", "Dims the screen at night · needs access to modify system settings", "ALLOW", actions::requestBrightnessAccess)
    }
    val modes = listOfNotNull(NightMode.Off, NightMode.Sunset, NightMode.LightSensor.takeIf { system.autoBrightnessAvailable })
    val mode = nightMode(state)
    val detail = when (mode) {
        NightMode.Off -> "Brightness stays where you set it"
        NightMode.Sunset -> sunsetDetail(system.nightSchedule, clock)
        NightMode.LightSensor -> "Dims with the ambient light sensor"
    }
    val labels = modes.map {
        when (it) {
            NightMode.Off -> "Off"
            NightMode.Sunset -> "Sunset"
            NightMode.LightSensor -> "Light sensor"
        }
    }
    return OptionRow("Auto night mode", detail, labels, modes.indexOf(mode)) { actions.setNightMode(modes[it]) }
}

private fun nightMode(state: HmiUiState): NightMode =
    NightMode.of(lightSensor = state.system.autoBrightness, sunset = state.settings.isOn(SettingsStore.SUNSET_DIMMING))

/** When sunset dimming next changes, e.g. "Dims at sunset, 18:26". */
private fun sunsetDetail(schedule: NightSchedule, clock: DateTimeFormatter): String {
    val at = schedule.until?.atZone(ZoneId.systemDefault())?.format(clock)
        ?: return if (schedule.night) "Dimmed: the sun doesn't rise today" else "The sun doesn't set today"
    return when {
        !schedule.located && schedule.night -> "Dimmed until $at · a guess until GPS finds the car"
        !schedule.located -> "Dims at $at · a guess until GPS finds the car"
        schedule.night -> "Dimmed until sunrise, $at"
        else -> "Dims at sunset, $at"
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
private fun engineSoundRows(settings: HmiSettings, actions: HmiActions): List<SettingRow> {
    val engine = settings.engineSound
    return listOf(
        HeaderRow("ENGINE SOUND", "Follows rpm, load and gear"),
        ToggleRow(
            SettingsStore.ENGINE_SOUND,
            "Engine sound",
            "A synthesised engine through the speakers, from the OBD-II adapter's data or the demo drive",
            badge = "EXPERIMENTAL",
        ),
        OptionRow("Engine", "Cylinders, firing order and headers", ENGINE_LAYOUTS, engine.layout.ordinal) {
            actions.setChoice(SettingsStore.ENGINE_LAYOUT, it)
        },
        OptionRow("Exhaust", "From muffled to raspy", EXHAUST_NOTES, engine.note.ordinal) {
            actions.setChoice(SettingsStore.ENGINE_EXHAUST, it)
        },
        LevelRow(SettingsStore.ENGINE_VOLUME, "Engine volume", "Under the media volume, which turns it up and down too", engine.volume, EngineSoundSettings.MAX_VOLUME),
        LevelRow(SettingsStore.ENGINE_BASS, "Bass", "The engine's low rumble, for the speakers it plays through", engine.bass, EngineSoundSettings.MAX_BASS),
        ToggleRow(SettingsStore.ENGINE_SURROUND, "Surround", "Each bank's exhaust from its own side and the echoes around you; off plays the same from every speaker"),
        ToggleRow(SettingsStore.ENGINE_CRACKLE, "Pops on lift-off", "Crackles from the exhaust when you lift off at high revs"),
        ActionRow("Preview", "Hear the chosen engine rev, parked", "REV", actions::previewEngineSound),
    )
}

private val ENGINE_LAYOUTS = EngineLayout.entries.map { it.label }
private val EXHAUST_NOTES = ExhaustNote.entries.map { it.label }

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

/** Under the Gear indicator row: where the gears come from and how far learning has got. */
private fun gearIndicatorDetail(car: CarSetup, learnt: List<Float>): String = when (car.gearSource) {
    GearSource.Ratios -> "From tyre size and gear ratios · ${car.tyre}"
    GearSource.Learnt -> if (learnt.isEmpty()) {
        "Learning as you drive · nothing learnt yet"
    } else {
        "Learning as you drive · ${minOf(learnt.size, car.gears)} of ${car.gears} gears learnt"
    }
}

private fun fuelWidgetRow(state: HmiUiState, actions: HmiActions, choose: () -> Unit): SettingRow {
    val app = state.settings.fuelWidgetApp?.let { key -> state.apps.firstOrNull { it.key == key } }
    return ActionRow(
        "Home fuel widget",
        if (app == null) "Showing fuel range" else "Opens ${app.label}",
        "CHANGE",
        choose,
        secondary = if (app != null) "FUEL RANGE" to { actions.setFuelWidgetApp(null) } else null,
    )
}

private val PLACE_SEARCHES = listOf("OpenStreetMap", "Google")

private fun placeSearchDetail(settings: HmiSettings): String = when {
    settings.placeSearch != SettingsStore.SEARCH_GOOGLE -> "Photon and Valhalla over OpenStreetMap data, free"
    settings.googleApiKey == null -> "Google needs an API key below; OpenStreetMap until then"
    else -> "Google Places and Routes with your API key, so routes match Google Maps on CarPlay"
}

/** The Google key: shown once Google is chosen, or while one is saved. */
private fun googleKeyRow(settings: HmiSettings, actions: HmiActions, edit: () -> Unit): SettingRow? {
    val key = settings.googleApiKey
    if (key == null && settings.placeSearch != SettingsStore.SEARCH_GOOGLE) return null
    return if (key == null) {
        ActionRow("Google API key", "A Google Maps Platform key with Places API (New) and Routes API enabled", "ADD", edit)
    } else {
        ActionRow("Google API key", "Saved on this head unit · ends in ${key.takeLast(4)}", "CHANGE", edit, secondary = "REMOVE" to { actions.setGoogleApiKey(null) })
    }
}

/**
 * The Google Maps Platform API key, typed on the full keyboard (keys mix upper and lower case,
 * digits, - and _), pasted from the clipboard (PASTE, or a long press on the key), or read from a
 * text file. Pasted and imported text may hold more than the key; the key is picked out of it.
 */
@Composable
private fun ApiKeyEditor(initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var key by rememberSaveable { mutableStateOf(initial) }
    val context = LocalContext.current
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()
    fun take(text: String?, empty: String) {
        val found = text?.let(GoogleApiKey::find)
        if (found != null) key = found else Toast.makeText(context, empty, Toast.LENGTH_LONG).show()
    }
    val paste = { take(clipboardText(context), "Nothing on the clipboard looks like an API key. Copy the key first.") }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { take(readSmallText(context, uri), "No API key in that file.") }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HText("GOOGLE API KEY", size = 26.sp, family = Hmi.Display)
            HText(
                "From Google Cloud console › APIs & Services › Credentials, in a project with Places API (New) " +
                    "and Routes API enabled. Google charges that project for searches and routes beyond its free monthly allowance.",
                size = 16.sp,
                color = Hmi.Muted,
                lineHeight = 22.sp,
            )
            // Like a phone's text field, a long press pastes.
            Pressable(
                onClick = {},
                onLongClick = paste,
                modifier = Modifier.fillMaxWidth().edgeLine(),
                pressedBackground = Hmi.CyanWash,
                border = null,
                contentAlignment = Alignment.CenterStart,
                sound = null,
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Two lines hold a whole key.
                    HText(
                        key.ifEmpty { "Type, paste or import the key" },
                        Modifier.weight(1f),
                        size = 20.sp,
                        color = if (key.isEmpty()) Hmi.Faint else Hmi.Text,
                        maxLines = 2,
                    )
                    if (key.isNotEmpty()) {
                        Pressable(onClick = { key = "" }, Modifier.height(52.dp), border = null) {
                            Caption("CLEAR", Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("PASTE", paste, Modifier.height(52.dp))
                GhostButton(
                    "IMPORT FILE",
                    {
                        runCatching { file.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }
                            .onFailure { Toast.makeText(context, "This head unit has no file picker.", Toast.LENGTH_LONG).show() }
                    },
                    Modifier.height(52.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("CANCEL", onCancel, Modifier.height(56.dp))
                SolidButton("SAVE", onClick = { onSave(key) }, modifier = Modifier.height(56.dp).weight(1f, fill = false).width(240.dp))
            }
        }
        FullKeyboard(
            onKey = { key = (key + it).take(GoogleApiKey.MAX_LENGTH) },
            // Keys have no spaces.
            onSpace = {},
            onBackspace = { key = key.dropLast(1) },
            modifier = (if (compact) Modifier.weight(1.4f) else Modifier.width(760.dp)).fillMaxHeight(),
        )
    }
}

private fun clipboardText(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

/** The start of a picked text file, enough for a key and whatever surrounds it; null if unreadable. */
private suspend fun readSmallText(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_KEY_FILE_BYTES)
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            String(buffer, 0, read)
        }
    }.getOrNull()
}

private const val MAX_KEY_FILE_BYTES = 16 * 1024

/** Settings › Home › Home fuel widget: pick the installed app the widget opens, or go back to the fuel range. */
@Composable
private fun FuelWidgetPicker(state: HmiUiState, actions: HmiActions, onDone: () -> Unit) {
    AppChooser(
        title = "FUEL WIDGET",
        hint = "Pick an app for the home screen widget to open instead of the fuel range.",
        apps = state.apps,
        chosen = state.settings.fuelWidgetApp,
        defaultTitle = "Fuel range",
        defaultDetail = "Fuel level and distance left",
        onChoose = actions::setFuelWidgetApp,
        onDone = onDone,
    )
}

/** Settings › Vehicle › Car: which car this is, for the Vehicle screen and the gear indicator. */
@Composable
private fun CarPanel(state: HmiUiState, actions: HmiActions, onDone: () -> Unit) {
    val car = state.settings.car
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = editing != null) { editing = null }
    when (editing) {
        EDIT_MAKE -> {
            TextEditor("MAKE", "Who makes the car, e.g. Maruti Suzuki or Mahindra.", car.make, onCancel = { editing = null }) {
                actions.updateCar(car.copy(make = it))
                editing = null
            }
            return
        }
        EDIT_MODEL -> {
            TextEditor("MODEL", "The model and version, e.g. Swift VXi 2015.", car.model, onCancel = { editing = null }) {
                actions.updateCar(car.copy(model = it))
                editing = null
            }
            return
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            HText("CAR", size = 26.sp, family = Hmi.Display)
            HText(
                "Shown on the Vehicle screen. The number of gears sets the home screen's gear strip and the gear indicator.",
                Modifier.padding(top = 6.dp),
                size = 15.sp,
                color = Hmi.Muted,
            )
            SettingRowView(ActionRow("Make", car.make, "EDIT", { editing = EDIT_MAKE }), state, actions)
            SettingRowView(ActionRow("Model", car.model, "EDIT", { editing = EDIT_MODEL }), state, actions)
            SettingRowView(
                StepperRow("Gears", "Forward gears in the gearbox", car.gears, CarSetup.MIN_GEARS..CarSetup.MAX_GEARS) {
                    actions.updateCar(car.withGears(it))
                },
                state,
                actions,
            )
            Caption("COLOUR", Modifier.padding(top = 20.dp, bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CarColour.entries.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { colour ->
                            val selected = colour == car.colour
                            Pressable(
                                onClick = { actions.updateCar(car.copy(colour = colour)) },
                                modifier = Modifier.weight(1f).height(64.dp),
                                background = if (selected) Hmi.Cyan.copy(alpha = 0.10f) else Color.Transparent,
                                pressedBackground = Hmi.CyanTint,
                                border = if (selected) Hmi.Cyan else Hmi.Line,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(22.dp).background(Color(colour.argb)).border(1.dp, Hmi.LineStrong))
                                    HText(colour.label, size = 17.sp, color = if (selected) Hmi.Text else Hmi.Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 16.dp)) {
            AccentButton("DONE", onDone, Modifier.height(56.dp))
        }
    }
}

/**
 * Settings › Vehicle › Gear indicator. OBD-II doesn't report the gear, so Revv tells it from engine
 * and road speed: from the gearbox ratios and tyre size, or by learning the gears as the car is driven.
 */
@Composable
private fun GearIndicatorPanel(state: HmiUiState, actions: HmiActions, onDone: () -> Unit) {
    val car = state.settings.car
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = editing != null) { editing = null }
    when (editing) {
        EDIT_TYRE -> {
            NumberEditor(
                title = "TYRE SIZE",
                hint = "As on the tyre's sidewall: 165/80 R14 is 165 mm wide, its sidewall 80% of that, on a 14-inch rim.",
                fields = listOf(
                    NumberField("WIDTH · MM", "${car.tyre.widthMm}"),
                    NumberField("SIDEWALL · %", "${car.tyre.aspectPercent}"),
                    NumberField("RIM · INCHES", "${car.tyre.rimInches}"),
                ),
                decimals = false,
                onCancel = { editing = null },
            ) { values ->
                val (width, sidewall, rim) = values.map { it.toIntOrNull() ?: 0 }
                val tyre = TyreSize(width, sidewall, rim)
                tyre.valid.also { valid ->
                    if (valid) {
                        actions.updateCar(car.copy(tyre = tyre))
                        editing = null
                    }
                }
            }
            return
        }
        EDIT_RATIOS -> {
            NumberEditor(
                title = "GEAR RATIOS",
                hint = "Each gear's ratio and the final drive, from the owner's manual or a spec sheet. First gear is usually 3 to 4.",
                fields = (1..car.gears).map { gear -> NumberField(ordinal(gear), car.ratios[gear - 1].toString()) } +
                    NumberField("FINAL DRIVE", car.finalDrive.toString()),
                decimals = true,
                onCancel = { editing = null },
            ) { values ->
                val ratios = values.dropLast(1).map { it.toFloatOrNull() ?: 0f }
                val finalDrive = values.last().toFloatOrNull() ?: 0f
                // Each gear lower than the one before, all within what gearboxes use.
                val valid = ratios.all { it in 0.3f..8f } && ratios.zipWithNext().all { (low, high) -> low > high } && finalDrive in 1f..10f
                valid.also {
                    if (valid) {
                        actions.updateCar(car.copy(ratios = ratios + car.ratios.drop(car.gears), finalDrive = finalDrive))
                        editing = null
                    }
                }
            }
            return
        }
    }
    Column(Modifier.fillMaxSize()) {
        // Scrolls when a large display size leaves too little height.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HText("GEAR INDICATOR", size = 26.sp, family = Hmi.Display)
            HText(
                "OBD-II doesn't report the gear, so Revv works it out from engine speed and road speed.",
                Modifier.padding(bottom = 8.dp),
                size = 15.sp,
                color = Hmi.Muted,
            )
            val fromRatios = car.gearSource == GearSource.Ratios
            ChoiceRow(
                icon = HmiIcons.VEHICLE,
                title = "From tyre size and gear ratios",
                detail = "Right from the start, with figures from the owner's manual or a spec sheet",
                selected = fromRatios,
                tag = if (fromRatios) "IN USE" else null,
                onClick = { actions.updateCar(car.copy(gearSource = GearSource.Ratios)) },
            )
            ChoiceRow(
                icon = HmiIcons.VEHICLE,
                title = "Learn automatically",
                detail = "Learns each gear as you drive in it, and which is first from moving off",
                selected = !fromRatios,
                tag = if (!fromRatios) "IN USE" else null,
                onClick = { actions.updateCar(car.copy(gearSource = GearSource.Learnt)) },
            )
            if (fromRatios) {
                SettingRowView(ActionRow("Tyre size", car.tyre.toString(), "EDIT", { editing = EDIT_TYRE }), state, actions)
                SettingRowView(
                    ActionRow(
                        "Gear ratios",
                        car.ratios.take(car.gears).joinToString(" · ") + " · final drive ${car.finalDrive}",
                        "EDIT",
                        { editing = EDIT_RATIOS },
                    ),
                    state,
                    actions,
                )
                Caption("RPM PER KM/H · " + car.rpmPerKmh().joinToString(" · ") { oneDecimal(it) }, size = 13.sp)
            } else {
                val learnt = state.learntGears.take(car.gears)
                SettingRowView(
                    ActionRow(
                        "Learnt so far",
                        if (learnt.isEmpty()) {
                            "Nothing yet: drive through the gears, moving off from a stop at least once"
                        } else {
                            "${learnt.size} of ${car.gears} gears · " + learnt.joinToString(" · ") { oneDecimal(it) } + " rpm per km/h"
                        },
                        "RELEARN",
                        actions::relearnGears,
                    ),
                    state,
                    actions,
                )
            }
        }
        Row(Modifier.padding(top = 16.dp)) {
            AccentButton("DONE", onDone, Modifier.height(56.dp))
        }
    }
}

/** A short name typed on the HMI keyboard: the car's make or model. */
@Composable
private fun TextEditor(title: String, hint: String, initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val compact = LocalCompact.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HText(title, size = 26.sp, family = Hmi.Display)
            HText(hint, size = 16.sp, color = Hmi.Muted)
            Row(Modifier.fillMaxWidth().height(72.dp).edgeLine(), verticalAlignment = Alignment.CenterVertically) {
                HText(text.ifEmpty { title }, Modifier.weight(1f), size = 28.sp, color = if (text.isEmpty()) Hmi.Faint else Hmi.Text, maxLines = 1)
                if (text.isNotEmpty()) {
                    Pressable(onClick = { text = "" }, Modifier.height(52.dp), border = null) {
                        Caption("CLEAR", Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("CANCEL", onCancel, Modifier.height(56.dp))
                SolidButton(
                    "SAVE",
                    onClick = { text.trim().takeIf { it.isNotEmpty() }?.let(onSave) },
                    modifier = Modifier.height(56.dp).weight(1f, fill = false).width(240.dp),
                )
            }
        }
        TextKeyboard(
            onKey = { text = (text + it).take(MAX_NAME) },
            onSpace = { if (text.isNotEmpty() && !text.endsWith(" ")) text += " " },
            onBackspace = { text = text.dropLast(1) },
            // Compact (display sizes above 130%) shares the width instead.
            modifier = (if (compact) Modifier.weight(1.4f) else Modifier.width(720.dp)).fillMaxHeight(),
        )
    }
}

private class NumberField(val label: String, val initial: String)

/**
 * A few numbers typed on a keypad: a tyre size, or gear ratios. A tap on a field types into it;
 * [onSave] gets every field's text and says whether it all made sense.
 */
@Composable
private fun NumberEditor(
    title: String,
    hint: String,
    fields: List<NumberField>,
    decimals: Boolean,
    onCancel: () -> Unit,
    onSave: (List<String>) -> Boolean,
) {
    val values = remember { fields.map { it.initial }.toMutableStateList() }
    var active by remember { mutableIntStateOf(0) }
    var invalid by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HText(title, size = 26.sp, family = Hmi.Display)
            HText(hint, size = 16.sp, color = Hmi.Muted)
            // Scrolls when a large display size leaves too little height for every gear.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.indices.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { index ->
                            val selected = index == active
                            Pressable(
                                onClick = { active = index },
                                modifier = Modifier.weight(1f).height(64.dp),
                                background = if (selected) Hmi.Cyan.copy(alpha = 0.10f) else Color.Transparent,
                                pressedBackground = Hmi.CyanTint,
                                border = if (selected) Hmi.Cyan else Hmi.Line,
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Caption(fields[index].label, Modifier.weight(1f), size = 13.sp)
                                    HText(
                                        values[index].ifEmpty { "–" },
                                        size = 24.sp,
                                        family = Hmi.Display,
                                        color = if (values[index].isEmpty()) Hmi.Faint else Hmi.Text,
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (invalid) HText("Some of these don't look right. Check them against the manual.", size = 16.sp, color = Hmi.Red)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("CANCEL", onCancel, Modifier.height(56.dp))
                SolidButton("SAVE", onClick = { invalid = !onSave(values.toList()) }, modifier = Modifier.height(56.dp).width(240.dp))
            }
        }
        KeyPad(
            if (decimals) DECIMAL_KEYS else WHOLE_KEYS,
            onKey = { key ->
                invalid = false
                val value = values[active]
                when (key) {
                    KEY_DELETE -> values[active] = value.dropLast(1)
                    KEY_NEXT -> active = (active + 1) % fields.size
                    "." -> if ('.' !in value) values[active] = value.ifEmpty { "0" } + "."
                    else -> values[active] = (value + key).take(MAX_DIGITS)
                }
            },
            modifier = Modifier.width(420.dp).fillMaxHeight(),
        )
    }
}

private fun ordinal(gear: Int): String = gear.toString() + when (gear) {
    1 -> "ST"
    2 -> "ND"
    3 -> "RD"
    else -> "TH"
}

private fun oneDecimal(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

private const val EDIT_MAKE = "make"
private const val EDIT_MODEL = "model"
private const val EDIT_TYRE = "tyre"
private const val EDIT_RATIOS = "ratios"
private const val MAX_NAME = 30
private const val MAX_DIGITS = 6
private const val KEY_DELETE = "DEL"
private const val KEY_NEXT = "NEXT"
private val DIGIT_KEYS = (1..9).map { "$it" to "" }
private val DECIMAL_KEYS = DIGIT_KEYS + listOf("." to "POINT", "0" to "", KEY_DELETE to "")
private val WHOLE_KEYS = DIGIT_KEYS + listOf(KEY_NEXT to "", "0" to "", KEY_DELETE to "")
