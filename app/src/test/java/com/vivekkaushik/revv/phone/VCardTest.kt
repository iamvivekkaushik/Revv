package com.vivekkaushik.revv.phone

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VCardTest {

    private val india = ZoneId.of("Asia/Kolkata")

    private fun millis(at: LocalDateTime, zone: ZoneId) = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun callHistoryInVersion30_readsWhoWhenAndHow() {
        val text = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Maa
            N:;Maa;;;
            TEL;TYPE=CELL:+91 98100 33333
            X-IRMC-CALL-DATETIME;TYPE=MISSED:20260930T164800
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:
            N:
            TEL:
            X-IRMC-CALL-DATETIME;TYPE=DIALED:20260930T090000Z
            END:VCARD
        """.trimIndent().replace("\n", "\r\n")
        val entries = VCard.parse(text).map { PbapSession.entry(it, india) }

        assertEquals(PbapEntry("Maa", listOf("+91 98100 33333"), CallType.Missed, millis(LocalDateTime.of(2026, 9, 30, 16, 48), india)), entries[0])
        // A withheld number, logged in UTC.
        assertEquals(PbapEntry(null, emptyList(), CallType.Outgoing, millis(LocalDateTime.of(2026, 9, 30, 9, 0), ZoneOffset.UTC)), entries[1])
    }

    @Test
    fun callHistoryInVersion21_readsBareTypesAndQuotedPrintableNames() {
        val text = """
            BEGIN:VCARD
            VERSION:2.1
            N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E0=A4=AE=E0=A4=BE;=
            =E0=A4=AA=E0=A4=BE
            TEL;CELL:9810022222
            X-IRMC-CALL-DATETIME;RECEIVED:20260929T201500
            END:VCARD
        """.trimIndent()
        val entry = PbapSession.entry(VCard.parse(text).single(), india)

        // N is family name first: "मा;पा" reads "पा मा".
        assertEquals("पा मा", entry.name)
        assertEquals(listOf("9810022222"), entry.numbers)
        assertEquals(CallType.Incoming, entry.call)
    }

    @Test
    fun foldedLinesAndEscapesAndGroups() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Sharma\\, Priya\r\n  (work)\r\nitem1.TEL;TYPE=WORK:0124 400 0000\r\n" +
            "TEL;TYPE=CELL,PREF:+91 98100 11111\r\nEND:VCARD\r\n"
        val entry = PbapSession.entry(VCard.parse(text).single(), india)

        assertEquals("Sharma, Priya (work)", entry.name)
        // The preferred number comes first.
        assertEquals(listOf("+91 98100 11111", "0124 400 0000"), entry.numbers)
        assertNull(entry.call)
        assertNull(entry.timeMillis)
    }

    @Test
    fun nameFallsBackToTheStructuredName() {
        val text = "BEGIN:VCARD\nVERSION:3.0\nN:Sharma;Priya;;;\nTEL:111\nEND:VCARD\n"
        assertEquals("Priya Sharma", PbapSession.entry(VCard.parse(text).single(), india).name)
    }

    @Test
    fun linesOutsideACard_orWithoutAValue_areIgnored() {
        val text = "VERSION:3.0\nBEGIN:VCARD\nGARBAGE\nFN:Rohan\nEND:VCARD\nFN:Stray\n"
        val cards = VCard.parse(text)
        assertEquals(1, cards.size)
        assertEquals(listOf("FN"), cards.single().properties.map { it.name })
    }
}
