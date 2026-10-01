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

    /** Contacts A to Z, those not starting with a letter last, one line per name and number. */
    fun contacts(all: List<Contact>): List<Contact> =
        all.distinctBy { it.name.lowercase() to numberKey(it.number) }
            .sortedWith(compareBy<Contact> { initial(it.name) == OTHER_INITIAL }.thenBy { it.name.lowercase() })

    /** The letter [name] is filed under in the contact list: its first letter, or # for numbers and symbols. */
    fun initial(name: String): Char {
        val first = name.trim().firstOrNull()?.uppercaseChar() ?: return OTHER_INITIAL
        return if (first in 'A'..'Z') first else OTHER_INITIAL
    }

    /** The keypad digit for each letter: 2 is ABC, 3 is DEF, and so on. */
    fun t9(text: String): String = buildString {
        for (c in text.lowercase()) {
            when (c) {
                in 'a'..'c' -> append('2')
                in 'd'..'f' -> append('3')
                in 'g'..'i' -> append('4')
                in 'j'..'l' -> append('5')
                in 'm'..'o' -> append('6')
                in 'p'..'s' -> append('7')
                in 't'..'v' -> append('8')
                in 'w'..'z' -> append('9')
                in '0'..'9' -> append(c)
            }
        }
    }

    /**
     * Who the keys typed so far could be, T9 style: contacts whose name, or one of its words, starts
     * with letters on those keys, then anyone whose number contains the digits. Contacts first, then
     * names from recent calls; [typed] may hold any dialler characters, and only digits count.
     */
    fun suggestions(typed: String, contacts: List<Contact>, recents: List<Call>, limit: Int = SUGGESTIONS): List<Contact> {
        val digits = typed.filter(Char::isDigit)
        if (digits.isEmpty()) return emptyList()
        val everyone = (contacts + recents.filter { it.number.isNotBlank() && it.label != it.number }.map { Contact(it.label, it.number) })
            .distinctBy { numberKey(it.number) }
        fun rank(person: Contact): Int {
            val words = person.name.split(' ', '-', '.').filter(String::isNotBlank).map(::t9)
            return when {
                t9(person.name).startsWith(digits) -> 0
                words.any { it.startsWith(digits) } -> 1
                person.number.filter(Char::isDigit).contains(digits) -> 2
                else -> 3
            }
        }
        return everyone.map { it to rank(it) }
            .filter { it.second < 3 }
            .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
            .map { it.first }
            .take(limit)
    }

    private const val SUGGESTIONS = 30

    const val OTHER_INITIAL = '#'

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
