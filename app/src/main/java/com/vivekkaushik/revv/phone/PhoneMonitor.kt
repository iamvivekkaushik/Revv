package com.vivekkaushik.revv.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.vivekkaushik.revv.obd.BluetoothAccess
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follows the phone paired with this head unit and reads its call history and favourites over
 * Bluetooth (PBAP), the way car kits do, never the head unit's own. The phone is the one connected
 * for calls and audio, else the one read last time, else the only one paired. Call [start], [stop]
 * and the rest on the main thread.
 */
class PhoneMonitor(private val context: Context, private val scope: CoroutineScope) {

    private val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val _call = MutableStateFlow<ActiveCall?>(null)

    /** The call Revv placed on the phone, while it is dialling, ringing or connected. */
    val call: StateFlow<ActiveCall?> = _call.asStateFlow()

    @Volatile private var hangUpRequested = false

    private var started = false
    private var linkJob: Job? = null
    private var readJob: Job? = null

    /** The phone the state is about, when its calls were last read, and when a read last started. */
    @Volatile private var phoneAddress: String? = null
    @Volatile private var readAt = 0L
    @Volatile private var triedAt = 0L

    /** How much a change calls for: a new look at the phone, or reading it as well. */
    private enum class Trigger { Look, Check, Force }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshLink(
            when (intent.action) {
                // A phone coming into range, Bluetooth coming on or a new pairing: worth reading.
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> Trigger.Check
                else -> Trigger.Look
            },
        )
    }

    fun start() {
        if (started) return
        started = true
        // Exported: Bluetooth's broadcasts come from the Bluetooth app, not the system itself.
        ContextCompat.registerReceiver(context, receiver, bluetoothEvents(), ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(receiver)
        linkJob?.cancel()
    }

    /** Picks up Bluetooth access granted or a phone paired while Revv was away, and reads the phone if it's been a while. */
    fun refresh() = refreshLink(Trigger.Check)

    /** Reads the phone's calls again now, as after a failure. */
    fun readAgain() = refreshLink(Trigger.Force)

    /**
     * Has the phone call [number] (digits and + * #). With [CallRoute.Phone], Revv links up as a
     * hands-free device just long enough to dial, and [onFailed] hears on the main thread if it
     * couldn't; with [CallRoute.HeadUnit] the head unit's own hands-free link should place it.
     */
    fun call(number: String, onFailed: (String) -> Unit): CallRoute {
        val link = _state.value.link
        val adapter = bluetooth
        if (link == null || adapter == null || !BluetoothAccess.granted(context)) return CallRoute.None
        if (headUnitHandsFree(adapter)) return CallRoute.HeadUnit
        if (_call.value != null) return CallRoute.Phone
        hangUpRequested = false
        _call.value = ActiveCall(number, nameFor(number), CallStage.Dialling)
        scope.launch(Dispatchers.IO) {
            val problem = try {
                HandsFreeLink.dial(adapter, adapter.getRemoteDevice(link.address), number, ::onCallIndicators) { hangUpRequested }
                null
            } catch (e: HandsFreeException) {
                callProblem(e.stage, link.name)
            } catch (e: SecurityException) {
                "Revv may no longer use Bluetooth."
            }
            _call.value = null
            if (problem != null) withContext(Dispatchers.Main) { onFailed(problem) }
        }
        return CallRoute.Phone
    }

    /** Ends the call Revv placed. */
    fun hangUp() {
        hangUpRequested = true
        _call.update { it?.copy(stage = CallStage.Ending) }
    }

    private fun onCallIndicators(indicators: CallIndicators) {
        _call.update { active ->
            active ?: return@update null
            when {
                active.stage == CallStage.Ending -> active
                indicators.call > 0 -> active.copy(stage = CallStage.Connected, answeredAt = active.answeredAt ?: SystemClock.elapsedRealtime())
                indicators.setup == 3 -> active.copy(stage = CallStage.Ringing)
                else -> active
            }
        }
    }

    /** The contact the number belongs to, if it is in the recent calls or favourites. */
    private fun nameFor(number: String): String? {
        fun digits(text: String) = text.filter(Char::isDigit).takeLast(10)
        val wanted = digits(number)
        if (wanted.isEmpty()) return null
        val state = _state.value
        return state.favourites.firstOrNull { digits(it.number) == wanted }?.name
            ?: state.recents.firstOrNull { digits(it.number) == wanted && it.label != it.number }?.label
    }

    /** Whether the head unit's own Bluetooth is linked to a phone as its hands-free kit. */
    @SuppressLint("MissingPermission")
    private fun headUnitHandsFree(adapter: BluetoothAdapter): Boolean =
        runCatching { adapter.getProfileConnectionState(HEADSET_CLIENT) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)

    private fun callProblem(stage: HandsFreeException.Stage, name: String): String = when (stage) {
        HandsFreeException.Stage.Reaching -> "Couldn't reach $name to call. Check its Bluetooth is on and it's nearby."
        HandsFreeException.Stage.Linking -> "$name wouldn't take calls from this head unit."
        HandsFreeException.Stage.Dialling -> "$name couldn't dial that number."
    }

    private fun refreshLink(trigger: Trigger) {
        linkJob?.cancel()
        linkJob = scope.launch(Dispatchers.IO) {
            delay(SETTLE_MILLIS)
            if (!BluetoothAccess.granted(context)) {
                ensureActive()
                phoneAddress = null
                _state.value = PhoneState(sync = PhoneSync.NeedsPermission)
                return@launch
            }
            val phones = bluetoothPhones()
            val paired = phones.map { (device, connected) -> PairedPhone(nameOf(device), connected) }
            val (device, connected) = choose(phones) ?: run {
                ensureActive()
                phoneAddress = null
                _state.value = PhoneState(sync = PhoneSync.NoPhone, pairedPhones = paired)
                return@launch
            }
            val link = PhoneLink(nameOf(device), device.address, batteryOf(device), connected)
            ensureActive()
            val newPhone = device.address != phoneAddress
            if (newPhone) {
                phoneAddress = device.address
                readAt = 0L
                triedAt = 0L
                // Another phone's calls mustn't linger under this one's name.
                _state.value = PhoneState(sync = PhoneSync.Reading, link = link, pairedPhones = paired)
            } else {
                _state.update { it.copy(link = link, pairedPhones = paired) }
            }
            if (newPhone || trigger != Trigger.Look) maybeRead(device, if (newPhone) Trigger.Force else trigger)
        }
    }

    @Synchronized
    private fun maybeRead(device: BluetoothDevice, trigger: Trigger) {
        if (readJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        // Our own connection's comings and goings mustn't set off reading after reading.
        val due = trigger == Trigger.Force || (now - readAt > FRESH_MILLIS && now - triedAt > RETRY_MILLIS)
        if (!due) return
        val adapter = bluetooth ?: return
        triedAt = now
        readJob = scope.launch(Dispatchers.IO) { read(adapter, device) }
    }

    private fun read(adapter: BluetoothAdapter, device: BluetoothDevice) {
        val address = device.address
        val name = nameOf(device)
        // Changes the state, unless the head unit moved on to another phone meanwhile.
        fun report(change: (PhoneState) -> PhoneState) {
            if (phoneAddress == address) _state.update(change)
        }
        report { it.copy(sync = PhoneSync.Reading, problem = null) }
        try {
            val data = PbapSession.read(
                adapter,
                device,
                onWaiting = { report { it.copy(sync = PhoneSync.AwaitingApproval) } },
                onReading = { report { it.copy(sync = PhoneSync.Reading) } },
            )
            val country = networkCountry()
            val calls = data.calls.mapNotNull { call(it, country) }
            val favourites = data.favourites.orEmpty().mapNotNull { favourite(it, country) }
            val contacts = data.contacts.mapNotNull { contact(it) }
            val now = System.currentTimeMillis()
            prefs.edit { putString(LAST_PHONE, address) }
            if (phoneAddress == address) readAt = SystemClock.elapsedRealtime()
            report {
                it.copy(
                    sync = PhoneSync.Synced,
                    problem = null,
                    syncedAt = now,
                    recents = PhoneBook.recents(calls),
                    favourites = PhoneBook.favourites(favourites, calls, now),
                    contacts = PhoneBook.contacts(contacts),
                )
            }
        } catch (e: PbapException) {
            report { it.copy(sync = PhoneSync.Failed, problem = problem(e.stage, name)) }
        } catch (e: SecurityException) {
            report { it.copy(sync = PhoneSync.NeedsPermission) }
        } catch (e: RuntimeException) {
            // Whatever a phone sends, it mustn't take the home screen down.
            report { it.copy(sync = PhoneSync.Failed, problem = "Couldn't make sense of what $name sent.") }
        }
        // Read the phone the head unit moved on to, if it did.
        if (phoneAddress != address) scope.launch(Dispatchers.Main) { refreshLink(Trigger.Force) }
    }

    private fun problem(stage: PbapException.Stage, name: String): String = when (stage) {
        PbapException.Stage.Reaching -> "Couldn't reach $name. Check its Bluetooth is on and it's nearby."
        PbapException.Stage.Asking ->
            "$name didn't share its calls. Allow contacts and call history for this head unit in its Bluetooth settings, then try again."
        PbapException.Stage.Reading -> "The connection to $name dropped while reading its calls."
    }

    private fun call(entry: PbapEntry, country: String?): Call? {
        val type = entry.call ?: return null
        val number = entry.numbers.firstOrNull().orEmpty()
        val label = entry.name ?: if (number.isBlank()) "Unknown number" else readable(number, country)
        return Call(number, label, type, entry.timeMillis ?: 0L)
    }

    private fun contact(entry: PbapEntry): Contact? {
        val name = entry.name ?: return null
        val number = entry.numbers.firstOrNull() ?: return null
        return Contact(name, number)
    }

    private fun favourite(entry: PbapEntry, country: String?): Favourite? {
        val number = entry.numbers.firstOrNull() ?: return null
        return Favourite(entry.name ?: readable(number, country), number, starred = true)
    }

    /** The number laid out for reading where its country is clear: "+91 98100 12345". */
    private fun readable(number: String, country: String?): String {
        // Without a SIM to go by, a number with no country code could be from anywhere.
        val region = country ?: Locale.getDefault().country.takeIf { number.startsWith("+") } ?: return number
        return PhoneNumberUtils.formatNumber(number, region) ?: number
    }

    private fun networkCountry(): String? = runCatching {
        telephony?.networkCountryIso?.takeIf(String::isNotBlank) ?: telephony?.simCountryIso?.takeIf(String::isNotBlank)
    }.getOrNull()?.uppercase(Locale.ROOT)

    private fun choose(phones: List<Pair<BluetoothDevice, Boolean>>): Pair<BluetoothDevice, Boolean>? =
        phones.firstOrNull { it.second }
            ?: prefs.getString(LAST_PHONE, null)?.let { last -> phones.firstOrNull { it.first.address == last } }
            ?: phones.singleOrNull()

    /** Phones paired with this head unit, connected ones first, each with whether it's connected. */
    @SuppressLint("MissingPermission")
    private fun bluetoothPhones(): List<Pair<BluetoothDevice, Boolean>> {
        val adapter = bluetooth ?: return emptyList()
        return try {
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices.orEmpty()
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
                .map { it to isConnected(it) }
                .sortedByDescending { it.second }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(device: BluetoothDevice): String = try {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null) ?: device.name ?: device.address
    } catch (e: SecurityException) {
        device.address
    }

    // Both are system APIs, hidden from apps' SDK but open to reflection, and they need only the
    // Bluetooth permission Revv already asks for. Builds without them just show less.
    private fun isConnected(device: BluetoothDevice): Boolean =
        runCatching { device.javaClass.getMethod("isConnected").invoke(device) as Boolean }.getOrDefault(false)

    private fun batteryOf(device: BluetoothDevice): Int? =
        runCatching { device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int }.getOrNull()?.takeIf { it in 0..100 }

    private fun bluetoothEvents() = IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) addAction(BluetoothDevice.ACTION_ALIAS_CHANGED)
        addAction(BLUETOOTH_BATTERY_CHANGED)
    }

    private companion object {
        /** BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED, a system API. */
        const val BLUETOOTH_BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

        /** BluetoothProfile.HEADSET_CLIENT, a system API: the head unit's side of hands-free calling. */
        const val HEADSET_CLIENT = 16

        const val LAST_PHONE = "lastPhone"
        const val SETTLE_MILLIS = 300L

        /** Calls read this recently are shown as they are. */
        const val FRESH_MILLIS = 60_000L

        /** How soon after a failed read the next one may start by itself. */
        const val RETRY_MILLIS = 10_000L
    }
}
