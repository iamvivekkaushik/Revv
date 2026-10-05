package com.vivekkaushik.revv.ui.hmi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenStateTest {

    private val home = ScreenState()

    @Test
    fun back_fromAnAppOpenedAtHome_goesHome() {
        assertNull(home.open(HmiApp.Vehicle).back().app)
    }

    @Test
    fun back_returnsThroughEachAppInTurn() {
        val settings = home.open(HmiApp.Apps).open(HmiApp.Settings)
        val apps = settings.back()
        assertEquals(HmiApp.Apps, apps.app)
        assertNull(apps.back().app)
    }

    @Test
    fun reopeningAnAppInTheStack_movesItToTheTop() {
        val state = home.open(HmiApp.Phone).open(HmiApp.Maps).open(HmiApp.Phone)
        assertEquals(listOf(HmiApp.Maps), state.previous)
        assertEquals(HmiApp.Maps, state.back().app)
        assertNull(state.back().back().app)
    }

    @Test
    fun openingTheOpenApp_changesNothing() {
        val phone = home.open(HmiApp.Phone)
        assertEquals(phone, phone.open(HmiApp.Phone))
    }

    @Test
    fun home_forgetsTheStack() {
        val state = home.open(HmiApp.Apps).open(HmiApp.Settings).home(animate = true)
        assertEquals(home, state)
        assertEquals(1, home.open(HmiApp.Apps).home(animate = false).resetCount)
    }

    @Test
    fun back_atHome_staysHome() {
        assertEquals(home, home.back())
    }

    @Test
    fun back_fromFullScreenCarPlay_returnsToTheAutoScreen() {
        val full = home.open(HmiApp.Phone).open(HmiApp.Auto).copy(carPlayFullScreen = true)
        val back = full.back()
        assertEquals(HmiApp.Auto, back.app)
        assertFalse(back.carPlayFullScreen)
        assertEquals(HmiApp.Phone, back.back().app)
    }

    @Test
    fun leavingTheAutoScreen_endsFullScreenCarPlay() {
        val full = home.open(HmiApp.Auto).copy(carPlayFullScreen = true)
        assertFalse(full.open(HmiApp.Settings).carPlayFullScreen)
        assertFalse(full.home(animate = true).carPlayFullScreen)
        assertFalse(full.home(animate = false).carPlayFullScreen)
    }
}
