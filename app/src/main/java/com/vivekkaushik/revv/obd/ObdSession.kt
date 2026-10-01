package com.vivekkaushik.revv.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import com.vivekkaushik.revv.vehicle.CarSetup
import com.vivekkaushik.revv.vehicle.FuelMath
import com.vivekkaushik.revv.vehicle.GearEstimator
import com.vivekkaushik.revv.vehicle.TripComputer
import com.vivekkaushik.revv.vehicle.VehicleProfile
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps a link to one ELM327 adapter: connects, waits for the car to answer, polls live data and
 * reconnects with backoff whenever the adapter or the car goes quiet.
 */
class ObdSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val profile: VehicleProfile = VehicleProfile.SWIFT_VXI_2015,
) {
    private val _status = MutableStateFlow(ObdStatus())
    val status: StateFlow<ObdStatus> = _status.asStateFlow()

    private val _readings = MutableStateFlow<ObdReadings?>(null)

    /** Live values while the car answers, null otherwise. */
    val readings: StateFlow<ObdReadings?> = _readings.asStateFlow()

    private val trip = TripComputer()
    private var job: Job? = null

    /** The car as set up in Settings, which says how to tell its gears. */
    @Volatile
    private var car = CarSetup.SWIFT_VXI_2015

    /** The gear estimate for the car connected now. */
    @Volatile
    private var gears: GearEstimator? = null

    private val _learntGears = MutableStateFlow<List<Float>>(emptyList())

    /** The gears learnt for the car last connected, rpm per km/h, first gear first. */
    val learntGears: StateFlow<List<Float>> = _learntGears.asStateFlow()

    /** Saved where USB can reach it (Android/data/<app>/files/logs), for drives with no laptop along. */
    private val log = ObdLog(context.getExternalFilesDir(LOG_FOLDER) ?: File(context.filesDir, LOG_FOLDER)).also {
        it.recordCrashes()
    }

    /** The adapter conversation of recent connection attempts, for troubleshooting. */
    val adapterLog: StateFlow<List<String>> = log.lines

    /** Whether the adapter log is also saved to a file on the device. */
    fun saveLogs(save: Boolean) {
        log.saving = save
    }

    /** The protocol each adapter's car answered on, tried first on the next connection. */
    private val protocols = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    /** While live data flows, the log keeps only what went wrong. */
    @Volatile
    private var polling = false

    /** Where the Wi-Fi adapter answered last time, tried first on reconnects. */
    @Volatile
    private var lastWifiEndpoint: WifiEndpoint? = null

    @Volatile
    private var transport: ObdTransport? = null

    fun setCar(car: CarSetup) {
        this.car = car
        gears?.setup = car
    }

    /** Forgets every car's learnt gears, to learn them afresh. */
    fun relearnGears() {
        gears?.forget()
        protocols.edit { protocols.all.keys.filter { it.startsWith(GEARS_PREFIX) }.forEach(::remove) }
        _learntGears.value = emptyList()
    }

    /** Connects to [adapter] and stays connected, reconnecting as needed, until [stop]. */
    fun start(adapter: ObdAdapter) {
        stop()
        job = scope.launch(Dispatchers.IO) { keepConnected(adapter) }
    }

    /** Asks the car to erase its fault codes, on the next pass of the polling loop. */
    fun clearTroubleCodes() {
        clearRequested = true
    }

    @Volatile
    private var clearRequested = false

    fun stop() {
        job?.cancel()
        job = null
        // A blocking read only ends when its socket closes.
        transport?.let { runCatching { it.close() } }
        transport = null
        _status.value = ObdStatus()
        _readings.value = null
    }

    private suspend fun keepConnected(adapter: ObdAdapter) {
        log.add("Revv ${appVersion()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        var retryMillis = FIRST_RETRY_MILLIS
        while (true) {
            try {
                connectAndPoll(adapter) { retryMillis = FIRST_RETRY_MILLIS }
            } catch (e: MissingPermissionException) {
                log.add("Stopped: Bluetooth access isn't allowed")
                _status.value = ObdStatus(ObdLink.NeedsPermission, adapter.name)
                return
            } catch (e: SecurityException) {
                log.add("Stopped: Bluetooth access isn't allowed")
                _status.value = ObdStatus(ObdLink.NeedsPermission, adapter.name)
                return
            } catch (e: BluetoothOffException) {
                log.add("Bluetooth is off")
                _status.value = ObdStatus(ObdLink.BluetoothOff, adapter.name)
            } catch (e: NoWifiException) {
                log.add("Not on the adapter's Wi-Fi network")
                _status.value = ObdStatus(ObdLink.NoWifi, adapter.name)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                log.add("Failed: ${e.message ?: "connection lost"}")
                _status.value = ObdStatus(ObdLink.Retrying, adapter.name, problem = e.message ?: "Connection lost")
            }
            polling = false
            _readings.value = null
            log.add("Trying again in ${retryMillis / 1000} s")
            delay(retryMillis)
            retryMillis = (retryMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
        }
    }

    private suspend fun connectAndPoll(adapter: ObdAdapter, onLive: () -> Unit) {
        _status.value = ObdStatus(ObdLink.Connecting, adapter.name)
        log.add("Connecting to ${adapter.name} (${adapter.kind}, ${adapter.address})")
        val link = open(adapter)
        transport = link
        val endpoint = if (adapter.kind == ObdAdapter.Kind.WiFi) lastWifiEndpoint?.toString() else null
        try {
            currentCoroutineContext().ensureActive()
            val elm = Elm327(link, ::trace)
            val known = protocols.getInt(protocolKey(adapter), 0).takeIf { it > 0 }
            known?.let { log.add("Trying ${ObdResponse.protocolName(it)} first, as last time") }
            elm.initialize(preferredProtocol = known)
            while (true) {
                val supported = waitForEcu(elm, adapter)
                elm.protocolNumber?.let { protocols.edit { putInt(protocolKey(adapter), it) } }
                log.add("Live: ${elm.protocolName ?: "unknown protocol"}, car supports PIDs " + supported.sorted().joinToString(" ") { ObdResponse.hex(it) })
                _status.value = ObdStatus(ObdLink.Live, adapter.name, protocol = elm.protocolName, endpoint = endpoint)
                onLive()
                val gearsKey = gearsKey(elm.protocolNumber, supported)
                val estimator = GearEstimator(car, savedGears(gearsKey)).also { gears = it }
                _learntGears.value = rounded(estimator.learnt())
                polling = true
                try {
                    poll(elm, supported, estimator) { keepLearntGears(gearsKey, estimator) }
                } finally {
                    keepLearntGears(gearsKey, estimator)
                }
                polling = false
                log.add("The car stopped answering")
                // The car stopped answering (ignition off). Keep the adapter and wait for it again.
                _readings.value = null
            }
        } finally {
            polling = false
            runCatching { link.close() }
            if (transport === link) transport = null
        }
    }

    /** Logs the adapter conversation: all of it while connecting, only failures once data flows. */
    private fun trace(command: String, reply: String?) {
        val text = reply?.split('\r', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" | ")
        if (polling && text != null && text.none { it == '?' } && !FAILURES.any { text.contains(it) }) return
        log.add("> $command   < ${text?.ifEmpty { "(empty)" } ?: "no reply"}")
    }

    private fun protocolKey(adapter: ObdAdapter) = "protocol.${adapter.kind}.${adapter.address}"

    /** Learnt gears are kept per car, told apart by its protocol and the readings it supports. */
    private fun gearsKey(protocol: Int?, supported: Set<Int>) =
        GEARS_PREFIX + "${protocol ?: 0}." + Integer.toHexString(supported.sorted().hashCode())

    private fun savedGears(key: String): List<Float> =
        protocols.getString(key, null)?.split(',')?.mapNotNull(String::toFloatOrNull).orEmpty()

    /** To a tenth: finer changes aren't worth saving or logging. */
    private fun rounded(gears: List<Float>) = gears.map { round(it * 10) / 10 }

    /** Saves what's been learnt about the car's gears, and logs it when it changes. */
    private fun keepLearntGears(key: String, estimator: GearEstimator) {
        val learnt = rounded(estimator.learnt())
        if (learnt == _learntGears.value) return
        protocols.edit { putString(key, learnt.joinToString(",")) }
        _learntGears.value = learnt
        log.add("Gears learnt: " + learnt.joinToString(" · ") { String.format(Locale.ROOT, "%.1f", it) } + " rpm per km/h")
    }

    private fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun open(adapter: ObdAdapter): ObdTransport = when (adapter.kind) {
        ObdAdapter.Kind.Simulated -> SimulatedElm327(profile)
        ObdAdapter.Kind.Bluetooth -> openBluetooth(adapter)
        ObdAdapter.Kind.BluetoothLe -> openBle(adapter)
        ObdAdapter.Kind.WiFi -> openWifi(adapter)
    }

    private fun openBluetooth(adapter: ObdAdapter): ObdTransport {
        val (bluetooth, device) = bluetoothDevice(adapter)
        return BluetoothObdTransport.connect(bluetooth, device, log::add)
    }

    private fun openBle(adapter: ObdAdapter): ObdTransport {
        val (_, device) = bluetoothDevice(adapter)
        return try {
            BleObdTransport.connect(context, device)
        } catch (e: IOException) {
            // Adapters with random addresses can't always be reached by address alone; a scan
            // finds them along with the address type. Needs scan access, so it's a fallback.
            val scanned = BleScanner.find(context, adapter.address) ?: throw e
            BleObdTransport.connect(context, scanned)
        }
    }

    private fun bluetoothDevice(adapter: ObdAdapter): Pair<BluetoothAdapter, BluetoothDevice> {
        if (!BluetoothAccess.granted(context)) throw MissingPermissionException()
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This device has no Bluetooth")
        if (!bluetooth.isEnabled) throw BluetoothOffException()
        val device = try {
            bluetooth.getRemoteDevice(adapter.address)
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a Bluetooth address: ${adapter.address}")
        }
        return bluetooth to device
    }

    /**
     * Connects to the Wi-Fi adapter: at the address the user typed in, or else by trying the
     * addresses clones commonly use on the joined network.
     */
    private fun openWifi(adapter: ObdAdapter): ObdTransport {
        val network = WifiNetworks.current(context) ?: throw NoWifiException()
        adapter.wifiEndpoint?.let { typed ->
            // Deliberately chosen, so tried exactly and on any network, even one with internet.
            return connectToElm(network, typed) ?: throw IOException("No ELM327 answered at $typed")
        }
        // Home Wi-Fi or a phone hotspot has internet and a router or phone for a gateway; an
        // adapter's network has neither, so only its gateway is worth probing.
        val onAdapterNetwork = !WifiNetworks.hasInternet(context, network)
        val gateway = if (onAdapterNetwork) WifiNetworks.gateway(context, network) else null
        WifiEndpoint.candidates(lastWifiEndpoint, gateway).firstNotNullOfOrNull { connectToElm(network, it) }
            ?.let { return it }
        if (!onAdapterNetwork) throw NoWifiException()
        throw IOException("No ELM327 answered on this Wi-Fi network")
    }

    /** Connects to [endpoint] if an ELM327 answers there; null if nothing does, or something else does. */
    private fun connectToElm(network: Network, endpoint: WifiEndpoint): ObdTransport? {
        val transport = try {
            WifiObdTransport.connect(network, endpoint)
        } catch (e: IOException) {
            return null
        }
        if (!Elm327(transport).looksLikeElm327()) {
            transport.close()
            return null
        }
        lastWifiEndpoint = endpoint
        return transport
    }

    private suspend fun waitForEcu(elm: Elm327, adapter: ObdAdapter): Set<Int> {
        var attempt = 0
        while (true) {
            // Trying every protocol by hand takes a minute or more with the ignition off, so only
            // now and then; the adapter's own search runs every time.
            val probe = attempt % PROBE_EVERY == 0
            if (probe) log.add("Asking the car for data, trying each protocol if the adapter's search fails")
            elm.connectToEcu(probe)?.let { return it }
            attempt++
            val volts = elm.readVoltage()
            log.add("The car didn't answer" + (volts?.let { ", adapter reads $it V at the port" } ?: ""))
            _status.value = ObdStatus(ObdLink.NoEcu, adapter.name, batteryVolts = volts)
            delay(ECU_RETRY_MILLIS)
        }
    }

    /**
     * Reads speed and rpm every cycle and everything else less often, estimating the gear with
     * [estimator]. Returns when the car goes quiet. [everyFewSeconds] runs alongside the log snapshot.
     */
    private suspend fun poll(elm: Elm327, supported: Set<Int>, estimator: GearEstimator, everyFewSeconds: () -> Unit) {
        var readings = ObdReadings()
        var cycle = 0
        var misses = 0
        var lastSample = SystemClock.elapsedRealtime()
        var lastSnapshot = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val speed = elm.readPid(ObdPid.SPEED)?.let(ObdPid::speed)
            val rpm = elm.readPid(ObdPid.RPM)?.let(ObdPid::rpm)
            if (speed == null && rpm == null) {
                if (++misses >= MAX_MISSES) return
                continue
            }
            misses = 0

            val maf = elm.readSupported(supported, ObdPid.MAF, ObdPid::maf)
            val manifold = if (maf == null) elm.readSupported(supported, ObdPid.INTAKE_PRESSURE, ObdPid::pressure) else null
            if (cycle % MEDIUM_EVERY == 0) {
                readings = readings.copy(
                    engineLoad = elm.readSupported(supported, ObdPid.ENGINE_LOAD, ObdPid::percent),
                    throttle = elm.readSupported(supported, ObdPid.THROTTLE, ObdPid::percent),
                )
            }
            if (cycle % SLOW_EVERY == 0) {
                readings = readings.copy(
                    coolantC = elm.readSupported(supported, ObdPid.COOLANT_TEMP, ObdPid::temperature),
                    intakeAirC = elm.readSupported(supported, ObdPid.INTAKE_AIR_TEMP, ObdPid::temperature),
                    ambientC = elm.readSupported(supported, ObdPid.AMBIENT_TEMP, ObdPid::temperature),
                    fuelLevel = elm.readSupported(supported, ObdPid.FUEL_LEVEL, ObdPid::percent),
                    batteryVolts = elm.readVoltage(),
                )
            }
            if (clearRequested) {
                clearRequested = false
                val cleared = elm.clearTroubleCodes()
                log.add(if (cleared) "Fault codes cleared" else "The car did not clear its fault codes")
                checkHealth(elm)
            } else if (cycle % HEALTH_EVERY == 0) {
                checkHealth(elm)
            }

            val now = SystemClock.elapsedRealtime()
            val flow = fuelFlow(maf, manifold, rpm, readings.intakeAirC)
            trip.add((now - lastSample) / 1000.0, speed ?: 0, flow, engineRunning = (rpm ?: 0) > 0)
            lastSample = now
            readings = readings.copy(
                speedKmh = speed,
                rpm = rpm,
                kmPerLitre = if (speed != null && flow != null) FuelMath.kmPerLitre(speed, flow) else null,
                gear = if (speed != null && rpm != null) estimator.update(speed, rpm, now) else null,
                tripKm = trip.distanceKm.toFloat(),
                tripFuelLitres = trip.fuelLitres.toFloat(),
                tripAverageKmPerLitre = trip.averageKmPerLitre,
                tripEngineSeconds = trip.engineSeconds.toLong(),
            )
            _readings.value = readings
            if (now - lastSnapshot >= SNAPSHOT_MILLIS) {
                lastSnapshot = now
                log.add(snapshot(readings, maf, manifold))
                everyFewSeconds()
            }
            cycle++
        }
    }

    /** One line of what the car reported, so a saved log shows the drive as well as the connection. */
    private fun snapshot(readings: ObdReadings, maf: Float?, manifoldKpa: Int?): String {
        fun Any?.or(unit: String) = if (this == null) "-" else "$this$unit"
        val air = maf?.let { "MAF $it g/s" } ?: manifoldKpa?.let { "MAP $it kPa" } ?: "no air flow"
        return "Data: ${readings.speedKmh.or(" km/h")}, ${readings.rpm.or(" rpm")}, gear ${readings.gear.or("")}, " +
            "coolant ${readings.coolantC.or(" C")}, load ${readings.engineLoad.or("%")}, throttle ${readings.throttle.or("%")}, " +
            "$air, fuel ${readings.fuelLevel.or("%")}, ${readings.batteryVolts.or(" V")}"
    }

    /** Fuel flow from the MAF sensor, or estimated from manifold pressure on cars without one. */
    private fun fuelFlow(maf: Float?, manifoldKpa: Int?, rpm: Int?, intakeAirC: Int?): Float? = when {
        maf != null -> FuelMath.litresPerHour(maf)
        manifoldKpa != null && rpm != null ->
            FuelMath.litresPerHour(FuelMath.airFlow(manifoldKpa, rpm, intakeAirC ?: ASSUMED_INTAKE_C, profile))
        else -> null
    }

    private fun checkHealth(elm: Elm327) {
        val monitor = elm.readPid(ObdPid.MONITOR_STATUS) ?: return
        val count = ObdPid.troubleCodeCount(monitor) ?: 0
        val codes = if (count > 0) elm.readTroubleCodes() else emptyList()
        val pending = elm.readPendingCodes().filter { it !in codes }
        _status.update {
            it.copy(milOn = ObdPid.milOn(monitor) == true, troubleCodeCount = count, troubleCodes = codes, pendingCodes = pending)
        }
    }

    private fun <T> Elm327.readSupported(supported: Set<Int>, pid: Int, decode: (ByteArray) -> T?): T? =
        if (pid in supported) readPid(pid)?.let(decode) else null

    private class MissingPermissionException : Exception()

    private class BluetoothOffException : IOException("Bluetooth is off")

    private class NoWifiException : IOException("Not connected to Wi-Fi")

    private companion object {
        const val FIRST_RETRY_MILLIS = 2_000L
        const val MAX_RETRY_MILLIS = 15_000L
        const val ECU_RETRY_MILLIS = 5_000L
        const val PROBE_EVERY = 6
        const val SNAPSHOT_MILLIS = 10_000L
        const val GEARS_PREFIX = "gears."
        const val LOG_FOLDER = "logs"

        /** Adapter replies that mean something went wrong, worth logging even mid-drive. */
        val FAILURES = listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "BUFFER FULL", "BUS BUSY", "CAN ERROR")

        /** Cycles in a row without speed or rpm before deciding the car has gone quiet. */
        const val MAX_MISSES = 5

        const val MEDIUM_EVERY = 3
        const val SLOW_EVERY = 10
        const val HEALTH_EVERY = 150

        /** Used for the air density estimate until the intake temperature has been read. */
        const val ASSUMED_INTAKE_C = 25
    }
}
