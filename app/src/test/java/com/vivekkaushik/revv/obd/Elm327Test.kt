package com.vivekkaushik.revv.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {

    /** Answers from a script and records what was sent. Unscripted commands get "?". */
    private class ScriptedTransport(private val replies: Map<String, String>) : ObdTransport {
        val sent = mutableListOf<String>()
        private var pending: String? = null

        override fun send(command: String) {
            sent += command
            pending = replies[command] ?: "?\r\r"
        }

        override fun receive(timeoutMillis: Long): String? = pending.also { pending = null }

        override fun close() = Unit
    }

    @Test
    fun initialize_configuresCompactHeaderlessReplies() {
        val transport = ScriptedTransport(mapOf("ATZ" to "ELM327 v1.5\r\r"))
        Elm327(transport).initialize()
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"), transport.sent)
    }

    @Test
    fun connectToEcu_walksTheSupportedPidRanges() {
        val transport = ScriptedTransport(
            mapOf(
                "0100" to "SEARCHING...\r4100983A8001\r\r",
                "0120" to "412000020001\r\r",
                "0140" to "414044000000\r\r",
                "ATDPN" to "A6\r\r",
            ),
        )
        val elm = Elm327(transport)
        val supported = elm.connectToEcu()
        assertNotNull(supported)
        assertTrue(ObdPid.SPEED in supported!!)
        assertTrue(ObdPid.FUEL_LEVEL in supported)
        assertTrue(ObdPid.AMBIENT_TEMP in supported)
        assertEquals("ISO 15765-4 CAN (11 bit, 500 kbaud)", elm.protocolName)
    }

    @Test
    fun connectToEcu_isNullWithTheIgnitionOff() {
        val transport = ScriptedTransport(mapOf("0100" to "SEARCHING...\rUNABLE TO CONNECT\r\r"))
        assertNull(Elm327(transport).connectToEcu())
    }

    @Test
    fun readPid_returnsTheDataBytes() {
        val transport = ScriptedTransport(mapOf("010D" to "410D3C\r\r"))
        assertArrayEquals(byteArrayOf(0x3C), Elm327(transport).readPid(ObdPid.SPEED))
    }

    @Test
    fun readVoltage_parsesAtrv() {
        val transport = ScriptedTransport(mapOf("ATRV" to "12.4V\r\r"))
        assertEquals(12.4f, Elm327(transport).readVoltage()!!, 0.001f)
    }

    @Test
    fun readTroubleCodes_usesTheProtocolFoundAtConnect() {
        val transport = ScriptedTransport(
            mapOf(
                "0100" to "4100983A8000\r\r",
                "ATDPN" to "A6\r\r",
                "03" to "43010171\r\r",
            ),
        )
        val elm = Elm327(transport)
        elm.connectToEcu()
        assertEquals(listOf("P0171"), elm.readTroubleCodes())
    }

    @Test
    fun theSimulatedAdapterSpeaksTheSameProtocol() {
        val elm = Elm327(SimulatedElm327())
        elm.initialize()
        val supported = elm.connectToEcu()!!
        assertTrue(ObdPid.INTAKE_PRESSURE in supported)
        assertNotNull(elm.readPid(ObdPid.SPEED)?.let(ObdPid::speed))
        assertEquals(14.1f, elm.readVoltage()!!, 0.001f)
        assertEquals(emptyList<String>(), elm.readTroubleCodes())
    }
}
