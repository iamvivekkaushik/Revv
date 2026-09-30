package com.vivekkaushik.revv.phone

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObexTest {

    /** A phone's phonebook server that answers each request with the next of [replies]. */
    private class Phone(vararg replies: ByteArray) {
        val input = ByteArrayInputStream(replies.fold(ByteArray(0)) { all, reply -> all + reply })
        val sent = ByteArrayOutputStream()
        val client = ObexClient(input, sent)
        fun sentHex() = sent.toByteArray().hex()
    }

    @Test
    fun connect_namesTheTargetAndKeepsTheConnectionId() {
        val phone = Phone(connected, packet(SUCCESS))
        phone.client.connect(TARGET, TIMEOUT)
        phone.client.disconnect(TIMEOUT)

        assertEquals(
            // CONNECT: OBEX 1.0, no flags, 64 KB packets, then the Target header.
            "80001A" + "1000FFFF" + "460013" + TARGET.hex() +
                // DISCONNECT carries the connection ID the server gave.
                "810008" + "CB00000007",
            phone.sentHex(),
        )
    }

    @Test
    fun get_joinsTheBodyAcrossPackets_askingForMoreWithEmptyRequests() {
        val phone = Phone(
            connected,
            packet(CONTINUE, header(BODY, "BEGIN:".toByteArray())),
            packet(CONTINUE),
            packet(SUCCESS, header(END_OF_BODY, "VCARD".toByteArray())),
        )
        phone.client.connect(TARGET, TIMEOUT)
        phone.sent.reset()
        val body = phone.client.get("a.vcf", "x-t", byteArrayOf(0x07, 0x01, 0x01), TIMEOUT)

        assertEquals("BEGIN:VCARD", String(body))
        assertEquals(
            "830024" + "CB00000007" +
                // Name, in UTF-16 with a terminating null; Type, in ASCII with one; the application parameters.
                "01000F" + "0061002E007600630066" + "0000" + "420007" + "782D7400" + "4C0006" + "070101" +
                "830003" + "830003",
            phone.sentHex(),
        )
    }

    @Test
    fun anErrorResponse_isRaised_withWhetherItWasARefusal() {
        val notFound = assertThrows(ObexException::class.java) { Phone(packet(0xC4)).client.get("a", "t", ByteArray(0), TIMEOUT) }
        assertEquals(0xC4, notFound.code)
        assertFalse(notFound.refused)
        val forbidden = assertThrows(ObexException::class.java) { Phone(packet(0xC3)).client.connect(TARGET, TIMEOUT) }
        assertTrue(forbidden.refused)
    }

    @Test
    fun aSilentPhone_isWaitedFor_thenGivenUpOn() {
        val silent = object : InputStream() {
            override fun available() = 0
            override fun read() = -1
        }
        var waited = false
        val client = ObexClient(silent, ByteArrayOutputStream(), waitingAfterMillis = 20)
        assertThrows(SocketTimeoutException::class.java) { client.connect(TARGET, 150) { waited = true } }
        assertTrue(waited)
    }

    @Test
    fun pbapSession_asksForVersion30CallHistory_andManagesWithoutFavourites() {
        val history = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Maa
            TEL:+919810033333
            X-IRMC-CALL-DATETIME;TYPE=RECEIVED:20260930T101500Z
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Not a call
            TEL:111
            END:VCARD
        """.trimIndent().replace("\n", "\r\n")
        val phone = Phone(
            connected,
            packet(SUCCESS, header(END_OF_BODY, history.toByteArray())),
            // Phones before PBAP 1.2 have no favourites.
            packet(0xC4),
        )
        val session = PbapSession(phone.client, ZoneOffset.UTC)
        session.connect {}
        phone.sent.reset()
        val calls = session.callHistory()
        val favourites = session.favourites()

        assertEquals(listOf("Maa"), calls.map { it.name })
        assertEquals(CallType.Incoming, calls.single().call)
        assertNull(favourites)
        val sent = phone.sentHex()
        // Call history in vCard 3.0, up to 500 calls, with names, numbers and when each call was.
        assertTrue(sent.contains(utf16("telecom/cch.vcf")))
        assertTrue(sent.contains("4C0014" + "070101" + "040201F4" + "06080000000010000087"))
        assertTrue(sent.contains(utf16("telecom/fav.vcf")))
    }

    private companion object {
        const val CONTINUE = 0x90
        const val SUCCESS = 0xA0
        const val BODY = 0x48
        const val END_OF_BODY = 0x49
        const val TIMEOUT = 1_000L

        val TARGET = ByteArray(16) { it.toByte() }

        fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

        fun utf16(text: String) = text.toByteArray(Charsets.UTF_16BE).hex()

        fun header(id: Int, value: ByteArray): ByteArray {
            val length = 3 + value.size
            return byteArrayOf(id.toByte(), (length shr 8).toByte(), length.toByte()) + value
        }

        fun packet(code: Int, vararg parts: ByteArray): ByteArray {
            val payload = parts.fold(ByteArray(0)) { all, part -> all + part }
            val length = 3 + payload.size
            return byteArrayOf(code.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        }

        /** A connect response: OBEX 1.0, no flags, 64 KB packets, connection ID 7. */
        val connected = packet(SUCCESS, byteArrayOf(0x10, 0x00, 0xFF.toByte(), 0xFF.toByte()), byteArrayOf(0xCB.toByte(), 0, 0, 0, 7))
    }
}
