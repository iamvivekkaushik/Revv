package com.vivekkaushik.revv.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleApiKeyTest {
    // Made up, in Google's shape; put together here so secret scanners don't take it for a real key.
    private val key = "AIza" + "SyD-example_KEY0123456789abcdefghi"

    @Test
    fun aBareKeyIsTheKey() {
        assertEquals(key, GoogleApiKey.find(key))
        assertEquals(key, GoogleApiKey.find("  $key\n"))
    }

    @Test
    fun theKeyIsPickedOutOfWhatSurroundsIt() {
        assertEquals(key, GoogleApiKey.find("MAPS_API_KEY=$key"))
        assertEquals(key, GoogleApiKey.find("""{"name": "Revv", "apiKey": "$key"}"""))
    }

    @Test
    fun textWithoutAKeyHasNone() {
        assertNull(GoogleApiKey.find("Remember to buy milk"))
        assertNull(GoogleApiKey.find(""))
    }
}
