package com.vivekkaushik.revv.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
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
            val turnedUp = render(layout, note, EngineState(6000.0, 1.0, overrun = false, running = true), seconds = 1.5, volume = EngineSynth.MAX_GAIN)
            val name = "$layout / $note"
            assertTrue("$name finite", flatOut.all { it.isFinite() } && idle.all { it.isFinite() })
            assertTrue("$name within full scale", turnedUp.all { abs(it) < 1f })
            assertTrue("$name idle audible: ${rms(idle)}", rms(idle) > 0.01)
            // A muffler swallows a lot of the high revs, so only the sporty exhausts must roar, and
            // a V12 fires so evenly that it idles closest to how it revs.
            val louder = if (note == ExhaustNote.SPORT) 1.9 else 1.3
            val heardIdle = rms(heard(mono(idle)))
            val heardFlatOut = rms(heard(mono(flatOut)))
            assertTrue("$name flat out louder: $heardFlatOut vs $heardIdle", heardFlatOut > louder * heardIdle)
        }
    }

    @Test
    fun cruisingPlaysAboutAsLoudAsMusic() {
        for (layout in EngineLayout.entries) for (note in ExhaustNote.entries) {
            val cruise = render(layout, note, EngineState(2200.0, 0.35, overrun = false, running = true), seconds = 1.5)
            val dbfs = 20 * log10(rms(cruise))
            assertTrue("$layout / $note cruising at $dbfs dBFS", dbfs > -18)
        }
    }

    @Test
    fun bassTurnsUpTheLowEnd() {
        val idle = EngineState(850.0, 0.1, overrun = false, running = true)
        val flat = render(EngineLayout.V8_CROSS_PLANE, ExhaustNote.SPORT, idle, seconds = 1.5, bass = 0.0)
        val boosted = render(EngineLayout.V8_CROSS_PLANE, ExhaustNote.SPORT, idle, seconds = 1.5, bass = 12.0)
        val gain = 20 * log10(lowShare(mono(boosted)) / lowShare(mono(flat)))
        assertTrue("low end up $gain dB", gain > 6)
    }

    @Test
    fun soundsFullFromTheLowEndToTheHiss() {
        // Next to music in a car, engine-sim's sound was 8-10 dB short below 80 Hz and 20 dB
        // above 2.5 kHz. Against the mids, where the exhausts resonate, the sporty one idled
        // 13-22 dB short below 80 Hz and every one cruised 24-31 dB short above 2.5 kHz.
        val idle = EngineState(800.0, 0.15, overrun = false, running = true)
        val cruise = EngineState(2000.0, 0.3, overrun = false, running = true)
        for (note in listOf(ExhaustNote.STOCK, ExhaustNote.SPORT)) for (layout in listOf(EngineLayout.V6, EngineLayout.V12)) {
            val idling = mono(render(layout, note, idle, seconds = 1.5))
            val low = 20 * log10(rms(band(idling, 40.0, 80.0)) / rms(band(idling, 320.0, 640.0)))
            val cruising = mono(render(layout, note, cruise, seconds = 1.5))
            val hiss = 20 * log10(rms(band(cruising, 2560.0, 5120.0)) / rms(band(cruising, 320.0, 640.0)))
            assertTrue("$layout / $note low end $low dB against the mids", low > 0)
            assertTrue("$layout / $note hiss $hiss dB under the mids", hiss > -15)
        }
    }

    @Test
    fun banksPlayFromTheirOwnSidesWithTheLowEndInTheMiddle() {
        val cruise = EngineState(2500.0, 0.5, overrun = false, running = true)
        for (layout in listOf(EngineLayout.V8_CROSS_PLANE, EngineLayout.INLINE_4)) {
            val stereo = render(layout, ExhaustNote.SPORT, cruise, seconds = 1.5)
            val apart = correlation(channel(stereo, 0), channel(stereo, 1))
            val low = correlation(lowPassed(channel(stereo, 0), 80.0), lowPassed(channel(stereo, 1), 80.0))
            // A V8's banks are their own pipes; even one pipe's echoes part a little.
            assertTrue("$layout sides apart: $apart", apart < if (layout == EngineLayout.V8_CROSS_PLANE) 0.8 else 0.97)
            assertTrue("$layout low end together: $low", low > 0.95)
        }
    }

    @Test
    fun withoutSurroundBothSidesPlayTheSame() {
        val cruise = EngineState(2500.0, 0.5, overrun = false, running = true)
        val stereo = render(EngineLayout.V8_CROSS_PLANE, ExhaustNote.SPORT, cruise, seconds = 1.0, surround = false)
        assertTrue(channel(stereo, 0).contentEquals(channel(stereo, 1)))
        // And what both play is what surround plays from the middle.
        val surround = render(EngineLayout.V8_CROSS_PLANE, ExhaustNote.SPORT, cruise, seconds = 1.0)
        assertEquals(rms(mono(surround)), rms(channel(stereo, 0)), 0.1 * rms(mono(surround)))
    }

    @Test
    fun switchingToAPipePerBankSpreadsThem() {
        val synth = synth(EngineLayout.INLINE_4, ExhaustNote.SPORT)
        val impulse = ImpulseResponse.read(File("src/main/assets/${ExhaustNote.SPORT.impulse}").readBytes())
        synth.configure(EngineLayout.V8_CROSS_PLANE, ExhaustNote.SPORT, impulse, crackle = false, bass = 9.0)
        val block = FloatArray(EngineSynth.BLOCK * EngineSynth.CHANNELS)
        val out = ArrayList<Float>()
        repeat(400) {
            synth.render(block, EngineState(2500.0, 0.5, overrun = false, running = true), 1.0)
            if (it >= 200) block.forEach { sample -> out += sample }
        }
        val stereo = out.toFloatArray()
        val apart = correlation(channel(stereo, 0), channel(stereo, 1))
        assertTrue("banks on their own sides: $apart", apart < 0.8)
    }

    @Test
    fun phoneSpeakerGetsWhatItCanPlay() {
        val idle = EngineState(850.0, 0.1, overrun = false, running = true)
        val bass = EngineSynth.MAX_BASS_DB
        val car = render(EngineLayout.INLINE_4, ExhaustNote.STOCK, idle, seconds = 1.5, bass = bass)
        val phone = render(EngineLayout.INLINE_4, ExhaustNote.STOCK, idle, seconds = 1.5, bass = bass, smallSpeaker = true)
        val gain = 20 * log10(rms(phoneSpeaker(mono(phone))) / rms(phoneSpeaker(mono(car))))
        assertTrue("phone speaker plays $gain dB louder", gain > 4)
    }

    @Test
    fun goesQuietOnceTheEngineStops() {
        val synth = synth(EngineLayout.V8_CROSS_PLANE, ExhaustNote.SPORT)
        val block = FloatArray(EngineSynth.BLOCK * EngineSynth.CHANNELS)
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
        // And thump: the pipe rings under each crack, which used to be all there was.
        val thump = peak(lowPassed(mono(popping), 150.0)) / peak(lowPassed(mono(quiet), 150.0))
        assertTrue("thump ${20 * log10(thump)} dB", thump > 8)
    }

    /**
     * Writes WAVs to listen to, with REVV_ENGINE_RENDER set to a folder: a rev for every engine and
     * exhaust, at the default volume or REVV_ENGINE_VOLUME's (0 to 30).
     */
    @Test
    fun renderRevs() {
        val folder = System.getenv("REVV_ENGINE_RENDER") ?: return assumeTrue(false)
        File(folder).mkdirs()
        val volume = System.getenv("REVV_ENGINE_VOLUME")?.let { EngineSoundSettings(volume = it.toInt()) } ?: EngineSoundSettings()
        for (layout in EngineLayout.entries) for (note in ExhaustNote.entries) {
            val synth = synth(layout, note)
            val block = FloatArray(EngineSynth.BLOCK * EngineSynth.CHANNELS)
            val samples = ArrayList<Float>()
            var elapsed = 0L
            while (elapsed < PreviewRev.NANOS) {
                synth.render(block, PreviewRev.at(elapsed), volume.gain)
                block.forEach { samples += it }
                elapsed += EngineSynth.BLOCK * 1_000_000_000L / EngineSynth.SAMPLE_RATE
            }
            File(folder, "${layout.name.lowercase()}-${note.name.lowercase()}.wav").writeBytes(wav(samples))
        }
    }

    private fun strongestPeriod(layout: EngineLayout, rpm: Double): Double {
        val samples = mono(render(layout, ExhaustNote.SPORT, EngineState(rpm, 0.6, overrun = false, running = true), seconds = 2.0))
        val tail = samples.copyOfRange(samples.size / 2, samples.size)
        val lags = 110..600
        val best = lags.maxBy { lag -> (0 until tail.size - lag).sumOf { tail[it].toDouble() * tail[it + lag] } }
        return best.toDouble()
    }

    private fun render(
        layout: EngineLayout,
        note: ExhaustNote,
        state: EngineState,
        seconds: Double,
        crackle: Boolean = true,
        bass: Double = EngineSoundSettings().bassDb,
        volume: Double = 1.0,
        smallSpeaker: Boolean = false,
        surround: Boolean = true,
    ): FloatArray {
        val synth = synth(layout, note, crackle, bass)
        synth.smallSpeaker = smallSpeaker
        synth.surround = surround
        val size = EngineSynth.BLOCK * EngineSynth.CHANNELS
        val block = FloatArray(size)
        val blocks = (seconds * EngineSynth.SAMPLE_RATE / EngineSynth.BLOCK).toInt()
        val out = FloatArray(blocks * size)
        repeat(blocks) { i ->
            synth.render(block, state, volume)
            block.copyInto(out, destinationOffset = i * size)
        }
        // The first half second is the engine and the filters settling.
        return out.copyOfRange(EngineSynth.SAMPLE_RATE / 2 * EngineSynth.CHANNELS, out.size)
    }

    private fun channel(stereo: FloatArray, which: Int) = FloatArray(stereo.size / 2) { stereo[2 * it + which] }

    private fun mono(stereo: FloatArray) = FloatArray(stereo.size / 2) { (stereo[2 * it] + stereo[2 * it + 1]) / 2 }

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val ab = a.indices.sumOf { a[it].toDouble() * b[it] }
        return ab / sqrt(a.sumOf { it.toDouble() * it } * b.sumOf { it.toDouble() * it })
    }

    private fun lowPassed(samples: FloatArray, hz: Double): FloatArray {
        val lowPass = ButterworthLowPass(hz, EngineSynth.SAMPLE_RATE.toDouble())
        return FloatArray(samples.size) { lowPass.process(samples[it].toDouble()).toFloat() }
    }

    /** Roughly what a phone's speaker plays: nothing much below 350 Hz. */
    private fun phoneSpeaker(samples: FloatArray): FloatArray {
        val first = Biquad.highPass(350.0, EngineSynth.SAMPLE_RATE.toDouble())
        val second = Biquad.highPass(350.0, EngineSynth.SAMPLE_RATE.toDouble())
        return FloatArray(samples.size) { second.process(first.process(samples[it].toDouble())).toFloat() }
    }

    private fun synth(layout: EngineLayout, note: ExhaustNote, crackle: Boolean = true, bass: Double = EngineSoundSettings().bassDb) =
        EngineSynth().apply {
            configure(layout, note, ImpulseResponse.read(File("src/main/assets/${note.impulse}").readBytes()), crackle, bass)
        }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    /** How much of [samples]' RMS lies below 100 Hz. */
    private fun band(samples: FloatArray, from: Double, to: Double): FloatArray {
        val rate = EngineSynth.SAMPLE_RATE.toDouble()
        val highPass = listOf(Biquad.highPass(from, rate), Biquad.highPass(from, rate))
        val lowPass = ButterworthLowPass(to, rate)
        return FloatArray(samples.size) { lowPass.process(highPass[1].process(highPass[0].process(samples[it].toDouble()))).toFloat() }
    }

    private fun lowShare(samples: FloatArray) = rms(lowPassed(samples, 100.0)) / rms(samples)

    /** Without the deep bass, which the ear hardly counts in how loud something is, as the compressor listens. */
    private fun heard(samples: FloatArray): FloatArray {
        val highPass = Biquad.highPass(100.0, EngineSynth.SAMPLE_RATE.toDouble())
        return FloatArray(samples.size) { highPass.process(samples[it].toDouble()).toFloat() }
    }

    private fun peak(samples: FloatArray) = samples.maxOf { abs(it) }

    /** 16-bit stereo. */
    private fun wav(samples: List<Float>): ByteArray {
        val out = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
        out.putInt(16).putShort(1).putShort(2).putInt(EngineSynth.SAMPLE_RATE).putInt(EngineSynth.SAMPLE_RATE * 4)
        out.putShort(4).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
        return out.array()
    }
}
