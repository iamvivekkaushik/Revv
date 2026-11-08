package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.FytRfSerialProtocol.Event
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FytRfSerialProtocolTest {

    @Test
    fun connectingNamesTheIphoneAndIap2sService_likeCarLink() {
        assertEquals(
            "AT#SP001122AABBCC00000000DECAFADEDECADEAFDECACAFE",
            FytRfSerialProtocol.connect("00:11:22:aa:bb:cc"),
        )
        assertEquals(
            "AT#SP001122AABBCC00000000DECAFADEDECADEAFDECACAFE",
            FytRfSerialProtocol.connect("001122AABBCC"),
        )
        assertThrows(IllegalArgumentException::class.java) { FytRfSerialProtocol.connect("00:11:22") }
    }

    @Test
    fun theChannelComesUpAndGoesDown() {
        assertEquals(Event.ChannelUp, FytRfSerialProtocol.parse("SV"))
        assertEquals(Event.ChannelDown, FytRfSerialProtocol.parse("SS"))
    }

    @Test
    fun addresses_gainTheirColons() {
        assertEquals(Event.PhoneAddress("00:11:22:AA:BB:CC"), FytRfSerialProtocol.parse("JH001122aabbcc"))
        assertEquals(Event.LocalAddress("AA:BB:CC:00:11:22"), FytRfSerialProtocol.parse("DBAABBCC001122"))
        assertTrue(FytRfSerialProtocol.parse("JH0011") is Event.Other)
    }

    @Test
    fun iap2BytesFromTheIphone_areHex() {
        val event = FytRfSerialProtocol.parse("SIFF5A00")
        assertTrue(event is Event.Data)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x5A, 0x00), (event as Event.Data).bytes)
        assertTrue(FytRfSerialProtocol.parse("SIF") is Event.Other)
    }

    @Test
    fun iap2BytesToTheIphone_goAsUppercaseHex_aHundredBytesALine() {
        val data = ByteArray(250) { (it * 7).toByte() }
        val lines = FytRfSerialProtocol.dataLines(data)
        assertEquals(listOf(205, 205, 105), lines.map { it.length })
        assertTrue(lines.all { it.startsWith("AT#SG") && it.drop(5).all { c -> c in "0123456789ABCDEF" } })
        val decoded = lines.flatMap { FytRfSerialProtocol.decodeHex(it.drop(5))!!.toList() }.toByteArray()
        assertArrayEquals(data, decoded)
        assertEquals(emptyList<String>(), FytRfSerialProtocol.dataLines(ByteArray(0)))
    }

    @Test
    fun linesComeApart_onCrLf() {
        val splitter = FytRfSerialProtocol.LineSplitter()
        val first = "\r\nIB\r\n\r\nJH0011".toByteArray()
        val second = "22AABBCC\r\nSV\r\n".toByteArray()
        assertEquals(listOf("IB"), splitter.feed(first, first.size))
        assertEquals(listOf("JH001122AABBCC", "SV"), splitter.feed(second, second.size))
    }
}
