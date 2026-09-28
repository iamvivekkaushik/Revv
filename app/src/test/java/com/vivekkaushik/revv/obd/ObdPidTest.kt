package com.vivekkaushik.revv.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdPidTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun decodesTheSaeFormulas() {
        assertEquals(50, ObdPid.speed(bytes(0x32)))
        assertEquals(1726, ObdPid.rpm(bytes(0x1A, 0xF8)))
        assertEquals(89, ObdPid.temperature(bytes(129)))
        assertEquals(-40, ObdPid.temperature(bytes(0)))
        assertEquals(100, ObdPid.percent(bytes(255)))
        assertEquals(50, ObdPid.percent(bytes(128)))
        assertEquals(98, ObdPid.pressure(bytes(98)))
        assertEquals(12.34f, ObdPid.maf(bytes(0x04, 0xD2))!!, 0.001f)
    }

    @Test
    fun readsTheCheckEngineLight() {
        assertEquals(true, ObdPid.milOn(bytes(0x82, 0x07, 0x65, 0x00)))
        assertEquals(2, ObdPid.troubleCodeCount(bytes(0x82, 0x07, 0x65, 0x00)))
        assertEquals(false, ObdPid.milOn(bytes(0x00)))
    }

    @Test
    fun shortRepliesDecodeToNull() {
        assertNull(ObdPid.rpm(bytes(0x1A)))
        assertNull(ObdPid.speed(bytes()))
    }
}
