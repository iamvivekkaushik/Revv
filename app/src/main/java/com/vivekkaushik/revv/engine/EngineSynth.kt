package com.vivekkaushik.revv.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
 * Then, unlike engine-sim, it plays in stereo, each bank's pipe on its own side; it gets its low
 * end back, which the derivative thins out, or on a phone's speaker the overtones of it; and it is
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

    /** How much of each exhaust goes to the left and right. */
    private var exhaustLeft = DoubleArray(0)
    private var exhaustRight = DoubleArray(0)
    private var convolverMid: Convolver? = null

    /** For the difference between the sides, only with a pipe per bank. */
    private var convolverSide: Convolver? = null

    /** The echoes' part in the sides, see [configure]. */
    private var convolverWidth: Convolver? = null
    private var bassDb = 0.0
    private var shelfDb = Double.NaN
    private val left = Channel(sampleRate.toDouble())
    private val right = Channel(sampleRate.toDouble())
    private val sideCut1 = Biquad.highPass(CENTRE_BELOW, sampleRate.toDouble())
    private val sideCut2 = Biquad.highPass(CENTRE_BELOW, sampleRate.toDouble())
    private val harmonics = BassHarmonics(SMALL_SPEAKER_CORNER, SMALL_SPEAKER_CORNER, sampleRate.toDouble())
    private val compressor = Compressor(COMPRESS_ABOVE_DB, COMPRESS_RATIO, BLOCK.toDouble() / sampleRate)
    private var squeeze = 1.0

    /** Playing through a phone's own speaker, which can't play the low end, rather than the car's. */
    @Volatile
    var smallSpeaker = false

    /** In stereo, rather than the same from both sides. */
    @Volatile
    var surround = true
    private var sides = 1.0

    private var crank = 0.0
    private var rpm = 0.0
    private var load = 0.0
    private var gain = 0.0
    private var overrunSeconds = 0.0
    private val mixedMid = DoubleArray(BLOCK)
    private val mixedSide = DoubleArray(BLOCK)
    private val convolvedMid = DoubleArray(BLOCK)
    private val convolvedSide = DoubleArray(BLOCK)
    private val convolvedWidth = DoubleArray(BLOCK)
    private val outLeft = DoubleArray(BLOCK)
    private val outRight = DoubleArray(BLOCK)

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
        val newLayout = layout != this.layout || voices.isEmpty()
        if (newLayout) {
            this.layout = layout
            voices = voicesOf(layout)
            exhausts = Array(layout.exhaustMetres.size) { Exhaust(sampleRate.toDouble(), noise) }
            exhaustInput = DoubleArray(exhausts.size)
            // A pipe per bank: each comes out on its own side, with some of it on the other.
            val count = exhausts.size
            val pans = DoubleArray(count) { if (count == 1) 0.0 else EXHAUST_SPREAD * (2.0 * it / (count - 1) - 1) }
            exhaustLeft = DoubleArray(count) { sqrt(2.0) * cos((pans[it] + 1) * PI / 4) }
            exhaustRight = DoubleArray(count) { sqrt(2.0) * sin((pans[it] + 1) * PI / 4) }
        }
        if (note != this.note || newLayout || convolverMid == null) {
            convolverMid = Convolver(impulse, BLOCK)
            convolverSide = if (exhausts.size > 1) Convolver(impulse, BLOCK) else null
            // No two pipes are quite the same length: heard a little longer on the left and
            // shorter on the right, the echoes part ways while the pulses stay together. Only the
            // difference goes to the sides, so both together sound just as before.
            val longer = ImpulseResponse.stretched(impulse, 1 + PIPE_DIFFERENCE)
            val shorter = ImpulseResponse.stretched(impulse, 1 - PIPE_DIFFERENCE)
            convolverWidth = Convolver(FloatArray(longer.size) { (WIDTH * (longer[it] - shorter.getOrElse(it) { 0f }) / 2).toFloat() }, BLOCK)
        }
        this.note = note
        this.crackle = crackle
        bassDb = bass
        val shelf = bass * note.bass
        if (shelf != shelfDb) {
            left.bass(shelf)
            right.bass(shelf)
        }
        shelfDb = shelf
    }

    /**
     * Fills [out] (at least [BLOCK] × [CHANNELS] long) with the next block, left and right
     * interleaved, moving smoothly from the last block's engine and [volume] (a gain, 0 to
     * [MAX_GAIN]) to these.
     */
    fun render(out: FloatArray, state: EngineState, volume: Double) {
        val convolverMid = convolverMid ?: error("Not configured")
        val convolverWidth = convolverWidth ?: error("Not configured")
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
            sample(n, rpm, load, popChance)
        }
        rpm = toRpm
        load = toLoad

        convolverMid.process(mixedMid, convolvedMid)
        val fromSides = sides
        val toSides = if (surround) 1.0 else 0.0
        if (fromSides > 0 || toSides > 0) {
            // Off, the sides weren't kept up: start them from silence rather than from back then.
            if (fromSides == 0.0) {
                convolverWidth.clear()
                convolverSide?.clear()
            }
            convolverWidth.process(mixedMid, convolvedWidth)
            convolverSide?.process(mixedSide, convolvedSide) ?: convolvedSide.fill(0.0)
        }
        sides = toSides
        val loudness = OUTPUT_GAIN * note.loudness
        val small = smallSpeaker
        val overtones = HARMONICS * bassDb / MAX_BASS_DB
        var power = 0.0
        for (n in 0 until BLOCK) {
            // The low end stays in the middle: parted, it would thin out where the speakers meet.
            val mid = convolvedMid[n]
            val spread = fromSides + (toSides - fromSides) * (n + 1).toDouble() / BLOCK
            val side = if (spread > 0) spread * sideCut2.process(sideCut1.process(convolvedSide[n] + convolvedWidth[n])) else 0.0
            var x = mid + side
            var y = mid - side
            if (small) {
                val growl = overtones * harmonics.process((x + y) / 2)
                x = left.small(x) + growl
                y = right.small(y) + growl
            } else {
                x = left.full(x)
                y = right.full(y)
            }
            x *= loudness
            y *= loudness
            outLeft[n] = x
            outRight[n] = y
            power += x * x + y * y
        }
        val fromSqueeze = squeeze
        val toSqueeze = compressor.gain(power / (CHANNELS * BLOCK))
        val toGain = volume.coerceIn(0.0, MAX_GAIN)
        var loudest = 0.0
        for (n in 0 until BLOCK) {
            val t = (n + 1).toDouble() / BLOCK
            val g = (fromGain + (toGain - fromGain) * t) * (fromSqueeze + (toSqueeze - fromSqueeze) * t)
            val x = SoftClip.process(outLeft[n] * g)
            val y = SoftClip.process(outRight[n] * g)
            loudest = max(loudest, max(abs(x), abs(y)))
            out[CHANNELS * n] = x.toFloat()
            out[CHANNELS * n + 1] = y.toFloat()
        }
        gain = toGain
        squeeze = toSqueeze
        peak = loudest
    }

    /** Sample [n] of the exhausts, panned, before the impulse response. */
    private fun sample(n: Int, rpm: Double, load: Double, popChance: Double) {
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
        var l = 0.0
        var r = 0.0
        for (i in exhausts.indices) {
            val pipe = exhausts[i].process(exhaustInput[i], layout.jitter, note.noise, note.highFrequencyGain)
            l += pipe * exhaustLeft[i]
            r += pipe * exhaustRight[i]
        }
        mixedMid[n] = (l + r) / 2
        mixedSide[n] = (l - r) / 2
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

    /** One side's filters after the convolution, for the car's speakers or a phone's. */
    private class Channel(private val sampleRate: Double) {
        private val shelf = Biquad.lowShelf(BASS_CORNER, 0.0, sampleRate)
        private val rumble = Biquad.highPass(RUMBLE_CORNER, sampleRate)
        private val speaker1 = Biquad.highPass(SMALL_SPEAKER_CORNER, sampleRate)
        private val speaker2 = Biquad.highPass(SMALL_SPEAKER_CORNER, sampleRate)

        fun bass(db: Double) {
            shelf.lowShelf(BASS_CORNER, db, sampleRate)
        }

        fun full(x: Double) = rumble.process(shelf.process(x))

        /** Without what a phone's speaker can't play, so it doesn't use up the headroom. */
        fun small(x: Double) = speaker2.process(speaker1.process(x))
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

        /** Left and right, interleaved. */
        const val CHANNELS = 2

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

        /** Below what a phone's speaker plays. */
        private const val SMALL_SPEAKER_CORNER = 300.0

        /** How loud the low end's overtones play on a phone's speaker, with the bass all the way up. */
        private const val HARMONICS = 2.0

        /** The most the bass setting adds, in decibels. */
        const val MAX_BASS_DB = 15.0

        /** Below this both sides play the same. */
        private const val CENTRE_BELOW = 150.0

        /** How far to the sides two pipes are, 0 together to 1 apart. */
        private const val EXHAUST_SPREAD = 0.6

        /** How much longer the left pipe sounds than the right. */
        private const val PIPE_DIFFERENCE = 0.03

        /** How much of the pipes' difference to play: more and the sides start to cancel out. */
        private const val WIDTH = 0.6

        /** Into 0 until 720°; no floor(), which is a slow native call in debug builds. */
        private fun wrap(degrees: Double): Double {
            var d = degrees
            while (d < 0) d += CYCLE
            while (d >= CYCLE) d -= CYCLE
            return d
        }
    }
}
