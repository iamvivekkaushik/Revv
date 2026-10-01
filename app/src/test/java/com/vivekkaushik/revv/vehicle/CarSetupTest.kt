package com.vivekkaushik.revv.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSetupTest {

    private val swift = CarSetup.SWIFT_VXI_2015

    @Test
    fun rpmPerKmh_comesFromRatiosFinalDriveAndTyre() {
        // 165/80 R14 is 0.62 m across; first gear turns the engine about 129 times a minute per km/h.
        assertEquals(0.6196, swift.tyre.diameterMetres, 0.0005)
        val expected = listOf(128.6f, 69.0f, 46.4f, 35.0f, 27.5f)
        swift.rpmPerKmh().zip(expected).forEach { (actual, wanted) -> assertEquals(wanted, actual, 0.2f) }
    }

    @Test
    fun moreGears_guessesTheNewOnes_lessKeepsTheSpares() {
        val six = swift.withGears(6)
        assertEquals(6, six.rpmPerKmh().size)
        assertTrue("a sixth gear is taller than fifth", six.ratios[5] < six.ratios[4])
        val four = swift.withGears(4)
        assertEquals(4, four.rpmPerKmh().size)
        // Back to five, fifth is still the one entered.
        assertEquals(0.757f, four.withGears(5).ratios[4], 0.0001f)
        assertEquals(CarSetup.MAX_GEARS, swift.withGears(12).gears)
    }

    @Test
    fun tyreSizes_outsideWhatCarsUse_areInvalid() {
        assertTrue(TyreSize(215, 60, 17).valid)
        assertFalse(TyreSize(16, 80, 14).valid)
        assertEquals("185/65 R15", TyreSize(185, 65, 15).toString())
    }
}
