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
    fun initialize_triesLastTimesProtocolFirst() {
        val transport = ScriptedTransport(mapOf("ATZ" to "ELM327 v2.1\r\r"))
        Elm327(transport).initialize(preferredProtocol = 5)
        assertEquals("ATSPA5", transport.sent.last())
    }

    /**
     * A clone on a KWP2000 car: its automatic search gives up, but told the protocol it connects.
     * Remembers which protocol it was told, as the real thing does.
     */
    private class KLineClone : ObdTransport {
        val sent = mutableListOf<String>()
        private var protocol = "0"
        private var pending: String? = null

        override fun send(command: String) {
            sent += command
            pending = when {
                command.startsWith("ATSP") -> "OK\r\r".also { protocol = command.removePrefix("ATSP") }
                command == "ATDPN" -> "$protocol\r\r"
                command == "0100" && protocol == "5" -> "BUS INIT: ...OK\r4100BE1FA813\r\r"
                command == "0100" -> "SEARCHING...\rUNABLE TO CONNECT\r\r"
                command == "0120" && protocol == "5" -> "NO DATA\r\r"
                else -> "OK\r\r"
            }
        }

        override fun receive(timeoutMillis: Long): String? = pending.also { pending = null }

        override fun close() = Unit
    }

    @Test
    fun connectToEcu_probesEachProtocolWhenTheSearchFails() {
        val clone = KLineClone()
        val elm = Elm327(clone)
        val supported = elm.connectToEcu(probe = true)
        assertNotNull(supported)
        assertTrue(ObdPid.SPEED in supported!!)
        assertEquals(5, elm.protocolNumber)
        assertEquals("ISO 14230-4 KWP (fast init)", elm.protocolName)
        // CAN first, being quick to rule out, then K-line with the fast KWP init.
        assertEquals(listOf("0100", "ATSP6", "0100"), clone.sent.take(3))
        assertEquals(listOf("ATSP6", "ATSP8", "ATSP7", "ATSP9", "ATSP5"), clone.sent.filter { it.startsWith("ATSP") })
    }

    /**
     * The Mini V2.1 clone Revv was first tried with: after a failed search it says UNABLE TO CONNECT twice,
     * the spare either already waiting ([lateSpare] false) or turning up after the next command.
     * Answers on KWP2000 once told to use it.
     */
    private class DoubleTalkingClone(private val lateSpare: Boolean) : ObdTransport {
        val sent = mutableListOf<String>()
        private val replies = ArrayDeque<String>()
        private var spare: String? = null
        private var protocol = "0"

        override fun send(command: String) {
            sent += command
            spare?.let { replies += it }
            spare = null
            when {
                command.startsWith("ATSP") -> replies += "OK\r\r".also { protocol = command.removePrefix("ATSP") }
                command == "ATDPN" -> replies += "$protocol\r\r"
                command == "0100" && protocol == "5" -> replies += "BUS INIT: ...OK\r4100BE1FA813\r\r"
                command == "0100" -> {
                    replies += "SEARCHING...\rUNABLE TO CONNECT\r\r"
                    if (lateSpare) spare = "UNABLE TO CONNECT\r\r" else replies += "UNABLE TO CONNECT\r\r"
                }
                else -> replies += "NO DATA\r\r"
            }
        }

        override fun receive(timeoutMillis: Long): String? = replies.removeFirstOrNull()

        override fun discard(): String = replies.joinToString("").also { replies.clear() }

        override fun close() = Unit
    }

    @Test
    fun connectToEcu_dropsASpareAnswerAlreadyWaiting() {
        val clone = DoubleTalkingClone(lateSpare = false)
        val elm = Elm327(clone)
        assertNotNull(elm.connectToEcu(probe = true))
        assertEquals(5, elm.protocolNumber)
    }

    @Test
    fun connectToEcu_skipsASpareAnswerArrivingLate() {
        val heard = mutableListOf<Pair<String, String?>>()
        val clone = DoubleTalkingClone(lateSpare = true)
        val elm = Elm327(clone) { command, reply -> heard += command to reply }
        assertNotNull(elm.connectToEcu(probe = true))
        assertEquals(5, elm.protocolNumber)
        // The spare shows in the log for what it is, and ATSP6 still gets its own OK.
        assertEquals("(leftover)" to "UNABLE TO CONNECT\r\r", heard[1])
        assertEquals("ATSP6" to "OK\r\r", heard[2])
    }

    @Test
    fun aLateOk_isNotTakenForTheCarsAnswer() {
        var replies = ArrayDeque<String>()
        val transport = object : ObdTransport {
            override fun send(command: String) {
                // The adapter's OK to the last setting only turns up with the car's answer.
                if (command == "010D") replies = ArrayDeque(listOf("OK\r\r", "410D3C\r\r"))
            }

            override fun receive(timeoutMillis: Long): String? = replies.removeFirstOrNull()

            override fun close() = Unit
        }
        assertArrayEquals(byteArrayOf(0x3C), Elm327(transport).readPid(ObdPid.SPEED))
    }

    @Test
    fun connectToEcu_withoutProbing_leavesTheProtocolAlone() {
        val clone = KLineClone()
        assertNull(Elm327(clone).connectToEcu())
        assertEquals(listOf("0100"), clone.sent)
    }

    @Test
    fun connectToEcu_goesBackToSearchingWhenNothingAnswers() {
        val transport = ScriptedTransport(mapOf("0100" to "SEARCHING...\rUNABLE TO CONNECT\r\r"))
        assertNull(Elm327(transport).connectToEcu(probe = true))
        assertEquals("ATSP0", transport.sent.last())
    }

    @Test
    fun trace_hearsEveryCommandAndReply() {
        val heard = mutableListOf<Pair<String, String?>>()
        val transport = ScriptedTransport(mapOf("010D" to "410D3C\r\r"))
        Elm327(transport) { command, reply -> heard += command to reply }.readPid(ObdPid.SPEED)
        assertEquals(listOf("010D" to "410D3C\r\r"), heard)
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
