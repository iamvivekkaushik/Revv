package com.vivekkaushik.revv.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiEndpointTest {

    @Test
    fun parse_readsAnAddressWithOrWithoutAPort() {
        assertEquals(WifiEndpoint("192.168.0.10", 35000), WifiEndpoint.parse("192.168.0.10"))
        assertEquals(WifiEndpoint("10.0.0.5", 23), WifiEndpoint.parse("10.0.0.5:23"))
        // Leading zeros and stray spaces are tidied up.
        assertEquals(WifiEndpoint("192.168.0.10", 35000), WifiEndpoint.parse(" 192.168.000.010:35000 "))
    }

    @Test
    fun parse_rejectsAnythingThatIsNotAnIpv4AddressAndPort() {
        listOf(
            "", "auto", "192.168.0", "192.168.0.10.5", "256.1.1.1", "1.2.3.-4", "1..3.4",
            "1.2.3.4:", "1.2.3.4:0", "1.2.3.4:70000", "1.2.3.4:5:6", "obd.local:35000",
        ).forEach { assertNull(it, WifiEndpoint.parse(it)) }
    }

    @Test
    fun type_buildsUpAnAddressKeyByKey() {
        val typed = "192.168.0.10:35000".map { it.toString() }.fold("") { text, key -> WifiEndpoint.type(text, key) }
        assertEquals("192.168.0.10:35000", typed)
    }

    @Test
    fun type_ignoresKeysThatCannotLeadToAValidAddress() {
        assertEquals("", WifiEndpoint.type("", "."))
        assertEquals("192.", WifiEndpoint.type("192.", "."))
        assertEquals("192.168", WifiEndpoint.type("192.168", ":"))
        assertEquals("1.2.3.4", WifiEndpoint.type("1.2.3.4", "."))
        assertEquals("1.2.3.4:5", WifiEndpoint.type("1.2.3.4:5", ":"))
        assertEquals("255.255.255.255:65535", WifiEndpoint.type("255.255.255.255:65535", "9"))
    }

    @Test
    fun wifiAdapter_carriesItsTypedAddress() {
        val adapter = ObdAdapter.wifiAt(WifiEndpoint("192.168.4.1", 35000))
        assertEquals(ObdAdapter.Kind.WiFi, adapter.kind)
        assertEquals(WifiEndpoint("192.168.4.1", 35000), adapter.wifiEndpoint)
        assertNull(ObdAdapter.WiFi.wifiEndpoint)
        assertNull(ObdAdapter.bluetooth("00:1D:A5:68:98:8B", "OBDII").wifiEndpoint)
    }
}
