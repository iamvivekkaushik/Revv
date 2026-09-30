package com.vivekkaushik.revv.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneBookTest {

    private val now = 1_790_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun call(number: String, type: CallType = CallType.Outgoing, ago: Long = minute, label: String = number) =
        Call(number, label, type, now - ago)

    @Test
    fun numberKey_matchesOneNumberWrittenDifferently() {
        assertEquals("9810012345", PhoneBook.numberKey("+91 98100 12345"))
        assertEquals("9810012345", PhoneBook.numberKey("098100-12345"))
        assertEquals("9810012345", PhoneBook.numberKey("9810012345"))
        assertEquals("121", PhoneBook.numberKey("121"))
        assertEquals("", PhoneBook.numberKey(""))
    }

    @Test
    fun recents_listEachNumberOnceAtItsLatestCall() {
        val calls = listOf(
            call("+919810012345", CallType.Missed, ago = 5 * minute),
            call("9810012345", CallType.Outgoing, ago = 2 * minute),
            call("+911244000000", CallType.Incoming, ago = 10 * minute),
        )
        val recents = PhoneBook.recents(calls)
        assertEquals(listOf("9810012345", "+911244000000"), recents.map { it.number })
        assertEquals(CallType.Outgoing, recents.first().type)
    }

    @Test
    fun recents_stopAtTheLimit() {
        val calls = (1..40).map { call("98100000%02d".format(it), ago = it * minute) }
        assertEquals(30, PhoneBook.recents(calls).size)
        assertEquals(3, PhoneBook.recents(calls, limit = 3).size)
    }

    @Test
    fun recents_keepThePhonesOrderWhenItGivesNoTimes() {
        val calls = listOf(Call("111", "A", CallType.Missed, 0L), Call("222", "B", CallType.Incoming, 0L))
        assertEquals(listOf("111", "222"), PhoneBook.recents(calls).map { it.number })
    }

    @Test
    fun favourites_fillUpWithTheMostCalledNumbers() {
        val starred = listOf(Favourite("Priya", "+91 98100 11111", starred = true))
        val calls = listOf(
            // Priya is a favourite already, however often she's called.
            call("9810011111"), call("9810011111"), call("9810011111"),
            call("9810022222", label = "Rohan"), call("9810022222", CallType.Incoming, label = "Rohan"),
            call("9810033333", label = "Maa"), call("9810033333", label = "Maa"), call("9810033333", CallType.Incoming, label = "Maa"),
            // Missed calls and a single call don't make a number frequent.
            call("9810044444", CallType.Missed), call("9810044444", CallType.Missed),
            call("9810055555"),
            // Calls from months ago still do, after the recent ones.
            call("9810066666", ago = 100 * day, label = "Papa"), call("9810066666", ago = 101 * day, label = "Papa"),
        )
        val favourites = PhoneBook.favourites(starred, calls, now)
        assertEquals(listOf("Priya", "Maa", "Rohan", "Papa"), favourites.map { it.name })
        assertEquals(listOf(true, false, false, false), favourites.map { it.starred })
    }

    @Test
    fun favourites_countRecentCallsForMoreThanOldOnes() {
        val calls = List(5) { call("111", ago = 200 * day + it * minute) } + List(2) { call("222", ago = 3 * day + it * minute) }
        assertEquals(listOf("222", "111"), PhoneBook.favourites(emptyList(), calls, now).map { it.number })
    }

    @Test
    fun favourites_preferTheLatestOfEquallyCalledNumbers() {
        val calls = listOf(
            call("111", ago = 50 * minute), call("111", ago = 40 * minute),
            call("222", ago = 30 * minute), call("222", ago = 20 * minute),
        )
        assertEquals(listOf("222", "111"), PhoneBook.favourites(emptyList(), calls, now).map { it.number })
    }

    @Test
    fun favourites_listAFavouriteWithTwoEntriesOnce_andStopAtTheLimit() {
        val twice = listOf(Favourite("Maa", "+91 98100 33333", starred = true), Favourite("Maa", "09810033333", starred = true))
        assertEquals(1, PhoneBook.favourites(twice, emptyList(), now).size)
        val starred = (1..8).map { Favourite("Friend $it", "98100000%02d".format(it), starred = true) }
        assertEquals(6, PhoneBook.favourites(starred, emptyList(), now).size)
    }
}
