package com.vivekkaushik.revv.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EngineSynthTest {

    @Test
    fun pulsesComeOncePerFiring() {
        // Four cylinders at 3,000 rpm fire 100 times a second; six at 4,000 rpm 200 times.
        assertEquals(441.0, strongestPeriod(EngineLayout.INLINE_4, 3000.0), 4.0)
        assertEquals(220.5, strongestPeriod(EngineLayout.INLINE_6, 4000.0), 3.0)
    }

    @Test
    fun everyEngineStaysInRangeAndWorkingIsLouderThanIdling() {
        for (layout in EngineLayout.entries) for (note in ExhaustNote.entries) {
            val idle = render(layout, note, EngineState(850.0, 0.07, overrun = false, running = true), seconds = 1.5)
            val flatOut = render(layout, note, EngineState(6000.0, 1.0, overrun = false, running = true), seconds = 1.5)
            val name = "$layout / $note"
            assertTrue("$name finite", flatOut.all { it.isFinite() } && idle.all { it.isFinite() })
            assertTrue("$name within full scale", flatOut.all { abs(it) <= 1f })
            assertTrue("$name idle audible: ${rms(idle)}", rms(idle) > 0.01)
            // A muffler swallows a lot of the high revs, so only the sporty exhausts must roar.
            val louder = if (note == ExhaustNote.SPORT) 3.0 else 1.3
            assertTrue("$name flat out louder: ${rms(flatOut)} vs ${rms(idle)}", rms(flatOut) > louder * rms(idle))
        }
    }

    @Test
    fun goesQuietOnceTheEngineStops() {
        val synth = synth(EngineLayout.V8_CROSS_PLANE, ExhaustNote.SPORT)
        val block = FloatArray(EngineSynth.BLOCK)
        repeat(200) { synth.render(block, EngineState(3000.0, 0.5, overrun = false, running = true), 1.0) }
        assertTrue(synth.peak > 0.01)
        repeat(200) { synth.render(block, EngineState.Off, 1.0) }
        assertTrue("peak ${synth.peak}", synth.peak < 1e-4)
    }

    @Test
    fun liftingOffPops() {
        val coasting = EngineState(4500.0, 0.0, overrun = true, running = true)
        val quiet = render(EngineLayout.INLINE_4, ExhaustNote.SPORT, coasting, seconds = 1.0, crackle = false)
        val popping = render(EngineLayout.INLINE_4, ExhaustNote.SPORT, coasting, seconds = 1.0, crackle = true)
        assertTrue("pops ${peak(popping)} vs ${peak(quiet)}", peak(popping) > 1.5 * peak(quiet))
    }

    /** Writes WAVs to listen to, with REVV_ENGINE_RENDER set to a folder: a rev for every engine and exhaust. */
    @Test
    fun renderRevs() {
        val folder = System.getenv("REVV_ENGINE_RENDER") ?: return assumeTrue(false)
        File(folder).mkdirs()
        for (layout in EngineLayout.entries) for (note in ExhaustNote.entries) {
            val synth = synth(layout, note)
            val block = FloatArray(EngineSynth.BLOCK)
            val samples = ArrayList<Float>()
            var elapsed = 0L
            while (elapsed < PreviewRev.NANOS) {
                synth.render(block, PreviewRev.at(elapsed), 1.0)
                block.forEach { samples += it }
                elapsed += EngineSynth.BLOCK * 1_000_000_000L / EngineSynth.SAMPLE_RATE
            }
            File(folder, "${layout.name.lowercase()}-${note.name.lowercase()}.wav").writeBytes(wav(samples))
        }
    }

    private fun strongestPeriod(layout: EngineLayout, rpm: Double): Double {
        val samples = render(layout, ExhaustNote.SPORT, EngineState(rpm, 0.6, overrun = false, running = true), seconds = 2.0)
        val tail = samples.copyOfRange(samples.size / 2, samples.size)
        val lags = 110..600
        val best = lags.maxBy { lag -> (0 until tail.size - lag).sumOf { tail[it].toDouble() * tail[it + lag] } }
        return best.toDouble()
    }

    private fun render(layout: EngineLayout, note: ExhaustNote, state: EngineState, seconds: Double, crackle: Boolean = true): FloatArray {
        val synth = synth(layout, note, crackle)
        val block = FloatArray(EngineSynth.BLOCK)
        val blocks = (seconds * EngineSynth.SAMPLE_RATE / EngineSynth.BLOCK).toInt()
        val out = FloatArray(blocks * EngineSynth.BLOCK)
        repeat(blocks) { i ->
            synth.render(block, state, 1.0)
            block.copyInto(out, destinationOffset = i * EngineSynth.BLOCK)
        }
        // The first half second is the engine and the filters settling.
        return out.copyOfRange(EngineSynth.SAMPLE_RATE / 2, out.size)
    }

    private fun synth(layout: EngineLayout, note: ExhaustNote, crackle: Boolean = true) = EngineSynth().apply {
        configure(layout, note, ImpulseResponse.read(File("src/main/assets/${note.impulse}").readBytes()), crackle)
    }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun peak(samples: FloatArray) = samples.maxOf { abs(it) }

    private fun wav(samples: List<Float>): ByteArray {
        val out = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
        out.putInt(16).putShort(1).putShort(1).putInt(EngineSynth.SAMPLE_RATE).putInt(EngineSynth.SAMPLE_RATE * 2)
        out.putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
        return out.array()
    }
}
