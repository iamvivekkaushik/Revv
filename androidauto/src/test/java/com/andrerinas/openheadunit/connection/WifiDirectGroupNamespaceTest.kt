package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names this stack gives its Wi-Fi Direct groups, and how it knows one of its own from an
 * earlier run (a record of the name, or the name's shape) from another app's.
 */
class WifiDirectGroupNamespaceTest {
    @Test fun suffixKeepsLettersAndDigitsOfTheHeadUnitsName() {
        assertEquals("OnePlus7Pro", WifiDirectManager.nameSuffix("OnePlus 7 Pro"))
        assertEquals("BYDDiLink51", WifiDirectManager.nameSuffix("BYD DiLink-5.1"))
        assertEquals("HeadUnit", WifiDirectManager.nameSuffix(null))
        assertEquals("HeadUnit", WifiDirectManager.nameSuffix(" - "))
        assertEquals(20, WifiDirectManager.nameSuffix("A".repeat(40)).length)
    }

    @Test fun ownNamesAreDirectTwoCharactersAndTheHeadUnitsName() {
        assertTrue(WifiDirectManager.inNamespace("DIRECT-UU-OnePlus7Pro", "OnePlus7Pro"))
        assertTrue(WifiDirectManager.inNamespace("DIRECT-8L-OnePlus7Pro", "OnePlus7Pro"))
        assertTrue(WifiDirectManager.inNamespace("DIRECT-A6-HeadUnit", "HeadUnit"))
    }

    @Test fun otherAppsNamesAreNotTakenForOurs() {
        // Revv's CarPlay stack names its groups by a hash in its own namespace.
        assertFalse(WifiDirectManager.inNamespace("DIRECT-dp4704de9283c0-fM3W", "OnePlus7Pro"))
        // The system's own naming carries the raw device name, and another unit's name is not ours.
        assertFalse(WifiDirectManager.inNamespace("DIRECT-Os-Android_XG6K", "OnePlus7Pro"))
        assertFalse(WifiDirectManager.inNamespace("DIRECT-UU-OnePlus8Pro", "OnePlus7Pro"))
        assertFalse(WifiDirectManager.inNamespace("DIRECT-U-OnePlus7Pro", "OnePlus7Pro"))
        assertFalse(WifiDirectManager.inNamespace("DIRECT-U--OnePlus7Pro", "OnePlus7Pro"))
        assertFalse(WifiDirectManager.inNamespace("OnePlus7Pro", "OnePlus7Pro"))
        assertFalse(WifiDirectManager.inNamespace("", "OnePlus7Pro"))
    }
}
