package com.vivekkaushik.revv.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsTest {

    @Test
    fun contactsAreSortedAToZ_withNumbersAndSymbolsLast() {
        val sorted = PhoneBook.contacts(
            listOf(
                Contact("zoya", "111"),
                Contact("1st Garage", "222"),
                Contact("Anil", "333"),
                Contact("Bina", "444"),
            ),
        )
        assertEquals(listOf("Anil", "Bina", "zoya", "1st Garage"), sorted.map { it.name })
    }

    @Test
    fun theSameContactTwice_isListedOnce() {
        val sorted = PhoneBook.contacts(listOf(Contact("Anil", "+91 98100 12345"), Contact("anil", "9810012345")))
        assertEquals(1, sorted.size)
    }

    @Test
    fun initialIsTheFirstLetter_orHashForAnythingElse() {
        assertEquals('P', PhoneBook.initial("priya sharma"))
        assertEquals('#', PhoneBook.initial("7-Eleven"))
        assertEquals('#', PhoneBook.initial(""))
    }
}
