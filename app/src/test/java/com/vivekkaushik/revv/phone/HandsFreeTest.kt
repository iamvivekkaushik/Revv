package com.vivekkaushik.revv.phone

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HandsFreeTest {

    /** A phone's audio gateway that has all of [replies] ready, as if answering each command at once. */
    private class Gateway(vararg replies: String) {
        val sent = ByteArrayOutputStream()
        val link = HandsFreeLink(ByteArrayInputStream(replies.joinToString("").toByteArray()), sent, timeoutMillis = 200)
        fun commands() = sent.toString(Charsets.US_ASCII.name()).split("\r").filter(String::isNotEmpty)
    }

    private val linkReplies = arrayOf(
        "\r\n+BRSF: 871\r\n\r\nOK\r\n",
        "\r\n+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0-3))\r\n\r\nOK\r\n",
        "\r\n+CIND: 1,0,0\r\n\r\nOK\r\n",
        "\r\nOK\r\n",
    )

    @Test
    fun setsUpTheLinkLikeACarKit_thenDials() {
        val gateway = Gateway(*linkReplies, "\r\nOK\r\n")
        gateway.link.establish()
        gateway.link.dial("+919810033333")

        assertEquals(listOf("AT+BRSF=0", "AT+CIND=?", "AT+CIND?", "AT+CMER=3,0,0,1", "ATD+919810033333;"), gateway.commands())
    }

    @Test
    fun eventsBetweenReplies_areIgnored() {
        // The phone reports the call starting before it confirms the dial.
        val gateway = Gateway(*linkReplies, "\r\n+CIEV: 3,2\r\n\r\nOK\r\n")
        gateway.link.establish()
        gateway.link.dial("121")
    }

    @Test
    fun aPhoneThatWontDial_isReported() {
        val gateway = Gateway(*linkReplies, "\r\n+CME ERROR: 30\r\n")
        gateway.link.establish()
        assertThrows(IOException::class.java) { gateway.link.dial("121") }
    }

    @Test
    fun aSilentPhone_isGivenUpOn() {
        assertThrows(IOException::class.java) { Gateway().link.establish() }
    }

    @Test
    fun callEvents_updateTheCallState() {
        val gateway = Gateway(*linkReplies, "\r\n+CIEV: 3,2\r\n\r\nOK\r\n", "\r\n+CIEV: 3,3\r\n", "\r\n+CIEV: 3,0\r\n+CIEV: 2,1\r\n")
        gateway.link.establish()
        gateway.link.dial("121")
        assertEquals(CallIndicators(call = 0, setup = 2), gateway.link.call)
        gateway.link.listen()
        assertEquals(CallIndicators(call = 1, setup = 0), gateway.link.call)
    }

    @Test
    fun hangUp_sendsChup() {
        val gateway = Gateway(*linkReplies, "\r\nOK\r\n")
        gateway.link.establish()
        gateway.link.hangUp()
        assertEquals("AT+CHUP", gateway.commands().last())
    }
}
