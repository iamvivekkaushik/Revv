package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.FytAutoSerialProtocol.Event
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FytAutoSerialProtocolTest {

    @Test
    fun blink5sMessages_comeApartOnAnyRunOfLineBreaks() {
        val splitter = FytAutoSerialProtocol.LineSplitter()
        // CR LF framing, after the terminal turned each CR into LF, split across two reads.
        val first = "\n\nJH001122AABBCC\n\n\n\nA".toByteArray()
        val second = "A\n\n\r\nZB0102\r\n".toByteArray()
        assertEquals(listOf("JH001122AABBCC"), splitter.feed(first, first.size))
        assertEquals(listOf("AA", "ZB0102"), splitter.feed(second, second.size))
    }

    @Test
    fun theChannelOpensAndCloses() {
        assertEquals(Event.Connected, FytAutoSerialProtocol.parse("AA"))
        assertEquals(Event.Closed, FytAutoSerialProtocol.parse("SS"))
    }

    @Test
    fun addressesGainTheirColons() {
        assertEquals(Event.PhoneAddress("00:11:22:AA:BB:CC"), FytAutoSerialProtocol.parse("JH001122aabbcc"))
        assertEquals(Event.LocalAddress("AA:BB:CC:00:11:22"), FytAutoSerialProtocol.parse("DBAABBCC001122"))
        assertTrue(FytAutoSerialProtocol.parse("JH0011") is Event.Other)
        assertTrue(FytAutoSerialProtocol.parse("JH00112233445Z") is Event.Other)
    }

    @Test
    fun dataFromThePhone_isHex() {
        val event = FytAutoSerialProtocol.parse("ZB0001FFa0")
        assertTrue(event is Event.Data)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xA0.toByte()), (event as Event.Data).bytes)
        assertTrue(FytAutoSerialProtocol.parse("ZB012") is Event.Other)
        assertNull(FytAutoSerialProtocol.decodeHex("0G"))
    }

    @Test
    fun dataToThePhone_goesAsUppercaseHex_fiftyBytesALine() {
        val bytes = ByteArray(120) { it.toByte() }
        val lines = FytAutoSerialProtocol.dataLines(bytes, 0, bytes.size)
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.startsWith("AT#ZA") })
        assertEquals(100, lines[0].length - 5)
        assertEquals(100, lines[1].length - 5)
        assertEquals(40, lines[2].length - 5)
        assertEquals("AT#ZA000102", lines[0].take(11))
        assertEquals("6E6F7071727374757677", lines[2].takeLast(20))
    }

    @Test
    fun dataToThePhone_honoursTheSlice() {
        val bytes = byteArrayOf(0x10, 0xAB.toByte(), 0x0C, 0x20)
        assertEquals(listOf("AT#ZAAB0C"), FytAutoSerialProtocol.dataLines(bytes, 1, 2))
        assertEquals(emptyList<String>(), FytAutoSerialProtocol.dataLines(bytes, 0, 0))
    }

    @Test
    fun anythingElse_isPassedOver() {
        assertEquals(Event.Other("IB"), FytAutoSerialProtocol.parse("IB"))
        assertEquals(Event.Other("A"), FytAutoSerialProtocol.parse("A"))
    }
}
