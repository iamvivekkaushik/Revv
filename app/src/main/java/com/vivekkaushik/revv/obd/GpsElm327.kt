package com.vivekkaushik.revv.obd

import android.content.Context
import android.os.SystemClock
import com.vivekkaushik.revv.nav.DeviceLocation
import com.vivekkaushik.revv.nav.Fix
import com.vivekkaushik.revv.nav.Geo
import com.vivekkaushik.revv.vehicle.CarSetup
import com.vivekkaushik.revv.vehicle.GpsCar
import com.vivekkaushik.revv.vehicle.VehicleProfile
import kotlin.math.roundToInt

/**
 * An ELM327 that isn't there, for driving without an adapter: road speed comes from the head
 * unit's GPS, and the gear, revs and load from [GpsCar], so the cluster, gear indicator and engine
 * sound all work from GPS alone. It answers only what GPS can stand in for: speed, rpm, load and
 * throttle, never temperatures, fuel, the battery or fault codes. Until the first fix, and once
 * fixes stop for a while, the "car" doesn't answer.
 */
class GpsElm327(
    private val car: GpsCar,
    /** The car as set up in Settings, read on every request so a change applies at once. */
    private val setup: () -> CarSetup,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val latencyMillis: Long = LATENCY_MILLIS,
    private val onClose: () -> Unit = {},
) : ObdTransport {

    private var reply: String? = null

    override fun send(command: String) {
        // Like a real adapter's round trip, which also keeps the polling loop from spinning.
        if (latencyMillis > 0) Thread.sleep(latencyMillis)
        reply = answer(command.trim().uppercase())
    }

    override fun receive(timeoutMillis: Long): String? = reply.also { reply = null }

    override fun close() = onClose()

    private fun answer(command: String): String = when {
        command == "ATZ" -> "\r\rELM327 v1.5 (GPS)\r\r"
        // No port to measure and no bus to name.
        command == "ATRV" || command == "ATDPN" -> "?\r\r"
        command.startsWith("AT") -> "OK\r\r"
        command == "03" -> "4300\r\r"
        command == "07" -> "4700\r\r"
        command == "04" -> "44\r\r"
        // An optional fifth digit asks for that many replies; there's only ever the one.
        (command.length == 4 || command.length == 5 && command[4].isDigit()) && command.startsWith("01") -> {
            val pid = command.substring(2, 4)
            val data = pid.toIntOrNull(16)?.let(::data)
            if (data == null) "NO DATA\r\r" else "41$pid$data\r\r"
        }
        else -> "?\r\r"
    }

    private fun data(pid: Int): String? {
        car.setup = setup()
        val state = car.at(clock()) ?: return null
        return when (pid) {
            ObdPid.SUPPORTED_01_20 -> SUPPORTED_PIDS
            ObdPid.ENGINE_LOAD -> fraction(SHUT_LOAD + (1 - SHUT_LOAD) * state.load)
            ObdPid.RPM -> byte(state.rpm * 4 / 256) + byte(state.rpm * 4 % 256)
            ObdPid.SPEED -> byte(state.speedKmh.roundToInt())
            ObdPid.THROTTLE -> fraction(SHUT_THROTTLE + (OPEN_THROTTLE - SHUT_THROTTLE) * state.load)
            else -> null
        }
    }

    private fun fraction(value: Double): String = byte((value * 255).roundToInt())

    private fun byte(value: Int): String = ObdResponse.hex(value.coerceIn(0, 255))

    companion object {
        /** Engine load (04), rpm (0C), speed (0D) and throttle (11), and nothing past 0x20. */
        const val SUPPORTED_PIDS = "10188000"

        /** Each request's round trip: a reading every ~100 ms, more often than GPS has anything new. */
        private const val LATENCY_MILLIS = 30L

        /** Calculated load with the throttle shut, and throttle position shut and wide open. */
        private const val SHUT_LOAD = 0.22
        private const val SHUT_THROTTLE = 0.14
        private const val OPEN_THROTTLE = 0.82

        /** Starts listening to the head unit's GPS; null without location access. */
        fun open(context: Context, setup: () -> CarSetup, profile: VehicleProfile): GpsElm327? {
            val location = DeviceLocation(context)
            if (!location.permitted) return null
            val car = GpsCar(setup(), profile)
            val speeds = GpsSpeed()
            location.start(
                onFix = { fix ->
                    val now = SystemClock.elapsedRealtimeNanos()
                    speeds.of(fix, now)?.let { car.fix(it, now) }
                },
                onChange = {},
            )
            return GpsElm327(car, setup, onClose = location::stop)
        }
    }
}

/**
 * Road speed from GPS fixes, km/h: the receiver's own Doppler speed where it gives one, else the
 * distance between two close fixes over the time between them. Null for a fix that says nothing
 * reliable about speed, such as a network fix or the last known position from long ago.
 */
class GpsSpeed {

    private var previous: Fix? = null

    fun of(fix: Fix, nowNanos: Long): Double? {
        if (fix.elapsedNanos != 0L && nowNanos - fix.elapsedNanos > MAX_AGE_NANOS) return null
        val before = previous
        previous = fix
        fix.speedMps?.let { return it * KMH_PER_MPS }
        if (before == null || !fix.precise() || !before.precise() || fix.elapsedNanos == 0L || before.elapsedNanos == 0L) return null
        val seconds = (fix.elapsedNanos - before.elapsedNanos) / 1e9
        if (seconds !in MIN_SECONDS..MAX_SECONDS) return null
        return Geo.distance(before.position, fix.position) / seconds * KMH_PER_MPS
    }

    private fun Fix.precise() = (accuracyMetres ?: Double.MAX_VALUE) <= MAX_ACCURACY_METRES

    private companion object {
        const val KMH_PER_MPS = 3.6
        const val MAX_AGE_NANOS = 5_000_000_000L
        const val MAX_ACCURACY_METRES = 20.0
        const val MIN_SECONDS = 0.5
        const val MAX_SECONDS = 5.0
    }
}
