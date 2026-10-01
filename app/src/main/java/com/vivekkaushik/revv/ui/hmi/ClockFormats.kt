package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.settings.HmiSettings

/** The clock and date layouts offered in Settings › Display. */
object ClockFormats {
    val timeLabels = listOf("System", "12-hour", "24-hour")

    /** Pattern for each date layout, in the order of [dateLabels]. */
    private val datePatterns = listOf("EEE dd MMM", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd")
    val dateLabels = listOf("THU 01 OCT", "01/10/2026", "10/01/2026", "2026-10-01")

    /** Whether the clock shows 24 hours, given what the system itself is set to. */
    fun is24Hour(settings: HmiSettings, system: Boolean): Boolean = when (settings.timeFormat) {
        1 -> false
        2 -> true
        else -> system
    }

    fun timePattern(is24Hour: Boolean): String = if (is24Hour) "HH:mm" else "h:mm"

    fun datePattern(settings: HmiSettings): String = datePatterns[settings.dateFormat.coerceIn(datePatterns.indices)]
}
