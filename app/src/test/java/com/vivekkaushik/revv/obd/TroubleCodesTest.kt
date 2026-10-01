package com.vivekkaushik.revv.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class TroubleCodesTest {

    @Test
    fun knownCodeHasItsMeaning() {
        assertEquals("Cylinder 1 misfire", TroubleCodes.describe("P0301"))
        assertEquals("Fuel mixture too lean", TroubleCodes.describe("p0171"))
    }

    @Test
    fun unknownCodeNamesItsSystem() {
        assertEquals("Generic powertrain code", TroubleCodes.describe("P0999"))
        assertEquals("Manufacturer-specific powertrain code", TroubleCodes.describe("P1234"))
        assertEquals("Generic network code", TroubleCodes.describe("U0001"))
    }

    @Test
    fun simulatedReplyDecodesToTheTwoFaults() {
        assertEquals(listOf("P0171", "P0301"), ObdResponse.troubleCodes("430201710301", can = true))
    }

    @Test
    fun pendingAndClearRepliesAreRecognised() {
        assertEquals(listOf("P0420"), ObdResponse.troubleCodes("47010420", can = true, reply = ObdResponse.PENDING_CODES_REPLY))
        assertEquals(emptyList<String>(), ObdResponse.troubleCodes("4700", can = true, reply = ObdResponse.PENDING_CODES_REPLY))
        assertEquals(true, ObdResponse.clearAcknowledged("44"))
        assertEquals(false, ObdResponse.clearAcknowledged("NO DATA"))
    }
}
