package com.vivekkaushik.revv.system

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunTest {

    private class Place(val lat: Double, val lon: Double, val zone: String)

    private val mumbai = Place(19.0760, 72.8777, "Asia/Kolkata")
    private val london = Place(51.5074, -0.1278, "Europe/London")
    private val sydney = Place(-33.8688, 151.2093, "Australia/Sydney")
    private val tromso = Place(69.6492, 18.9553, "Europe/Oslo")

    private fun at(place: Place, time: String) = LocalDateTime.parse(time).atZone(ZoneId.of(place.zone)).toInstant()

    /** The next sunrise or sunset after [time], as local time to compare with published tables. */
    private fun next(place: Place, time: String) =
        Sun.nextChange(at(place, time), place.lat, place.lon)!!.atZone(ZoneId.of(place.zone)).toLocalDateTime()

    /** Within two minutes of [expected]; published times are rounded and refraction varies. */
    private fun assertNear(expected: String, actual: LocalDateTime) {
        val off = Duration.between(LocalDateTime.parse(expected), actual).abs()
        assertTrue("expected about $expected, was $actual", off <= Duration.ofMinutes(2))
    }

    @Test
    fun matchesPublishedSunriseAndSunset() {
        assertNear("2026-10-02T06:29", next(mumbai, "2026-10-02T00:00"))
        assertNear("2026-10-02T18:26", next(mumbai, "2026-10-02T12:00"))
        // Midsummer and midwinter, across British Summer Time.
        assertNear("2026-06-21T04:43", next(london, "2026-06-21T00:00"))
        assertNear("2026-06-21T21:21", next(london, "2026-06-21T12:00"))
        assertNear("2026-12-21T08:03", next(london, "2026-12-21T00:00"))
        assertNear("2026-12-21T15:53", next(london, "2026-12-21T12:00"))
        // The southern hemisphere's summer.
        assertNear("2026-01-15T05:59", next(sydney, "2026-01-15T00:00"))
        assertNear("2026-01-15T20:09", next(sydney, "2026-01-15T12:00"))
    }

    @Test
    fun afterSunset_theNextChangeIsTomorrowsSunrise() {
        assertNear("2026-10-03T06:29", next(mumbai, "2026-10-02T20:00"))
    }

    @Test
    fun tellsNightFromDay() {
        assertTrue(Sun.isDown(at(mumbai, "2026-10-02T00:30"), mumbai.lat, mumbai.lon))
        assertFalse(Sun.isDown(at(mumbai, "2026-10-02T12:00"), mumbai.lat, mumbai.lon))
        assertTrue(Sun.isDown(at(mumbai, "2026-10-02T19:00"), mumbai.lat, mumbai.lon))
    }

    @Test
    fun polarNightAndMidnightSun_haveNoChange() {
        val winter = at(tromso, "2026-12-21T12:00")
        assertTrue(Sun.isDown(winter, tromso.lat, tromso.lon))
        assertNull(Sun.nextChange(winter, tromso.lat, tromso.lon))
        val summer = at(tromso, "2026-06-21T00:00")
        assertFalse(Sun.isDown(summer, tromso.lat, tromso.lon))
        assertNull(Sun.nextChange(summer, tromso.lat, tromso.lon))
    }

    @Test
    fun noonSunInMumbai_isHighInOctober() {
        // Declination about -3.5° on 2 October: 90 - 19.1 - 3.5.
        assertEquals(67.4, Sun.elevation(at(mumbai, "2026-10-02T12:25"), mumbai.lat, mumbai.lon), 0.5)
    }
}
