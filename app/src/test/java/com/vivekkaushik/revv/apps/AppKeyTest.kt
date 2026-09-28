package com.vivekkaushik.revv.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppKeyTest {

    @Test
    fun serializeThenParse_roundTrips() {
        val key = AppKey("com.example.maps/com.example.maps.MainActivity", 10)
        assertEquals(key, AppKey.parse(key.serialize()))
    }

    @Test
    fun parse_rejectsMalformedValues() {
        listOf("", "com.example/.Main", "com.example/.Main#", "#0", "com.example/.Main#work").forEach {
            assertNull(it, AppKey.parse(it))
        }
    }

    @Test
    fun packageName_isTheComponentsPackage() {
        assertEquals("com.example.maps", AppKey("com.example.maps/.Main", 0).packageName)
    }
}
