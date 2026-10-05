package com.vivekkaushik.revv.obd

import com.vivekkaushik.revv.vehicle.CarSetup
import com.vivekkaushik.revv.vehicle.DriveSimulator
import com.vivekkaushik.revv.vehicle.Telemetry
import com.vivekkaushik.revv.vehicle.VehicleProfile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An ELM327 that isn't there: it answers the adapter's command set with the demo drive, so the
 * whole OBD-II path can be exercised on an emulator. Offered only in debug builds. With
 * [startEngine], the car is first found with the ignition on and the engine stopped, and started:
 * the starter turns it over, it catches and settles to idle, and only then does the drive set off.
 */
class SimulatedElm327(
    private val profile: VehicleProfile = VehicleProfile.SWIFT_VXI_2015,
    private val startEngine: Boolean = false,
    private val clock: () -> Long = System::nanoTime,
) : ObdTransport {

    private val car = DriveSimulator().apply { ignite(skipSequence = true) }
    private val startNanos = clock()
    private var lastStepNanos = startNanos
    private var reply: String? = null
    private var searched = false

    /** When the car was first asked for data, which is when the start begins. */
    private var askedFrom: Long? = null

    /** Codes cleared by the driver stay away until the next two-minute cycle begins. */
    private var clearedCycle = -1L

    override fun send(command: String) {
        Thread.sleep(LATENCY_MILLIS)
        reply = answer(command.trim().uppercase())
    }

    override fun receive(timeoutMillis: Long): String? = reply.also { reply = null }

    override fun close() = Unit

    private fun answer(command: String): String = when {
        command == "ATZ" -> "\r\rELM327 v1.5\r\r"
        command == "ATRV" -> "${starting()?.volts ?: if (batteryLow()) "11.4" else "14.1"}V\r\r"
        command == "ATDPN" -> "A6\r\r"
        command.startsWith("AT") -> "OK\r\r"
        command == "04" -> "44\r\r".also { clearedCycle = cycle() }
        command == "07" -> if (pending()) "47010420\r\r" else "4700\r\r"
        command == "03" -> if (faulty()) "430201710301\r\r" else "4300\r\r"
        // An optional fifth digit asks for that many replies, which one engine computer always meets.
        (command.length == 4 || command.length == 5 && command[4].isDigit()) && command.startsWith("01") -> {
            val pid = command.substring(2, 4)
            val data = data(pid.toInt(16))
            val search = if (searched) "" else "SEARCHING...\r".also { searched = true }
            if (data == null) "${search}NO DATA\r\r" else "${search}41$pid$data\r\r"
        }
        else -> "?\r\r"
    }

    /** The battery sags for half of every minute, so the low-battery warning can be seen without a faulty car. */
    private fun batteryLow(): Boolean = (clock() - startNanos) / 1_000_000_000L % 60 >= 30

    /** Two faults (P0171, P0301) for the second minute of every two, so the codes can be seen on a healthy demo car. */
    private fun faulty(): Boolean = secondInCycle() >= 60 && cycle() != clearedCycle

    /** An unconfirmed catalytic converter fault (P0420) shows up for the half minute before the real ones. */
    private fun pending(): Boolean = secondInCycle() in 30..59 && cycle() != clearedCycle

    private fun elapsedSeconds() = (clock() - startNanos) / 1_000_000_000L
    private fun secondInCycle() = elapsedSeconds() % 120
    private fun cycle() = elapsedSeconds() / 120

    private fun data(pid: Int): String? {
        if (askedFrom == null) askedFrom = clock()
        starting()?.let { return startData(pid, it) ?: data(pid, advance()) }
        return data(pid, advance())
    }

    private fun data(pid: Int, frame: Telemetry): String? {
        val speed = frame.speedKmh
        val gear = frame.gearIndex
        // Rpm from the gear ratios rather than the demo's own curve, so the gear estimate works.
        val rpm = if (speed >= 1 && gear in 1..GEARING.size) {
            (speed * GEARING[gear - 1]).roundToInt()
        } else {
            DriveSimulator.IDLE_RPM.roundToInt()
        }
        return when (pid) {
            ObdPid.SUPPORTED_01_20 -> "983A8001"
            0x20 -> "00020001"
            0x40 -> "44000000"
            ObdPid.MONITOR_STATUS -> if (faulty()) "82076500" else "00076500"
            ObdPid.ENGINE_LOAD -> byte(frame.engineLoad * 255 / 100)
            ObdPid.COOLANT_TEMP -> byte(90 + 40)
            ObdPid.INTAKE_PRESSURE -> byte(30 + frame.throttle * 7 / 10)
            ObdPid.RPM -> byte(rpm * 4 / 256) + byte(rpm * 4 % 256)
            ObdPid.SPEED -> byte(speed)
            ObdPid.INTAKE_AIR_TEMP -> byte(35 + 40)
            ObdPid.THROTTLE -> byte(frame.throttle * 255 / 100)
            ObdPid.FUEL_LEVEL -> byte(62 * 255 / 100)
            ObdPid.AMBIENT_TEMP -> byte(31 + 40)
            else -> null
        }
    }

    /** What the engine reads while it starts, or null for the rest, which reads as the parked drive does. */
    private fun startData(pid: Int, start: Start): String? = when (pid) {
        ObdPid.RPM -> byte(start.rpm * 4 / 256) + byte(start.rpm * 4 % 256)
        ObdPid.SPEED -> byte(0)
        ObdPid.ENGINE_LOAD -> byte(start.load * 255 / 100)
        ObdPid.INTAKE_PRESSURE -> byte(start.manifoldKpa)
        ObdPid.THROTTLE -> byte(0)
        else -> null
    }

    /**
     * The engine starting, or null once it has and the drive is under way: stopped with the
     * ignition on, turned over by the starter with the battery sagging, then catching with a flare
     * of revs that settles to idle, and idling a moment before setting off.
     */
    private fun starting(): Start? {
        if (!startEngine) return null
        val seconds = askedFrom?.let { (clock() - it) / 1e9 } ?: 0.0
        return when {
            seconds < STOPPED_SECONDS -> Start(rpm = 0, load = 0, manifoldKpa = 100, volts = "12.4")
            seconds < CAUGHT_SECONDS -> {
                // Each compression slows the starter down.
                val rpm = 230 + 25 * sin(seconds * 2 * Math.PI * 7)
                Start(rpm.roundToInt(), load = 12, manifoldKpa = 85, volts = "10.4")
            }
            seconds < SET_OFF_SECONDS -> {
                val since = seconds - CAUGHT_SECONDS
                val rpm = if (since < FLARE_SECONDS) {
                    CRANKING_RPM + (FLARE_RPM - CRANKING_RPM) * since / FLARE_SECONDS
                } else {
                    IDLE + (FLARE_RPM - IDLE) * exp(-(since - FLARE_SECONDS) / SETTLE_SECONDS)
                }
                val flaring = since < FLARE_SECONDS * 2
                Start(rpm.roundToInt(), load = if (flaring) 40 else 22, manifoldKpa = if (flaring) 50 else 30, volts = "14.1")
            }
            else -> null
        }
    }

    private class Start(val rpm: Int, val load: Int, val manifoldKpa: Int, val volts: String)

    /** Runs the demo drive forward to now, in steps no longer than the simulator accepts; held while the engine starts. */
    private fun advance() = run {
        val now = clock()
        if (starting() != null) lastStepNanos = now
        var remaining = (now - lastStepNanos) / 1_000_000_000f
        lastStepNanos = now
        var frame = car.step(0f, demoDrive = true, reversing = false)
        while (remaining > 0f) {
            frame = car.step(min(remaining, 0.05f), demoDrive = true, reversing = false)
            remaining -= 0.05f
        }
        frame
    }

    private fun byte(value: Int): String = ObdResponse.hex(value.coerceIn(0, 255))

    companion object {
        private val engineStarted = AtomicBoolean()

        /**
         * True the first time it's asked in this run of Revv, and never again: the simulated car
         * starts its engine when Revv starts, not each time the adapter reconnects.
         */
        fun startsEngine(): Boolean = !engineStarted.getAndSet(true)

        /** The demo drive is a Swift's: rpm per km/h in each of its gears. */
        private val GEARING = CarSetup.SWIFT_VXI_2015.rpmPerKmh()

        /** Roughly what a Bluetooth clone on a CAN car takes per command. */
        private const val LATENCY_MILLIS = 40L

        /**
         * The start: when the starter turns, once Revv's own start-up sweep of the gauges is over,
         * when the engine catches, and when the drive sets off.
         */
        private const val STOPPED_SECONDS = 5.0
        private const val CAUGHT_SECONDS = 6.0
        private const val SET_OFF_SECONDS = 10.5
        private const val CRANKING_RPM = 230.0
        private const val FLARE_RPM = 1450.0
        private const val FLARE_SECONDS = 0.3
        private const val SETTLE_SECONDS = 0.6
        private const val IDLE = DriveSimulator.IDLE_RPM.toDouble()
    }
}
