package com.vivekkaushik.revv.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FytPhoneTest {

    private fun state(state: Int) = FytUpdate(FytUpdate.PHONE_STATE, intArrayOf(state), null)
    private fun text(code: Int, vararg strings: String?) = FytUpdate(code, null, arrayOf(*strings))

    @Test
    fun aPhoneIsConnectedOnceFytSaysSo_andWhileInACall() {
        val idle = FytLink().after(state(FytLink.STATE_CONNECTED))
        assertTrue(idle.connected)
        assertFalse(idle.inCall)
        for (calling in listOf(FytLink.STATE_DIALLING, FytLink.STATE_RINGING, FytLink.STATE_TALKING, FytLink.STATE_MULTIPARTY)) {
            val link = idle.after(state(calling))
            assertTrue(link.connected)
            assertTrue(link.inCall)
        }
    }

    @Test
    fun linkingAndPairingAreNotAPhone() {
        for (none in listOf(FytLink.STATE_DISCONNECTED, FytLink.STATE_LINKING, FytLink.STATE_PAIRING)) {
            assertFalse(FytLink().after(state(none)).connected)
        }
    }

    @Test
    fun callStages_followThePhoneState() {
        assertEquals(CallStage.Dialling, FytLink(state = FytLink.STATE_DIALLING).stage)
        assertEquals(CallStage.Ringing, FytLink(state = FytLink.STATE_RINGING).stage)
        assertEquals(CallStage.Connected, FytLink(state = FytLink.STATE_TALKING).stage)
        assertEquals(CallStage.Connected, FytLink(state = FytLink.STATE_MULTIPARTY).stage)
        assertNull(FytLink(state = FytLink.STATE_CONNECTED).stage)
    }

    @Test
    fun nameAndAddressCome_asTheFirstString() {
        val link = FytLink()
            .after(text(FytUpdate.PHONE_NAME, " Pixel 8 "))
            .after(text(FytUpdate.PHONE_ADDRESS, "AA:BB:CC:DD:EE:FF"))
        assertEquals("Pixel 8", link.name)
        assertEquals("AA:BB:CC:DD:EE:FF", link.address)
    }

    @Test
    fun theCallsNumberIsTheSecondString_whenACallIsOnHold() {
        assertEquals("+911234", FytLink().after(text(FytUpdate.PHONE_NUMBER, "+911234")).number)
        assertEquals("+915678", FytLink().after(text(FytUpdate.PHONE_NUMBER, "+911234", "+915678")).number)
        assertEquals("", FytLink(number = "1").after(text(FytUpdate.PHONE_NUMBER, null)).number)
    }

    @Test
    fun readingCovers_bothThePhoneBookAndTheCallHistory() {
        assertTrue(FytLink().after(FytUpdate(FytUpdate.PBAP_STATE, intArrayOf(2), null)).reading)
        assertTrue(FytLink().after(FytUpdate(FytUpdate.PBAP_STATE, intArrayOf(4), null)).reading)
        assertFalse(FytLink().after(FytUpdate(FytUpdate.PBAP_STATE, intArrayOf(1), null)).reading)
    }

    @Test
    fun updatesWithoutValues_changeNothing() {
        val link = FytLink(state = FytLink.STATE_TALKING, name = "Pixel")
        assertEquals(link, link.after(FytUpdate(FytUpdate.PHONE_STATE, intArrayOf(), null)))
        assertEquals(link, link.after(FytUpdate(FytUpdate.PBAP_STATE, null, null)))
        assertEquals(link, link.after(FytUpdate(1234, intArrayOf(1), arrayOf("x"))))
    }

    @Test
    fun losingTheService_forgetsThePhone() {
        val link = FytLink(state = FytLink.STATE_TALKING, name = "Pixel", address = "AA", number = "1")
        assertEquals(FytLink(), link.after(FytUpdate.LOST))
    }

    @Test
    fun callLogTypes_mapToRevvsKinds() {
        assertEquals(CallType.Incoming, FytPhoneBook.callType(1))
        assertEquals(CallType.Outgoing, FytPhoneBook.callType(2))
        assertEquals(CallType.Missed, FytPhoneBook.callType(3))
        assertEquals(CallType.Missed, FytPhoneBook.callType(5))
        assertEquals(CallType.Incoming, FytPhoneBook.callType(7))
        assertNull(FytPhoneBook.callType(4))
        assertNull(FytPhoneBook.callType(6))
    }
}
