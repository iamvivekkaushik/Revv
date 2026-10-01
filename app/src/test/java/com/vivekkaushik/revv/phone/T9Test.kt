package com.vivekkaushik.revv.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class T9Test {

    private val contacts = listOf(
        Contact("Priya Sharma", "+91 98100 12345"),
        Contact("Anil Kumar", "+91 90000 11111"),
        Contact("Bina", "+91 98765 43210"),
    )

    @Test
    fun lettersMapToKeypadDigits() {
        assertEquals("77492", PhoneBook.t9("Priya"))
        assertEquals("2645", PhoneBook.t9("Anil"))
    }

    @Test
    fun nameStartsMatch() {
        assertEquals(listOf("Priya Sharma"), PhoneBook.suggestions("774", contacts, emptyList()).map { it.name })
    }

    @Test
    fun anyWordInANameMatches() {
        assertEquals(listOf("Priya Sharma"), PhoneBook.suggestions("742", contacts, emptyList()).map { it.name })
        assertEquals(listOf("Anil Kumar"), PhoneBook.suggestions("58", contacts, emptyList()).map { it.name })
    }

    @Test
    fun numbersMatchToo_afterNames() {
        // 2 starts Anil's and Bina's names; Priya only turns up because her number has a 2 in it.
        assertEquals(listOf("Anil Kumar", "Bina", "Priya Sharma"), PhoneBook.suggestions("2", contacts, emptyList()).map { it.name })
        assertEquals(listOf("Bina"), PhoneBook.suggestions("4321", contacts, emptyList()).map { it.name })
    }

    @Test
    fun nothingTyped_suggestsNothing() {
        assertEquals(emptyList<Contact>(), PhoneBook.suggestions("", contacts, emptyList()))
        assertEquals(emptyList<Contact>(), PhoneBook.suggestions("+*#", contacts, emptyList()))
    }

    @Test
    fun recentCallsWithNames_areIncluded_withoutRepeatingContacts() {
        val recents = listOf(
            Call("+91 98100 12345", "Priya Sharma", CallType.Incoming, 1),
            Call("+91 70000 55555", "Pizza Place", CallType.Outgoing, 2),
            Call("+91 80000 66666", "+91 80000 66666", CallType.Missed, 3),
        )
        assertEquals(listOf("Pizza Place", "Priya Sharma"), PhoneBook.suggestions("74", contacts, recents).map { it.name })
    }
}
