package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.phone.Call
import com.vivekkaushik.revv.phone.CallType
import com.vivekkaushik.revv.phone.PhoneLink
import com.vivekkaushik.revv.phone.PhoneState
import com.vivekkaushik.revv.phone.PhoneSync
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Calls and phones the way the HMI shows them: short and upper case. */
object PhoneFormat {

    /** Under a call: "MISSED · 12 MIN AGO", "OUTGOING · 09:40", "INCOMING · YESTERDAY", "OUTGOING · WED". */
    fun detail(call: Call, now: LocalDateTime, timeFormat: DateTimeFormatter, zone: ZoneId = ZoneId.systemDefault()): String =
        if (call.timeMillis <= 0) type(call.type) else "${type(call.type)} · ${time(call.timeMillis, now, timeFormat, zone)}"

    /**
     * Under the phone's name: "CONNECTED · 74%" while it's connected, else when its calls were read,
     * or how reading them is going.
     */
    fun status(phone: PhoneState, timeFormat: DateTimeFormatter, zone: ZoneId = ZoneId.systemDefault()): String {
        val link = phone.link ?: return "TAP TO PAIR ONE IN BLUETOOTH SETTINGS"
        return when (phone.sync) {
            PhoneSync.Reading -> "READING CALLS…"
            PhoneSync.AwaitingApproval -> "ALLOW ACCESS ON THE PHONE"
            PhoneSync.Failed -> "COULDN'T READ CALLS · TAP TO RETRY"
            else -> {
                val read = phone.syncedAt?.let { "UPDATED " + Instant.ofEpochMilli(it).atZone(zone).format(timeFormat) }
                listOfNotNull(if (link.connected) "CONNECTED" else read ?: "NOT CONNECTED", link.battery?.let { "$it%" })
                    .joinToString(" · ")
            }
        }
    }

    /** The home card's corner: "PIXEL 8 · 74%". */
    fun badge(link: PhoneLink?): String =
        if (link == null) "NO PHONE" else listOfNotNull(link.name.uppercase(), link.battery?.let { "$it%" }).joinToString(" · ")

    private fun type(type: CallType) = when (type) {
        CallType.Incoming -> "INCOMING"
        CallType.Outgoing -> "OUTGOING"
        CallType.Missed -> "MISSED"
    }

    /** Minutes within the hour, then the time today, the day within the week, and the date after that. */
    private fun time(millis: Long, now: LocalDateTime, timeFormat: DateTimeFormatter, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
        val minutes = Duration.between(time, now).toMinutes()
        val days = ChronoUnit.DAYS.between(time.toLocalDate(), now.toLocalDate())
        val locale = timeFormat.locale
        return when {
            minutes < 1 -> "JUST NOW"
            minutes < 60 -> "$minutes MIN AGO"
            days < 1 -> time.format(timeFormat)
            days == 1L -> "YESTERDAY"
            days < 7 -> time.format(DateTimeFormatter.ofPattern("EEE", locale)).uppercase(locale)
            else -> time.format(DateTimeFormatter.ofPattern("d MMM", locale)).uppercase(locale)
        }
    }
}
