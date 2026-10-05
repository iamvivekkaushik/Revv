package com.vivekkaushik.revv.obd

import com.vivekkaushik.revv.nav.Fix
import com.vivekkaushik.revv.nav.LatLon
import com.vivekkaushik.revv.vehicle.CarSetup
import com.vivekkaushik.revv.vehicle.GpsCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GpsElm327Test {

    private var now = 5_000_000_000L
    private val car = GpsCar(CarSetup.SWIFT_VXI_2015)
    private val elm = Elm327(GpsElm327(car, { CarSetup.SWIFT_VXI_2015 }, clock = { now }, latencyMillis = 0))

    @Test
    fun theCarDoesNotAnswerUntilGpsHasAFix() {
        elm.initialize()
        assertNull(elm.connectToEcu())
        assertNull(elm.readVoltage())
    }

    @Test
    fun answersSpeedRevsLoadAndThrottleOnly() {
        elm.initialize()
        car.fix(50.0, now)
        val supported = elm.connectToEcu()
        assertEquals(setOf(ObdPid.ENGINE_LOAD, ObdPid.RPM, ObdPid.SPEED, ObdPid.THROTTLE), supported)
        // No bus behind it, so no protocol to show.
        assertNull(elm.protocolNumber)

        val state = car.at(now)!!
        assertEquals(50, elm.readPid(ObdPid.SPEED)?.let(ObdPid::speed))
        assertEquals(state.rpm, elm.readPid(ObdPid.RPM)?.let(ObdPid::rpm))
        assertNotNull(elm.readPid(ObdPid.THROTTLE)?.let(ObdPid::percent))
        assertNull(elm.readPid(ObdPid.COOLANT_TEMP))
        assertNull(elm.readPid(ObdPid.MONITOR_STATUS))
        assertEquals(emptyList<String>(), elm.readTroubleCodes())
    }

    @Test
    fun aShutThrottleWhenStandingStill() {
        elm.initialize()
        car.fix(0.0, now)
        elm.connectToEcu()
        assertEquals(0, elm.readPid(ObdPid.SPEED)?.let(ObdPid::speed))
        assertEquals(GpsCar.IDLE_RPM.toInt(), elm.readPid(ObdPid.RPM)?.let(ObdPid::rpm))
        assertEquals(14, elm.readPid(ObdPid.THROTTLE)?.let(ObdPid::percent))
    }

    @Test
    fun speedFromTheReceiverWhereItGivesOne() {
        val speeds = GpsSpeed()
        assertEquals(36.0, speeds.of(fix(0.0, speedMps = 10.0, at = now), now)!!, 1e-9)
    }

    @Test
    fun speedFromDistanceOverTimeWithoutOne() {
        val speeds = GpsSpeed()
        assertNull(speeds.of(fix(0.0, at = now), now))
        // About 10 m north a second later: 36 km/h.
        val later = now + 1_000_000_000L
        assertEquals(36.0, speeds.of(fix(10 / 111_195.0, at = later), later)!!, 0.5)
    }

    @Test
    fun ignoresStaleAndRoughFixes() {
        val speeds = GpsSpeed()
        // The last known position from before Revv started.
        assertNull(speeds.of(fix(0.0, speedMps = 10.0, at = now - 60_000_000_000L), now))
        // A network fix: hundreds of metres out, so its movement says nothing about speed.
        speeds.of(fix(0.0, accuracy = 300.0, at = now), now)
        val later = now + 1_000_000_000L
        assertNull(speeds.of(fix(0.001, accuracy = 300.0, at = later), later))
    }

    private fun fix(lat: Double, speedMps: Double? = null, accuracy: Double = 5.0, at: Long) =
        Fix(LatLon(28.45 + lat, 77.08), bearing = null, speedMps = speedMps, accuracyMetres = accuracy, timeMillis = 0, elapsedNanos = at)
}
