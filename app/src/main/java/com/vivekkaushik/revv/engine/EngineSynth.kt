package com.vivekkaushik.revv.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** What the engine is doing, as the synthesiser needs it. */
data class EngineState(
    val rpm: Double,
    /** How hard it's working: 0 with the throttle shut, 1 wide open, a little more with boost. */
    val load: Double,
    /** Coasting at revs with the throttle shut, when exhausts pop. */
    val overrun: Boolean,
    /** False once the engine has stopped or its data has. */
    val running: Boolean,
) {
    companion object {
        val Off = EngineState(rpm = 0.0, load = 0.0, overrun = false, running = false)
    }
}

/**
 * Engine sound the way engine-sim makes it, with the physics swapped for a model of what it
 * listens to. engine-sim simulates the crank, pistons and gas flow, then plays the pressure in
 * each cylinder's exhaust runner; here the car's own rpm and load drive a pulse per cylinder per
 * cycle instead: a sharp blowdown when the exhaust valve opens, then the piston pushing the rest
 * out. From there it follows engine-sim's synthesizer step by step:
 *
 * - each pulse reaches the tailpipe late by its pipe length over the speed of sound, quieter by the
 *   length squared, and pulses add up per exhaust;
 * - each exhaust is low-passed at 1.9 kHz, jittered, has its DC removed, and mixes its derivative
 *   with itself roughened by filtered noise;
 * - all of it is convolved with a recorded exhaust impulse response.
 *
 * Then, unlike engine-sim, it gets its low end back, which the derivative thins out, and is
 * compressed and rounded off under full scale so it can play about as loud as music.
 */
class EngineSynth(private val sampleRate: Int = SAMPLE_RATE, seed: Int = 1) {

    private val noise = Noise(seed)
    private var layout = EngineLayout.DEFAULT
    private var note = ExhaustNote.DEFAULT
    private var crackle = true
    private var voices = emptyArray<Voice>()
    private var exhausts = emptyArray<Exhaust>()
    private var exhaustInput = DoubleArray(0)
    private var convolver: Convolver? = null
    private var shelfDb = Double.NaN
    private val bassShelf = Biquad.lowShelf(BASS_CORNER, 0.0, sampleRate.toDouble())
    private val rumbleCut = Biquad.highPass(RUMBLE_CORNER, sampleRate.toDouble())
    private val compressor = Compressor(COMPRESS_ABOVE_DB, COMPRESS_RATIO, BLOCK.toDouble() / sampleRate)
    private var squeeze = 1.0

    private var crank = 0.0
    private var rpm = 0.0
    private var load = 0.0
    private var gain = 0.0
    private var overrunSeconds = 0.0
    private val mixed = DoubleArray(BLOCK)
    private val convolved = DoubleArray(BLOCK)

    // Per-sample factors for the pulses' exponential rise and fall: multiplying an envelope by
    // them each sample costs far less than exp(), which isn't an intrinsic in every ART build.
    private val riseStep = exp(-1.0 / (sampleRate * BLOWDOWN_RISE))
    private var decayStep = 0.0
    private val popRiseStep = exp(-1.0 / (sampleRate * POP_RISE))
    private val popDecayStep = exp(-1.0 / (sampleRate * POP_DECAY))
    private val popSamples = (POP_SECONDS * sampleRate).toInt()

    /** The loudest sample of the last block, to tell when it has gone quiet. */
    var peak = 0.0
        private set

    /**
     * Switches engine and exhaust; [impulse] is [note]'s response, as [ImpulseResponse.read] gives
     * it, and [bass] how many decibels to add below about 200 Hz, of which [ExhaustNote.bass] takes its share.
     */
    fun configure(layout: EngineLayout, note: ExhaustNote, impulse: FloatArray, crackle: Boolean, bass: Double) {
        if (layout != this.layout || voices.isEmpty()) {
            this.layout = layout
            voices = voicesOf(layout)
            exhausts = Array(layout.exhaustMetres.size) { Exhaust(sampleRate.toDouble(), noise) }
            exhaustInput = DoubleArray(exhausts.size)
        }
        if (note != this.note || convolver == null) convolver = Convolver(impulse, BLOCK)
        this.note = note
        this.crackle = crackle
        val shelf = bass * note.bass
        if (shelf != shelfDb) bassShelf.lowShelf(BASS_CORNER, shelf, sampleRate.toDouble())
        shelfDb = shelf
    }

    /**
     * Fills [out] (at least [BLOCK] long) with the next block, moving smoothly from the last
     * block's engine and [volume] (a gain, 0 to [MAX_GAIN]) to these.
     */
    fun render(out: FloatArray, state: EngineState, volume: Double) {
        val convolver = convolver ?: error("Not configured")
        val fromRpm = rpm
        val fromLoad = load
        val fromGain = gain
        val toRpm = state.rpm.coerceIn(0.0, MAX_RPM)
        val toLoad = state.load.coerceIn(0.0, MAX_LOAD)
        overrunSeconds = if (state.overrun) overrunSeconds + BLOCK.toDouble() / sampleRate else 0.0
        val popChance = if (crackle && state.overrun && toRpm > POP_MIN_RPM) POP_CHANCE * exp(-overrunSeconds / POP_FADE_SECONDS) else 0.0
        // The blowdown dies away over a crank angle, so faster the higher the revs.
        val decaySeconds = max(BLOWDOWN_DEGREES / max(toRpm * 6.0, 1.0), BLOWDOWN_SECONDS)
        decayStep = exp(-1.0 / (sampleRate * decaySeconds))

        for (n in 0 until BLOCK) {
            val t = (n + 1).toDouble() / BLOCK
            val rpm = fromRpm + (toRpm - fromRpm) * t
            val load = fromLoad + (toLoad - fromLoad) * t
            mixed[n] = sample(rpm, load, popChance)
        }
        rpm = toRpm
        load = toLoad

        convolver.process(mixed, convolved)
        val loudness = OUTPUT_GAIN * note.loudness
        var power = 0.0
        for (n in 0 until BLOCK) {
            val x = rumbleCut.process(bassShelf.process(convolved[n])) * loudness
            convolved[n] = x
            power += x * x
        }
        val fromSqueeze = squeeze
        val toSqueeze = compressor.gain(power / BLOCK)
        val toGain = volume.coerceIn(0.0, MAX_GAIN)
        var loudest = 0.0
        for (n in 0 until BLOCK) {
            val t = (n + 1).toDouble() / BLOCK
            val g = fromGain + (toGain - fromGain) * t
            val c = fromSqueeze + (toSqueeze - fromSqueeze) * t
            val y = SoftClip.process(convolved[n] * c * g)
            loudest = max(loudest, abs(y))
            out[n] = y.toFloat()
        }
        gain = toGain
        squeeze = toSqueeze
        peak = loudest
    }

    /** One sample of all the exhausts together, before the impulse response. */
    private fun sample(rpm: Double, load: Double, popChance: Double): Double {
        exhaustInput.fill(0.0)
        if (rpm > MIN_RPM) {
            val degreesPerSecond = rpm * 6.0
            crank += degreesPerSecond / sampleRate
            if (crank >= CYCLE) crank -= CYCLE
            // engine-sim fades the sound in below 40 rpm; a starting engine passes that in an instant.
            val spinUp = min(1.0, rpm / FULL_SOUND_RPM).let { it * it * it }
            for (voice in voices) {
                val phase = wrap(crank - voice.firesAt - EXHAUST_OPENS - voice.delaySeconds * degreesPerSecond)
                // A big step back is the next cycle; small ones are the delay growing as revs rise.
                if (phase < voice.phase - CYCLE / 2) startCycle(voice, rpm, load, popChance)
                voice.phase = phase
                var pressure = 0.0
                if (phase < PULSE_END) {
                    pressure = voice.amplitude * ((1 - voice.rise) * voice.decay + push(phase))
                    voice.rise *= riseStep
                    voice.decay *= decayStep
                }
                if (voice.popAt >= 0 && phase >= voice.popAt) {
                    voice.popAt = -1.0
                    voice.popRise = 1.0
                    voice.popDecay = 1.0
                    voice.popLeft = popSamples
                }
                if (voice.popLeft > 0) {
                    pressure += voice.popAmplitude * (1 - voice.popRise) * voice.popDecay * (0.4 + noise.next())
                    voice.popRise *= popRiseStep
                    voice.popDecay *= popDecayStep
                    voice.popLeft--
                }
                exhaustInput[voice.exhaust] += pressure * voice.weight * spinUp
            }
        }
        var sum = 0.0
        for (i in exhausts.indices) sum += exhausts[i].process(exhaustInput[i], layout.jitter, note.noise, note.highFrequencyGain)
        return sum
    }

    /**
     * A new cycle: the exhaust valve has just opened. Its pulse is a blowdown, rising in a quarter
     * of a millisecond and dying away within a few tens of crank degrees, then the piston's push.
     * Measured in crank angle the decay keeps its shape as revs rise, so pulses stay distinct even
     * when twelve cylinders fire every 60°; the rise is a fixed time, so the edge that the
     * derivative turns into sound doesn't sharpen with revs and drown out the idle.
     */
    private fun startCycle(voice: Voice, rpm: Double, load: Double, popChance: Double) {
        voice.rise = 1.0
        voice.decay = 1.0
        val revs = 0.7 + 0.3 * min(rpm / 6000.0, 1.3)
        // Burns vary cycle to cycle, most at idle.
        val variation = 0.05 + 0.1 * exp(-rpm / 1500.0)
        voice.amplitude = max(0.0, (PUMPING + (1 - PUMPING) * load) * revs * (1 + variation * noise.gaussian()))
        if (popChance > 0 && noise.next() < popChance) {
            voice.popAt = POP_EARLIEST + noise.next() * POP_SPREAD
            voice.popAmplitude = (POP_MIN + POP_EXTRA * noise.next()) * revs
        }
    }

    /** The piston pushing the rest of the gas out, [phase] degrees after the valve opened: a hump, near enough a half sine. */
    private fun push(phase: Double): Double {
        if (phase <= PUSH_FROM) return 0.0
        val x = (phase - PUSH_FROM) / (PULSE_END - PUSH_FROM)
        return PUSH * 4 * x * (1 - x)
    }

    private fun voicesOf(layout: EngineLayout): Array<Voice> {
        val voices = layout.cylinders.map { cylinder ->
            val metres = cylinder.primaryMetres + layout.exhaustMetres[cylinder.exhaust]
            // engine-sim: louder the shorter its pipe, by the square of the length.
            Voice(cylinder.firesAt, cylinder.exhaust, metres / SPEED_OF_SOUND, cylinder.gain / (metres * metres))
        }
        val mean = voices.sumOf { it.weight } / voices.size
        voices.forEach { it.weight /= mean }
        return voices.toTypedArray()
    }

    private class Voice(val firesAt: Double, val exhaust: Int, val delaySeconds: Double, var weight: Double) {
        var phase = 0.0
        var amplitude = 0.0

        /** The blowdown's envelopes, from 1 at the valve opening down towards 0. */
        var rise = 0.0
        var decay = 0.0
        var popAt = -1.0
        var popAmplitude = 0.0
        var popRise = 0.0
        var popDecay = 0.0
        var popLeft = 0
    }

    /** One exhaust's share of engine-sim's Synthesizer::renderAudio, before the convolution. */
    private class Exhaust(private val sampleRate: Double, private val noise: Noise) {
        private val antialias = ButterworthLowPass(1900.0, sampleRate)
        private val jitter = JitterFilter(10, 10_000.0, sampleRate, noise)
        private val dc = OnePoleLowPass(10.0, sampleRate)
        private val air = ButterworthLowPass(2000.0, sampleRate)
        private var previous = 0.0

        fun process(x: Double, jitterScale: Double, airNoise: Double, highFrequencyGain: Double): Double {
            val sample = jitter.process(antialias.process(x), jitterScale)
            val f = sample - dc.process(sample)
            val slope = (sample - previous) * sampleRate
            previous = sample
            val roughness = airNoise * air.process(2 * noise.next() - 1) + (1 - airNoise)
            return slope * highFrequencyGain + f * roughness * (1 - highFrequencyGain)
        }
    }

    companion object {
        /** engine-sim's impulse responses are recorded at this rate. */
        const val SAMPLE_RATE = 44_100

        /** Samples rendered at a time: about 6 ms. */
        const val BLOCK = 256

        /** The loudest volume [render] takes: 6 dB over 1, which just keeps flat out clean. */
        const val MAX_GAIN = 2.0

        private const val CYCLE = 720.0
        private const val MAX_RPM = 12_000.0
        private const val MAX_LOAD = 1.5
        private const val MIN_RPM = 1.0
        private const val FULL_SOUND_RPM = 150.0
        private const val SPEED_OF_SOUND = 343.0

        /** The exhaust valve opens about 50° before bottom dead centre. */
        private const val EXHAUST_OPENS = 130.0
        private const val BLOWDOWN_RISE = 0.00025
        private const val BLOWDOWN_DEGREES = 30.0
        private const val BLOWDOWN_SECONDS = 0.0006
        private const val PUSH = 0.25
        private const val PUSH_FROM = 60.0

        /** The exhaust valve closes about 250° after it opened. */
        private const val PULSE_END = 250.0

        /** Even with the throttle shut a cylinder breathes, so its pulse never quite goes. */
        private const val PUMPING = 0.15

        private const val POP_MIN_RPM = 1800.0
        private const val POP_CHANCE = 0.05
        private const val POP_FADE_SECONDS = 1.5
        private const val POP_EARLIEST = 30.0
        private const val POP_SPREAD = 300.0
        private const val POP_MIN = 1.5
        private const val POP_EXTRA = 2.0
        private const val POP_RISE = 0.0001
        private const val POP_DECAY = 0.0012
        private const val POP_SECONDS = 0.012

        /**
         * Brings the convolved signal up to play about as loud as music: measured across every
         * engine and exhaust at a volume of 1, after the compressor, idle sits near -20 dBFS RMS,
         * cruising -14 and flat out -10, the odd peak rounded off above [SoftClip.KNEE].
         */
        private const val OUTPUT_GAIN = 0.06
        private const val COMPRESS_ABOVE_DB = -22.0
        private const val COMPRESS_RATIO = 2.0

        /** The derivative that gives the pulses their edge thins out the firing note below this. */
        private const val BASS_CORNER = 200.0

        /** Below what car speakers play, and where a boost would only eat headroom. */
        private const val RUMBLE_CORNER = 28.0

        /** Into 0 until 720°; no floor(), which is a slow native call in debug builds. */
        private fun wrap(degrees: Double): Double {
            var d = degrees
            while (d < 0) d += CYCLE
            while (d >= CYCLE) d -= CYCLE
            return d
        }
    }
}
