package com.vivekkaushik.revv.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdResponseTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun payload_readsACompactReply() {
        assertArrayEquals(bytes(0x32), ObdResponse.payload("410D32\r\r", 0x01, ObdPid.SPEED))
    }

    @Test
    fun payload_toleratesSpacesSearchingAndBusInit() {
        val raw = "SEARCHING...\rBUS INIT: ...OK\r41 0C 1A F8 \r\r"
        assertArrayEquals(bytes(0x1A, 0xF8), ObdResponse.payload(raw, 0x01, ObdPid.RPM))
    }

    @Test
    fun payload_isNullWhenTheCarDoesNotAnswer() {
        assertNull(ObdResponse.payload("NO DATA\r\r", 0x01, ObdPid.SPEED))
        assertNull(ObdResponse.payload("SEARCHING...\rUNABLE TO CONNECT\r\r", 0x01, 0x00))
        assertNull(ObdResponse.payload("?\r\r", 0x01, ObdPid.SPEED))
    }

    @Test
    fun payload_skipsAnswersToOtherPids() {
        assertArrayEquals(bytes(0x40), ObdResponse.payload("410C0FA0\r410D40\r", 0x01, ObdPid.SPEED))
    }

    @Test
    fun messages_stitchesMultiFrameCanReplies() {
        // A 10-byte reply: frames are joined, then cut to the announced length, dropping padding.
        val raw = "00A\r0:430401710300\r1:0C3000000000AA\r\r"
        assertEquals(listOf("4304017103000C300000"), ObdResponse.messages(raw))
    }

    @Test
    fun supportedPids_decodesTheBitmask() {
        val pids = ObdResponse.supportedPids(bytes(0x98, 0x3A, 0x80, 0x01), base = 0x00)
        assertEquals(setOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x11, 0x20), pids)
        assertEquals(setOf(0x2F, 0x40), ObdResponse.supportedPids(bytes(0x00, 0x02, 0x00, 0x01), base = 0x20))
    }

    @Test
    fun protocol_isReadFromAtdpn() {
        assertEquals(6, ObdResponse.protocolNumber("A6\r\r"))
        assertEquals(3, ObdResponse.protocolNumber("3\r\r"))
        assertTrue(ObdResponse.isCan(6))
        assertFalse(ObdResponse.isCan(5))
        assertEquals("ISO 14230-4 KWP (fast init)", ObdResponse.protocolName(5))
    }

    @Test
    fun voltage_isReadFromAtrv() {
        assertEquals(12.6f, ObdResponse.voltage("12.6V\r\r")!!, 0.001f)
        assertNull(ObdResponse.voltage("?\r\r"))
    }

    @Test
    fun troubleCode_decodesSystemAndDigits() {
        assertEquals("P0171", ObdResponse.troubleCode(0x01, 0x71))
        assertEquals("P0300", ObdResponse.troubleCode(0x03, 0x00))
        assertEquals("U0100", ObdResponse.troubleCode(0xC1, 0x00))
        assertEquals("C1234", ObdResponse.troubleCode(0x52, 0x34))
    }

    @Test
    fun troubleCodes_onCanSkipTheCountByte() {
        assertEquals(listOf("P0171", "P0300"), ObdResponse.troubleCodes("43020171 0300\r\r", can = true))
        assertEquals(emptyList<String>(), ObdResponse.troubleCodes("4300\r\r", can = true))
    }

    @Test
    fun troubleCodes_onOlderProtocolsReadThreeSlotsPerLine() {
        val raw = "43 01 71 03 00 00 00\r43 04 20 00 00 00 00\r"
        assertEquals(listOf("P0171", "P0300", "P0420"), ObdResponse.troubleCodes(raw, can = false))
    }
}
