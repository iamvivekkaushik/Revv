package com.vivekkaushik.revv.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Network
import android.os.SystemClock
import com.vivekkaushik.revv.vehicle.FuelMath
import com.vivekkaushik.revv.vehicle.GearEstimator
import com.vivekkaushik.revv.vehicle.TripComputer
import com.vivekkaushik.revv.vehicle.VehicleProfile
import java.io.IOException
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
    private val gears = GearEstimator(profile)
    private var job: Job? = null

    /** Where the Wi-Fi adapter answered last time, tried first on reconnects. */
    @Volatile
    private var lastWifiEndpoint: WifiEndpoint? = null

    @Volatile
    private var transport: ObdTransport? = null

    /** Connects to [adapter] and stays connected, reconnecting as needed, until [stop]. */
    fun start(adapter: ObdAdapter) {
        stop()
        job = scope.launch(Dispatchers.IO) { keepConnected(adapter) }
    }

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
        var retryMillis = FIRST_RETRY_MILLIS
        while (true) {
            try {
                connectAndPoll(adapter) { retryMillis = FIRST_RETRY_MILLIS }
            } catch (e: MissingPermissionException) {
                _status.value = ObdStatus(ObdLink.NeedsPermission, adapter.name)
                return
            } catch (e: SecurityException) {
                _status.value = ObdStatus(ObdLink.NeedsPermission, adapter.name)
                return
            } catch (e: BluetoothOffException) {
                _status.value = ObdStatus(ObdLink.BluetoothOff, adapter.name)
            } catch (e: NoWifiException) {
                _status.value = ObdStatus(ObdLink.NoWifi, adapter.name)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                _status.value = ObdStatus(ObdLink.Retrying, adapter.name, problem = e.message ?: "Connection lost")
            }
            _readings.value = null
            delay(retryMillis)
            retryMillis = (retryMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
        }
    }

    private suspend fun connectAndPoll(adapter: ObdAdapter, onLive: () -> Unit) {
        _status.value = ObdStatus(ObdLink.Connecting, adapter.name)
        val link = open(adapter)
        transport = link
        val endpoint = if (adapter.kind == ObdAdapter.Kind.WiFi) lastWifiEndpoint?.toString() else null
        try {
            currentCoroutineContext().ensureActive()
            val elm = Elm327(link)
            elm.initialize()
            while (true) {
                val supported = waitForEcu(elm, adapter)
                _status.value = ObdStatus(ObdLink.Live, adapter.name, protocol = elm.protocolName, endpoint = endpoint)
                onLive()
                poll(elm, supported)
                // The car stopped answering (ignition off). Keep the adapter and wait for it again.
                _readings.value = null
            }
        } finally {
            runCatching { link.close() }
            if (transport === link) transport = null
        }
    }

    private fun open(adapter: ObdAdapter): ObdTransport = when (adapter.kind) {
        ObdAdapter.Kind.Simulated -> SimulatedElm327(profile)
        ObdAdapter.Kind.Bluetooth -> openBluetooth(adapter)
        ObdAdapter.Kind.BluetoothLe -> openBle(adapter)
        ObdAdapter.Kind.WiFi -> openWifi(adapter)
    }

    private fun openBluetooth(adapter: ObdAdapter): ObdTransport {
        val (bluetooth, device) = bluetoothDevice(adapter)
        return BluetoothObdTransport.connect(bluetooth, device)
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
        while (true) {
            elm.connectToEcu()?.let { return it }
            _status.value = ObdStatus(ObdLink.NoEcu, adapter.name, batteryVolts = elm.readVoltage())
            delay(ECU_RETRY_MILLIS)
        }
    }

    /** Reads speed and rpm every cycle and everything else less often. Returns when the car goes quiet. */
    private suspend fun poll(elm: Elm327, supported: Set<Int>) {
        var readings = ObdReadings()
        var cycle = 0
        var misses = 0
        var lastSample = SystemClock.elapsedRealtime()
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
            if (cycle % HEALTH_EVERY == 0) checkHealth(elm)

            val now = SystemClock.elapsedRealtime()
            val flow = fuelFlow(maf, manifold, rpm, readings.intakeAirC)
            trip.add((now - lastSample) / 1000.0, speed ?: 0, flow, engineRunning = (rpm ?: 0) > 0)
            lastSample = now
            readings = readings.copy(
                speedKmh = speed,
                rpm = rpm,
                kmPerLitre = if (speed != null && flow != null) FuelMath.kmPerLitre(speed, flow) else null,
                gear = if (speed != null && rpm != null) gears.estimate(speed, rpm) else null,
                tripKm = trip.distanceKm.toFloat(),
                tripFuelLitres = trip.fuelLitres.toFloat(),
                tripAverageKmPerLitre = trip.averageKmPerLitre,
                tripEngineSeconds = trip.engineSeconds.toLong(),
            )
            _readings.value = readings
            cycle++
        }
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
        _status.update {
            it.copy(milOn = ObdPid.milOn(monitor) == true, troubleCodeCount = count, troubleCodes = codes)
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

        /** Cycles in a row without speed or rpm before deciding the car has gone quiet. */
        const val MAX_MISSES = 5

        const val MEDIUM_EVERY = 3
        const val SLOW_EVERY = 10
        const val HEALTH_EVERY = 150

        /** Used for the air density estimate until the intake temperature has been read. */
        const val ASSUMED_INTAKE_C = 25
    }
}
