package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.nav.Turn
import org.junit.Assert.assertEquals
import org.junit.Test

class NavFormatTest {

    @Test
    fun distance_roundsLikeACountdown() {
        assertEquals("0" to "M", NavFormat.distance(3.0))
        assertEquals("120" to "M", NavFormat.distance(123.0))
        assertEquals("350" to "M", NavFormat.distance(347.0))
        assertEquals("950" to "M", NavFormat.distance(960.0))
        assertEquals("1.0" to "KM", NavFormat.distance(980.0))
        assertEquals("1.2" to "KM", NavFormat.distance(1_234.0))
        assertEquals("9.9" to "KM", NavFormat.distance(9_940.0))
        assertEquals("13" to "KM", NavFormat.distance(12_600.0))
    }

    @Test
    fun kilometres_keepOneDecimalUntilFarAway() {
        assertEquals("9.4", NavFormat.kilometres(9_400.0))
        assertEquals("0.3", NavFormat.kilometres(260.0))
        assertEquals("126", NavFormat.kilometres(126_000.0))
    }

    @Test
    fun duration_switchesToHoursPastAnHour() {
        assertEquals("18" to "MIN", NavFormat.duration(1_080.0))
        assertEquals("1" to "MIN", NavFormat.duration(59.0))
        assertEquals("1:05" to "H", NavFormat.duration(3_900.0))
    }

    @Test
    fun caption_matchesTheDesign() {
        assertEquals("NEXT TURN · RIGHT", NavFormat.caption(Turn.Right, null))
        assertEquals("NEXT · ROUNDABOUT · EXIT 2", NavFormat.caption(Turn.Roundabout, 2))
        assertEquals("NEXT · ARRIVE", NavFormat.caption(Turn.Arrive, null))
    }

    @Test
    fun leftTurns_shareTheRightHandArrows() {
        assertEquals(NavFormat.icon(Turn.Right), NavFormat.icon(Turn.Left))
        assertEquals(NavFormat.icon(Turn.KeepRight), NavFormat.icon(Turn.KeepLeft))
    }
}
