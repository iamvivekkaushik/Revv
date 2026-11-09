package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceUpTest {

    @Test
    fun anAccessPointWithNoDeviceJoinedIsUp() {
        // UP | BROADCAST | MULTICAST, as sysfs shows FYT's wlan0 hotspot before anything joins.
        assertTrue(InterfaceUp.flagsSayUp("0x1003\n"))
    }

    @Test
    fun aSwitchedOffInterfaceIsNotUp() {
        assertFalse(InterfaceUp.flagsSayUp("0x1002\n"))
    }

    @Test
    fun unreadableFlagsAreNotUp() {
        assertFalse(InterfaceUp.flagsSayUp(""))
        assertFalse(InterfaceUp.flagsSayUp("up"))
    }

    @Test
    fun flagsWithoutAPrefixStillRead() {
        assertTrue(InterfaceUp.flagsSayUp("1003"))
    }
}
