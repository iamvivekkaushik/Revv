package com.vivekkaushik.revv.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlacesParserTest {
    @Test
    fun readsNameAddressAndPosition() {
        val json = """
            {"places": [
              {"displayName": {"text": "Cyber Hub", "languageCode": "en"},
               "formattedAddress": "Cyber Hub, DLF Cyber City, Gurugram, Haryana 122002, India",
               "location": {"latitude": 28.4951, "longitude": 77.0890}},
              {"displayName": {"text": "No position"}, "formattedAddress": "Somewhere"}
            ]}
        """.trimIndent()

        val places = GooglePlacesParser.parse(json)

        assertEquals(1, places.size)
        assertEquals("Cyber Hub", places[0].name)
        // The name isn't repeated in the detail line.
        assertEquals("DLF Cyber City, Gurugram, Haryana 122002, India", places[0].detail)
        assertEquals(LatLon(28.4951, 77.0890), places[0].position)
    }

    @Test
    fun noMatchesIsAnEmptyAnswer() {
        assertTrue(GooglePlacesParser.parse("{}").isEmpty())
    }

    @Test
    fun anErrorGivesGooglesFirstSentence() {
        val json = """{"error": {"code": 403, "message": "Places API (New) has not been used in project 123 before or it is disabled. Enable it by visiting the console.", "status": "PERMISSION_DENIED"}}"""
        assertEquals("Google: Places API (New) has not been used in project 123 before or it is disabled", GooglePlacesParser.error(json))
        assertNull(GooglePlacesParser.error("not json"))
    }
}
