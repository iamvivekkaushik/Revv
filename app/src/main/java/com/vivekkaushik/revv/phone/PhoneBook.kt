package com.vivekkaushik.revv.phone

import kotlin.math.pow

/** Picks what the phone screens list from the phone's call history and favourites. */
object PhoneBook {

    /** Newest first, one line per number, showing its latest call. */
    fun recents(calls: List<Call>, limit: Int = RECENTS): List<Call> =
        calls.sortedByDescending { it.timeMillis }.distinctBy { numberKey(it.number) }.take(limit)

    /**
     * The phone's [starred] favourites, then the numbers answered or dialled most, so a phone with
     * no favourites still fills the grid. A call counts half as much for every month since, which
     * puts the people called lately first without dropping those from a quiet spell before.
     */
    fun favourites(starred: List<Favourite>, calls: List<Call>, nowMillis: Long, limit: Int = FAVOURITES): List<Favourite> {
        val taken = starred.map { numberKey(it.number) }.toSet()
        val frequent = calls
            .filter { it.type == CallType.Incoming || it.type == CallType.Outgoing }
            .groupBy { numberKey(it.number) }
            .filter { (key, group) -> key.isNotEmpty() && key !in taken && group.size >= MIN_FREQUENT_CALLS }
            .values
            .sortedByDescending { group -> group.sumOf { weight(it.timeMillis, nowMillis) } }
            .map { group -> group.maxBy { it.timeMillis }.let { Favourite(it.label, it.number, starred = false) } }
        return (starred.distinctBy { numberKey(it.number) } + frequent).take(limit)
    }

    private fun weight(timeMillis: Long, nowMillis: Long): Double =
        0.5.pow((nowMillis - timeMillis).coerceAtLeast(0L) / HALF_LIFE_MILLIS)

    /**
     * One key for a number however it was written: "+91 98100 12345", "098100 12345" and
     * "9810012345" end in the same ten digits. Empty for withheld numbers.
     */
    fun numberKey(number: String): String = number.filter(Char::isDigit).takeLast(KEY_DIGITS)

    private const val RECENTS = 30
    private const val FAVOURITES = 6
    private const val MIN_FREQUENT_CALLS = 2
    private const val KEY_DIGITS = 10
    private const val HALF_LIFE_MILLIS = 30.0 * 24 * 60 * 60 * 1000
}
