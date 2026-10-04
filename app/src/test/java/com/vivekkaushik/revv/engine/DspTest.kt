package com.vivekkaushik.revv.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
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

    private fun steadyGain(filter: ButterworthLowPass, hz: Double, rate: Double): Double {
        var peak = 0.0
        for (n in 0 until 20_000) {
            val y = filter.process(if (hz == 0.0) 1.0 else sin(2 * PI * hz * n / rate))
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
