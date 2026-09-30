package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.phone.Call
import com.vivekkaushik.revv.phone.CallType
import com.vivekkaushik.revv.phone.PhoneLink
import com.vivekkaushik.revv.phone.PhoneState
import com.vivekkaushik.revv.phone.PhoneSync
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFormatTest {

    // Wednesday 30 September 2026, 16:50.
    private val now = LocalDateTime.of(2026, 9, 30, 16, 50)
    private val clock24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    private fun millis(at: LocalDateTime) = at.toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun detail(type: CallType, at: LocalDateTime, timeFormat: DateTimeFormatter = clock24): String =
        PhoneFormat.detail(Call("9810012345", "Maa", type, millis(at)), now, timeFormat, ZoneOffset.UTC)

    private val s23 = PhoneLink("Galaxy S23", "00:11:22:33:44:55", battery = null, connected = false)

    private fun status(sync: PhoneSync, link: PhoneLink? = s23, syncedAt: Long? = null) =
        PhoneFormat.status(PhoneState(sync = sync, link = link, syncedAt = syncedAt), clock24, ZoneOffset.UTC)

    @Test
    fun detail_countsMinutesWithinTheHour() {
        assertEquals("MISSED · 12 MIN AGO", detail(CallType.Missed, now.minusMinutes(12)))
        assertEquals("INCOMING · JUST NOW", detail(CallType.Incoming, now.minusSeconds(20)))
        assertEquals("OUTGOING · 59 MIN AGO", detail(CallType.Outgoing, now.minusMinutes(59)))
    }

    @Test
    fun detail_givesTheTimeToday_thenTheDay_thenTheDate() {
        val morning = now.withHour(9).withMinute(40)
        assertEquals("OUTGOING · 09:40", detail(CallType.Outgoing, morning))
        assertEquals("OUTGOING · 9:40", detail(CallType.Outgoing, morning, DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)))
        assertEquals("INCOMING · YESTERDAY", detail(CallType.Incoming, now.minusDays(1).withHour(23)))
        assertEquals("MISSED · FRI", detail(CallType.Missed, now.minusDays(5)))
        assertEquals("OUTGOING · 23 SEP", detail(CallType.Outgoing, now.minusDays(7)))
    }

    @Test
    fun detail_leavesOutATimeThePhoneDidntGive() {
        assertEquals("MISSED", PhoneFormat.detail(Call("111", "Maa", CallType.Missed, 0L), now, clock24, ZoneOffset.UTC))
    }

    @Test
    fun status_followsTheReading() {
        assertEquals("READING CALLS…", status(PhoneSync.Reading))
        assertEquals("ALLOW ACCESS ON THE PHONE", status(PhoneSync.AwaitingApproval))
        assertEquals("COULDN'T READ CALLS · TAP TO RETRY", status(PhoneSync.Failed))
        assertEquals("UPDATED 16:45", status(PhoneSync.Synced, syncedAt = millis(now.minusMinutes(5))))
        assertEquals("CONNECTED · 74%", status(PhoneSync.Synced, s23.copy(battery = 74, connected = true), millis(now)))
        assertEquals("TAP TO PAIR ONE IN BLUETOOTH SETTINGS", status(PhoneSync.NoPhone, link = null))
    }

    @Test
    fun badge_namesThePhone_orSaysThereIsNone() {
        assertEquals("PIXEL 8 · 74%", PhoneFormat.badge(PhoneLink("Pixel 8", "00:11:22:33:44:55", 74, connected = true)))
        assertEquals("GALAXY S23", PhoneFormat.badge(s23))
        assertEquals("NO PHONE", PhoneFormat.badge(null))
    }
}
