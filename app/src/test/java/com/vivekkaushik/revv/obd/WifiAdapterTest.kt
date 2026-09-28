package com.vivekkaushik.revv.obd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAdapterTest {

    @Test
    fun candidates_tryTheLastWorkingAddressThenDefaultsThenTheGateway() {
        val candidates = WifiEndpoint.candidates(
            lastWorking = WifiEndpoint("192.168.4.1", 35000),
            gateway = "192.168.0.1",
        ).map { it.toString() }
        assertEquals(
            listOf("192.168.4.1:35000", "192.168.0.10:35000", "192.168.0.1:35000", "192.168.0.1:23", "192.168.0.10:23"),
            candidates,
        )
    }

    @Test
    fun candidates_dropDuplicatesWhenTheAdapterIsTheGateway() {
        val candidates = WifiEndpoint.candidates(lastWorking = null, gateway = "192.168.0.10").map { it.toString() }
        assertEquals(listOf("192.168.0.10:35000", "192.168.0.10:23"), candidates)
    }

    @Test
    fun streamTransport_readsUpToThePromptAndKeepsTheRest() {
        val input = ByteArrayInputStream("410D32\r\r>STALE>".toByteArray())
        val output = ByteArrayOutputStream()
        val transport = StreamObdTransport(input, output, onClose = {})
        transport.send("010D")
        assertEquals("010D\r", output.toString())
        assertEquals("410D32\r\r", transport.receive(timeoutMillis = 500))
        assertEquals("STALE", transport.receive(timeoutMillis = 500))
    }

    @Test
    fun streamTransport_timesOutWithoutAPrompt() {
        val transport = StreamObdTransport(ByteArrayInputStream("SEARCHING...".toByteArray()), ByteArrayOutputStream(), onClose = {})
        assertNull(transport.receive(timeoutMillis = 100))
    }

    @Test
    fun looksLikeElm327_acceptsAdaptersAndRejectsOtherServers() {
        assertTrue(Elm327(reply("\r\rELM327 v1.5\r\r")).looksLikeElm327())
        assertTrue(Elm327(reply("ELM327 v2.1\r")).looksLikeElm327())
        assertTrue(Elm327(reply("STN1110 v4.0.1\r")).looksLikeElm327())
        assertFalse(Elm327(reply("Router")).looksLikeElm327())
        assertFalse(Elm327(reply(null)).looksLikeElm327())
    }

    @Test
    fun restore_readsAdaptersSavedBeforeWifiSupport() {
        assertEquals(ObdAdapter.Kind.Bluetooth, ObdAdapter.restore(null, "00:1D:A5:68:98:8B", "OBDII").kind)
        assertEquals(ObdAdapter.Kind.Simulated, ObdAdapter.restore(null, "simulated", "Simulated").kind)
        assertEquals(ObdAdapter.WiFi, ObdAdapter.restore("WiFi", "auto", "Wi-Fi ELM327"))
    }

    /** A transport that answers every command with [text], or never answers when it's null. */
    private fun reply(text: String?) = object : ObdTransport {
        override fun send(command: String) = Unit
        override fun receive(timeoutMillis: Long): String? = text
        override fun close() = Unit
    }
}
