package com.vivekkaushik.revv.obd

import com.vivekkaushik.revv.vehicle.DriveSimulator
import com.vivekkaushik.revv.vehicle.VehicleProfile
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An ELM327 that isn't there: it answers the adapter's command set with the demo drive, so the
 * whole OBD-II path can be exercised on an emulator. Offered only in debug builds.
 */
class SimulatedElm327(private val profile: VehicleProfile = VehicleProfile.SWIFT_VXI_2015) : ObdTransport {

    private val car = DriveSimulator().apply { ignite(skipSequence = true) }
    private var lastStepNanos = System.nanoTime()
    private var reply: String? = null
    private var searched = false

    override fun send(command: String) {
        Thread.sleep(LATENCY_MILLIS)
        reply = answer(command.trim().uppercase())
    }

    override fun receive(timeoutMillis: Long): String? = reply.also { reply = null }

    override fun close() = Unit

    private fun answer(command: String): String = when {
        command == "ATZ" -> "\r\rELM327 v1.5\r\r"
        command == "ATRV" -> "14.1V\r\r"
        command == "ATDPN" -> "A6\r\r"
        command.startsWith("AT") -> "OK\r\r"
        command == "03" -> "4300\r\r"
        command.length == 4 && command.startsWith("01") -> {
            val pid = command.substring(2)
            val data = data(pid.toInt(16))
            val search = if (searched) "" else "SEARCHING...\r".also { searched = true }
            if (data == null) "${search}NO DATA\r\r" else "${search}41$pid$data\r\r"
        }
        else -> "?\r\r"
    }

    private fun data(pid: Int): String? {
        val frame = advance()
        val speed = frame.speedKmh
        val gear = frame.gearIndex
        // Rpm from the gear ratios rather than the demo's own curve, so the gear estimate works.
        val rpm = if (speed >= 1 && gear in 1..profile.rpmPerKmh.size) {
            (speed * profile.rpmPerKmh[gear - 1]).roundToInt()
        } else {
            DriveSimulator.IDLE_RPM.roundToInt()
        }
        return when (pid) {
            ObdPid.SUPPORTED_01_20 -> "983A8001"
            0x20 -> "00020001"
            0x40 -> "44000000"
            ObdPid.MONITOR_STATUS -> "00076500"
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

    /** Runs the demo drive forward to now, in steps no longer than the simulator accepts. */
    private fun advance() = run {
        val now = System.nanoTime()
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

    private companion object {
        /** Roughly what a Bluetooth clone on a CAN car takes per command. */
        const val LATENCY_MILLIS = 40L
    }
}
