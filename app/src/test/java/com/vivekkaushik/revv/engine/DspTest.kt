package com.vivekkaushik.revv.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspTest {

    @Test
    fun convolverMatchesDirectConvolution() {
        val noise = Noise(7)
        val block = 64
        val impulse = FloatArray(300) { (noise.next() * 2 - 1).toFloat() }
        val input = DoubleArray(block * 9) { noise.next() * 2 - 1 }
        val convolver = Convolver(impulse, block)
        val output = DoubleArray(input.size)
        val chunk = DoubleArray(block)
        val result = DoubleArray(block)
        for (start in input.indices step block) {
            input.copyInto(chunk, startIndex = start, endIndex = start + block)
            convolver.process(chunk, result)
            result.copyInto(output, destinationOffset = start)
        }
        for (n in input.indices) {
            var expected = 0.0
            for (k in impulse.indices) if (n - k >= 0) expected += impulse[k] * input[n - k]
            assertEquals("sample $n", expected, output[n], 1e-9)
        }
    }

    @Test
    fun butterworthPassesDcAndHalvesPowerAtCutoff() {
        val rate = 44_100.0
        assertEquals(1.0, steadyGain(ButterworthLowPass(1900.0, rate), 0.0, rate), 1e-6)
        assertEquals(1 / sqrt(2.0), steadyGain(ButterworthLowPass(1900.0, rate), 1900.0, rate), 0.01)
        assertTrue(steadyGain(ButterworthLowPass(1900.0, rate), 7600.0, rate) < 0.01)
    }

    @Test
    fun lowShelfLiftsOnlyTheLowEnd() {
        val rate = 44_100.0
        assertEquals(12.0, decibels(steadyGain(Biquad.lowShelf(200.0, 12.0, rate)::process, 0.0, rate)), 0.01)
        assertEquals(0.0, decibels(steadyGain(Biquad.lowShelf(200.0, 12.0, rate)::process, 5000.0, rate)), 0.2)
        // Halfway, in decibels, at the corner.
        assertEquals(6.0, decibels(steadyGain(Biquad.lowShelf(200.0, 12.0, rate)::process, 200.0, rate)), 0.2)
    }

    @Test
    fun highPassBlocksDcAndPassesTheNote() {
        val rate = 44_100.0
        assertTrue(steadyGain(Biquad.highPass(28.0, rate)::process, 0.0, rate) < 1e-3)
        assertEquals(1 / sqrt(2.0), steadyGain(Biquad.highPass(28.0, rate)::process, 28.0, rate), 0.01)
        assertEquals(1.0, steadyGain(Biquad.highPass(28.0, rate)::process, 200.0, rate), 0.02)
    }

    @Test
    fun compressorTurnsDownOnlyWhatIsAboveItsThreshold() {
        val quiet = Compressor(-20.0, 2.0, 0.006)
        repeat(500) { assertEquals(1.0, quiet.gain(0.001), 1e-9) }
        // 10 dB over at 2:1 comes out 5 dB over.
        val loud = Compressor(-20.0, 2.0, 0.006)
        var gain = 1.0
        repeat(500) { gain = loud.gain(0.1) }
        assertEquals(-5.0, decibels(gain), 0.01)
    }

    @Test
    fun softClipLeavesTheBodyAloneAndStaysUnderFullScale() {
        assertEquals(0.5, SoftClip.process(0.5), 0.0)
        assertEquals(-0.5, SoftClip.process(-0.5), 0.0)
        var previous = 0.0
        for (i in 1..1000) {
            val y = SoftClip.process(i / 100.0)
            assertTrue("rising at ${i / 100.0}", y > previous)
            assertTrue("under full scale at ${i / 100.0}", y < SoftClip.CEILING)
            previous = y
        }
    }

    @Test
    fun impulseResponseIsTrimmedAndScaledToUnitEnergy() {
        val samples = ShortArray(500) { if (it < 200) (10_000 * sin(it / 5.0)).toInt().toShort() else 50 }
        val response = ImpulseResponse.read(wav(samples))
        assertTrue("quiet tail trimmed: ${response.size}", response.size in 190..200)
        assertEquals(1.0, response.sumOf { it.toDouble() * it }, 1e-4)
    }

    @Test
    fun bundledImpulseResponsesRead() {
        ExhaustNote.entries.forEach { note ->
            val response = ImpulseResponse.read(java.io.File("src/main/assets/${note.impulse}").readBytes())
            assertTrue("${note.name}: ${response.size}", response.size in 1000..ImpulseResponse.MAX_SAMPLES)
        }
    }

    private fun decibels(gain: Double) = 20 * log10(gain)

    private fun steadyGain(filter: ButterworthLowPass, hz: Double, rate: Double) = steadyGain(filter::process, hz, rate)

    private fun steadyGain(filter: (Double) -> Double, hz: Double, rate: Double): Double {
        var peak = 0.0
        for (n in 0 until 20_000) {
            val y = filter(if (hz == 0.0) 1.0 else sin(2 * PI * hz * n / rate))
            if (n > 10_000) peak = maxOf(peak, abs(y))
        }
        return peak
    }

    private fun wav(samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + samples.size * 2)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(44_100)
            putInt(88_200)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(samples.size * 2)
        }
        return ByteArrayOutputStream().apply {
            write(header.array())
            write(data.array())
        }.toByteArray()
    }
}
