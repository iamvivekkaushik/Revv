package com.vivekkaushik.revv.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    /** The starter motor turning the engine over: 1 engaged, falling to 0 as it lets go. */
    val starter: Double = 0.0,
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
 * Then, unlike engine-sim, it plays in stereo, each bank's pipe on its own side; it adds the firing
 * note underneath, which the derivative thins out, as the heart of the sound, and puts the rest
 * behind a muffler that opens with the throttle, so it idles deep and barks when blipped, as a
 * recorded V8 does; it adds the tailpipe's hiss on top; on a phone's speaker it plays the low end's
 * overtones instead; and it is compressed and rounded off under full scale so it can play as loud
 * as music. Turned over by the starter, it plays the starter too.
 */
class EngineSynth(private val sampleRate: Int = SAMPLE_RATE, seed: Int = 1) {

    private val noise = Noise(seed)
    private var layout = EngineLayout.DEFAULT
    private var note = ExhaustNote.DEFAULT
    private var crackle = true
    private var voices = emptyArray<Voice>()
    private var exhausts = emptyArray<Exhaust>()
    private var exhaustInput = DoubleArray(0)

    /**
     * Each exhaust's ring when a pop goes off in it: the pipe's own note, the thump, and a bang a
     * few times higher that small speakers can play. [kicks] are this sample's pops.
     */
    private var thumps = emptyArray<Resonator>()

    /** The pops' crack through the exhaust, kept out of the compressor; only run while pops ring. */
    private var convolverCrack: Convolver? = null
    private var recording: Recording? = null
    private val crackEdge = CrackEdge(sampleRate.toDouble())

    /** The burning gas's roughness in a pop, kept below the band where it would only fizz. */
    private val grit = OnePoleLowPass(GRIT_HZ, sampleRate.toDouble())
    private var crackRinging = 0
    private var duck = 1.0
    private var bangs = emptyArray<Resonator>()
    private var kicks = DoubleArray(0)

    /** Each exhaust's push, smoothed, for the hiss to follow: a whoosh per pulse rather than a click. */
    private var hissEnvelopes = emptyArray<OnePoleLowPass>()

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
    private val body = ButterworthLowPass(BODY_HZ, sampleRate.toDouble())

    /**
     * The muffler: what the exhaust recording adds over the firing note is held down to a low
     * rumble at idle and opens up as the engine works, the way a big engine sounds deep and
     * soft idling and only turns hard and bright on the throttle.
     */
    private val muffleMid = Muffler(sampleRate.toDouble())
    private val muffleSide = Muffler(sampleRate.toDouble())
    private var muffleHz = MUFFLED_HZ
    private val raspMid = Rasp(sampleRate.toDouble())
    private val raspSide = Rasp(sampleRate.toDouble())
    private val listenLeft = Biquad.highPass(SIDECHAIN_HZ, sampleRate.toDouble())
    private val listenRight = Biquad.highPass(SIDECHAIN_HZ, sampleRate.toDouble())
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

    /**
     * The starter motor: its pinion's teeth striking the flywheel's, each strike ringing its
     * housing, its armature humming, its brushes hissing, all slowing and straining into each
     * compression. [clunk] and [clack] are the solenoid throwing the pinion in.
     */
    private var starter = 0.0
    private var meshTurns = 0.0
    private var motorTurns = 0.0
    private val mesh = Resonator(MESH_HZ, MESH_SECONDS, sampleRate.toDouble())
    private val housingLow = Resonator(HOUSING_LOW_HZ, HOUSING_LOW_SECONDS, sampleRate.toDouble())
    private val housingHigh = Resonator(HOUSING_HIGH_HZ, HOUSING_HIGH_SECONDS, sampleRate.toDouble())
    private val strike = Biquad.highPass(STRIKE_FROM, sampleRate.toDouble())
    private val brushesLow = Biquad.highPass(BRUSHES_FROM, sampleRate.toDouble())
    private val brushesHigh = Biquad.lowPass(BRUSHES_TO, sampleRate.toDouble())
    private val clunk = Resonator(CLUNK_HZ, CLUNK_SECONDS, sampleRate.toDouble())
    private val clack = Resonator(CLACK_HZ, CLACK_SECONDS, sampleRate.toDouble())
    private var engaged = 0.0
    private var firstFires = 0.0
    private var crankWobble = 0.0
    private val mixedStarter = DoubleArray(BLOCK)
    private val mixedMid = DoubleArray(BLOCK)
    private val mixedSide = DoubleArray(BLOCK)
    private val mixedBody = DoubleArray(BLOCK)
    private val mixedRaspMid = DoubleArray(BLOCK)
    private val mixedRaspSide = DoubleArray(BLOCK)
    private val mixedCrack = DoubleArray(BLOCK)
    private val convolvedCrack = DoubleArray(BLOCK)
    private val mixedThumpMid = DoubleArray(BLOCK)
    private val mixedThumpSide = DoubleArray(BLOCK)
    private val thumpLeft = DoubleArray(BLOCK)
    private val thumpRight = DoubleArray(BLOCK)
    private val thumpCutLeft = Biquad.highPass(SMALL_SPEAKER_CORNER, sampleRate.toDouble())
    private val thumpCutRight = Biquad.highPass(SMALL_SPEAKER_CORNER, sampleRate.toDouble())
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
    fun configure(layout: EngineLayout, note: ExhaustNote, impulse: FloatArray, crackle: Boolean, bass: Double) =
        configure(prepare(layout, note, impulse), crackle, bass)

    /** As above, with the slow part already done by [prepare], so it can switch while playing. */
    fun configure(recording: Recording, crackle: Boolean, bass: Double) {
        val layout = recording.layout
        val note = recording.note
        val newLayout = layout != this.layout || voices.isEmpty()
        if (newLayout) {
            this.layout = layout
            voices = voicesOf(layout)
            exhausts = Array(layout.exhaustMetres.size) { Exhaust(sampleRate.toDouble(), noise, ANTIALIAS_HZ) }
            exhaustInput = DoubleArray(exhausts.size)
            kicks = DoubleArray(exhausts.size)
            hissEnvelopes = Array(exhausts.size) { OnePoleLowPass(HISS_ENVELOPE_HZ, sampleRate.toDouble()) }
            firstFires = layout.cylinders.first().firesAt
            // Four cylinders snatch at the starter; twelve overlap into an even churn.
            crankWobble = CRANK_WOBBLE / sqrt(layout.cylinders.size / 4.0)
            thumps = Array(exhausts.size) { Resonator(pipeNote(layout, it), THUMP_SECONDS, sampleRate.toDouble()) }
            bangs = Array(exhausts.size) { Resonator(pipeNote(layout, it) * BANG_NOTE, BANG_SECONDS, sampleRate.toDouble()) }
            // A pipe per bank: each comes out on its own side, with some of it on the other.
            val count = exhausts.size
            val pans = DoubleArray(count) { if (count == 1) 0.0 else EXHAUST_SPREAD * (2.0 * it / (count - 1) - 1) }
            exhaustLeft = DoubleArray(count) { sqrt(2.0) * cos((pans[it] + 1) * PI / 4) }
            exhaustRight = DoubleArray(count) { sqrt(2.0) * sin((pans[it] + 1) * PI / 4) }
        }
        if (recording !== this.recording) {
            this.recording = recording
            convolverMid = recording.mid
            convolverCrack = recording.crack
            convolverSide = recording.side
            convolverWidth = recording.width
            crackRinging = 0
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
        val fromStarter = starter
        val toStarter = state.starter.coerceIn(0.0, 1.0)
        // The solenoid throws the pinion into the flywheel with a clunk before anything turns.
        if (fromStarter == 0.0 && toStarter > 0) engaged = 1.0
        // The starter letting go as the revs climb: the engine has caught, its first firings bang out of the pipes.
        if (fromStarter >= 1.0 && toStarter < 1.0 && state.rpm > rpm) {
            for (i in kicks.indices) kicks[i] += CATCH_BANG
        }

        for (n in 0 until BLOCK) {
            val t = (n + 1).toDouble() / BLOCK
            val rpm = fromRpm + (toRpm - fromRpm) * t
            val load = fromLoad + (toLoad - fromLoad) * t
            sample(n, rpm, load, popChance, fromStarter + (toStarter - fromStarter) * t)
        }
        rpm = toRpm
        load = toLoad
        starter = toStarter

        convolverMid.process(mixedMid, convolvedMid)
        val crackConvolver = convolverCrack ?: error("Not configured")
        if (mixedCrack.any { it != 0.0 }) {
            // Off since the last pop died away: start from silence rather than from back then.
            if (crackRinging == 0) crackConvolver.clear()
            crackRinging = CRACK_RING_BLOCKS
        }
        if (crackRinging > 0) {
            crackConvolver.process(mixedCrack, convolvedCrack)
            crackRinging--
        } else {
            convolvedCrack.fill(0.0)
        }
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
        // The firing note and the hiss don't come through the recording, so its loudness is no business of theirs.
        val bodyGain = OUTPUT_GAIN * BODY * (0.5 + bassDb / MAX_BASS_DB)
        // Jet noise grows steeply with how fast the gas leaves: a whisper at idle, a rasp flat out.
        val flow = sqrt(toLoad * toRpm / CRUISE_FLOW).coerceIn(MIN_HISS, MAX_HISS)
        // Mostly the throttle: a big engine flaring as it catches still sounds deep, blipped it barks.
        val effort = (toLoad * toLoad * sqrt(toRpm / OPEN_RPM)).coerceIn(0.0, 1.0)
        muffleHz += (MUFFLED_HZ * (OPEN_HZ / MUFFLED_HZ).pow(effort) - muffleHz) * MUFFLE_STEP
        muffleMid.open(muffleHz)
        muffleSide.open(muffleHz)
        val raspGain = OUTPUT_GAIN * RASP * note.rasp * flow
        val thumpGain = OUTPUT_GAIN * THUMP
        val crackGain = loudness * CRACK
        // Under the bonnet rather than out of the tailpipe: none of it goes through the exhaust.
        val starterGain = OUTPUT_GAIN * STARTER
        // Lifting off, the engine drops back, as it does with the throttle shut, and leaves room for the pops.
        val fromDuck = duck
        val toDuck = if (state.overrun) OVERRUN_LEVEL else 1.0
        duck = fromDuck + (toDuck - fromDuck) * DUCK_STEP
        var power = 0.0
        for (n in 0 until BLOCK) {
            // The low end stays in the middle: parted, it would thin out where the speakers meet.
            val spread = fromSides + (toSides - fromSides) * (n + 1).toDouble() / BLOCK
            // Pops go round the compressor, so they punch out of the engine rather than turn it down.
            val boomSide = if (spread > 0) spread * mixedThumpSide[n] else 0.0
            val crack = crackGain * convolvedCrack[n]
            val boomLeft = thumpGain * (mixedThumpMid[n] + boomSide) + crack
            val boomRight = thumpGain * (mixedThumpMid[n] - boomSide) + crack
            thumpLeft[n] = if (small) thumpCutLeft.process(boomLeft) else boomLeft
            thumpRight[n] = if (small) thumpCutRight.process(boomRight) else boomRight
            val mid = loudness * muffleMid.process(convolvedMid[n]) + bodyGain * body.process(mixedBody[n]) + raspGain * raspMid.process(mixedRaspMid[n]) +
                starterGain * mixedStarter[n]
            val sideRasp = raspGain * raspSide.process(mixedRaspSide[n])
            val side = if (spread > 0) {
                spread * (loudness * sideCut2.process(sideCut1.process(muffleSide.process(convolvedSide[n] + convolvedWidth[n]))) + sideRasp)
            } else {
                0.0
            }
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
            outLeft[n] = x
            outRight[n] = y
            val heardLeft = listenLeft.process(x)
            val heardRight = listenRight.process(y)
            power += heardLeft * heardLeft + heardRight * heardRight
        }
        val fromSqueeze = squeeze
        val toSqueeze = compressor.gain(power / (CHANNELS * BLOCK))
        val toGain = volume.coerceIn(0.0, MAX_GAIN)
        var loudest = 0.0
        for (n in 0 until BLOCK) {
            val t = (n + 1).toDouble() / BLOCK
            val g = fromGain + (toGain - fromGain) * t
            val c = (fromSqueeze + (toSqueeze - fromSqueeze) * t) * (fromDuck + (duck - fromDuck) * t)
            val x = SoftClip.process((outLeft[n] * c + thumpLeft[n]) * g)
            val y = SoftClip.process((outRight[n] * c + thumpRight[n]) * g)
            loudest = max(loudest, max(abs(x), abs(y)))
            out[CHANNELS * n] = x.toFloat()
            out[CHANNELS * n + 1] = y.toFloat()
        }
        gain = toGain
        squeeze = toSqueeze
        peak = loudest
    }

    /** Sample [n] of the exhausts, panned, before the impulse response, and of the [starter] motor. */
    private fun sample(n: Int, engineRpm: Double, load: Double, popChance: Double, starter: Double) {
        exhaustInput.fill(0.0)
        var crack = 0.0
        val roughness = grit.process(2 * noise.next() - 1)
        // Turned over by the starter, the engine slows into each compression and is flung out of it.
        val compression = if (starter > 0) cos(2 * PI * (crank - firstFires) * layout.cylinders.size / CYCLE) else 0.0
        val rpm = engineRpm * (1 - crankWobble * starter * compression)
        mixedStarter[n] = starterSample(rpm, starter, compression)
        // Nothing burns yet: the cylinders only breathe out what they took in.
        val fired = 1 - UNFIRED * starter
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
                    kicks[voice.exhaust] += voice.popAmplitude * voice.weight
                }
                if (voice.popLeft > 0) {
                    crack += voice.weight * voice.popAmplitude * (1 - voice.popRise) * voice.popDecay * (1 + GRIT * roughness)
                    voice.popRise *= popRiseStep
                    voice.popDecay *= popDecayStep
                    voice.popLeft--
                }
                exhaustInput[voice.exhaust] += pressure * voice.weight * spinUp * fired
            }
        }
        var l = 0.0
        var r = 0.0
        var lowEnd = 0.0
        var hissLeft = 0.0
        var hissRight = 0.0
        var boomLeft = 0.0
        var boomRight = 0.0
        for (i in exhausts.indices) {
            val exhaust = exhausts[i]
            val pipe = exhaust.process(exhaustInput[i], layout.jitter, note.noise, note.highFrequencyGain)
            l += pipe * exhaustLeft[i]
            r += pipe * exhaustRight[i]
            lowEnd += exhaust.pressure
            // The gas leaving the tailpipe hisses as hard as it's pushed.
            val hiss = (2 * noise.next() - 1) * hissEnvelopes[i].process(exhaustInput[i])
            hissLeft += hiss * exhaustLeft[i]
            hissRight += hiss * exhaustRight[i]
            val kick = kicks[i]
            kicks[i] = 0.0
            val boom = thumps[i].process(kick) + BANG * bangs[i].process(kick)
            boomLeft += boom * exhaustLeft[i]
            boomRight += boom * exhaustRight[i]
        }
        mixedMid[n] = (l + r) / 2
        mixedSide[n] = (l - r) / 2
        mixedBody[n] = lowEnd
        mixedRaspMid[n] = (hissLeft + hissRight) / 2
        mixedRaspSide[n] = (hissLeft - hissRight) / 2
        mixedCrack[n] = crackEdge.process(crack)
        mixedThumpMid[n] = (boomLeft + boomRight) / 2
        mixedThumpSide[n] = (boomLeft - boomRight) / 2
    }

    /**
     * The starter motor turning the engine at [rpm], [level] engaged, straining as much as
     * [compression] says: a grind of tooth strikes, each a little different, ringing the housing.
     */
    private fun starterSample(rpm: Double, level: Double, compression: Double): Double {
        val kick = engaged
        engaged = 0.0
        val thrown = CLUNK * clunk.process(kick) + CLACK * clack.process(kick)
        // Pushing a piston up against its compression, the motor draws more current and grinds harder.
        val drive = level * (1 + STRAIN * compression)
        var tooth = 0.0
        if (level > 0) {
            val teeth = rpm / 60 * RING_TEETH / sampleRate
            meshTurns += teeth
            if (meshTurns >= 1) {
                meshTurns -= 1
                tooth = drive * (1 + TOOTH_SPREAD * (2 * noise.next() - 1))
            }
            motorTurns = turn(motorTurns + teeth * MOTOR_PER_TOOTH)
        }
        // The housing keeps ringing after the starter lets go, so it always runs.
        val grind = MESH * mesh.process(tooth) + housingLow.process(tooth) + HOUSING_HIGH * housingHigh.process(tooth) +
            STRIKE * strike.process(tooth)
        if (level <= 0) return thrown + GRIND * grind
        val armature = 2 * PI * motorTurns
        val hum = sin(armature) + sin(3 * armature) / 3
        val brushes = brushesHigh.process(brushesLow.process(2 * noise.next() - 1))
        return thrown + GRIND * grind + drive * (HUM * hum + BRUSHES * brushes)
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

    /** The note an exhaust rings at when a pop goes off in it: its half-wave resonance, kept where speakers play. */
    private fun pipeNote(layout: EngineLayout, exhaust: Int): Double {
        val primaries = layout.cylinders.filter { it.exhaust == exhaust }.map { it.primaryMetres }
        val metres = layout.exhaustMetres[exhaust] + (primaries.average().takeIf { !it.isNaN() } ?: 0.0)
        return (SPEED_OF_SOUND / (2 * metres)).coerceIn(LOWEST_THUMP, HIGHEST_THUMP)
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

    /**
     * An exhaust recording made ready to play one engine through: building it takes longer than
     * the audio buffer lasts, so [prepare] it away from the audio thread.
     */
    class Recording internal constructor(
        val layout: EngineLayout,
        val note: ExhaustNote,
        internal val mid: Convolver,
        internal val side: Convolver?,
        internal val width: Convolver,
        internal val crack: Convolver,
    )

    /** One side's filters after the convolution, for the car's speakers or a phone's. */
    private class Channel(private val sampleRate: Double) {
        private val shelf = Biquad.lowShelf(BASS_CORNER, 0.0, sampleRate)
        private val rumble1 = Biquad.highPass(RUMBLE_CORNER, sampleRate)
        private val rumble2 = Biquad.highPass(RUMBLE_CORNER, sampleRate)
        private val speaker1 = Biquad.highPass(SMALL_SPEAKER_CORNER, sampleRate)
        private val speaker2 = Biquad.highPass(SMALL_SPEAKER_CORNER, sampleRate)

        fun bass(db: Double) {
            shelf.lowShelf(BASS_CORNER, db, sampleRate)
        }

        fun full(x: Double) = rumble2.process(rumble1.process(shelf.process(x)))

        /** Without what a phone's speaker can't play, so it doesn't use up the headroom. */
        fun small(x: Double) = speaker2.process(speaker1.process(x))
    }

    /** The pops' pressure as engine-sim would hear it: low-passed and mostly its edges, without the roughness. */
    private class CrackEdge(private val sampleRate: Double) {
        private val antialias = ButterworthLowPass(ANTIALIAS_HZ, sampleRate)
        private var previous = 0.0

        fun process(x: Double): Double {
            val sample = antialias.process(x)
            val slope = (sample - previous) * sampleRate
            previous = sample
            return slope * CRACK_EDGE + sample * (1 - CRACK_EDGE)
        }
    }

    /** Two low-passes in a row, steep enough that what's above the corner falls well away; moved a block at a time. */
    private class Muffler(private val sampleRate: Double) {
        private val first = Biquad.lowPass(MUFFLED_HZ, sampleRate)
        private val second = Biquad.lowPass(MUFFLED_HZ, sampleRate)

        fun open(hz: Double) {
            first.lowPass(hz, sampleRate)
            second.lowPass(hz, sampleRate)
        }

        fun process(x: Double) = second.process(first.process(x))
    }

    /** The tailpipe's hiss: noise as loud as the pulses pushing it, in the band where it's heard. */
    private class Rasp(sampleRate: Double) {
        private val highPass = Biquad.highPass(RASP_FROM, sampleRate)
        private val lowPass = Biquad.lowPass(RASP_TO, sampleRate)

        fun process(x: Double) = lowPass.process(highPass.process(x))
    }

    /** One exhaust's share of engine-sim's Synthesizer::renderAudio, before the convolution. */
    private class Exhaust(private val sampleRate: Double, private val noise: Noise, antialiasHz: Double) {
        private val antialias = ButterworthLowPass(antialiasHz, sampleRate)
        private val jitter = JitterFilter(10, 10_000.0, sampleRate, noise)
        private val dc = OnePoleLowPass(10.0, sampleRate)
        private val air = ButterworthLowPass(2000.0, sampleRate)
        private var previous = 0.0

        /** The pulses' pressure as of the last [process], smooth and without its average: the firing note. */
        var pressure = 0.0
            private set

        fun process(x: Double, jitterScale: Double, airNoise: Double, highFrequencyGain: Double): Double {
            val sample = jitter.process(antialias.process(x), jitterScale)
            val f = sample - dc.process(sample)
            pressure = f
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
        /** A pop's bang: a few milliseconds, not the click of a millisecond a speaker glitch makes. */
        private const val POP_RISE = 0.0004
        private const val POP_DECAY = 0.004
        private const val POP_SECONDS = 0.03

        /** How loud a pop's thump is, and how long it rings. */
        private const val THUMP = 1.8
        private const val THUMP_SECONDS = 0.06
        private const val LOWEST_THUMP = 45.0
        private const val HIGHEST_THUMP = 110.0

        /**
         * How loud a pop's crack is through the exhaust, and how much of it is its edge. With
         * [THUMP], loud pops just reach the soft clip at the default volume rather than flatten on it.
         */
        private const val CRACK = 0.45
        private const val CRACK_EDGE = 0.002

        /** How rough the burning gas makes a pop, and up to where: above, it would only fizz. */
        private const val GRIT = 1.5
        private const val GRIT_HZ = 2000.0

        /** Blocks the crack's echoes last after a pop: the exhaust recording's length and a little. */
        private const val CRACK_RING_BLOCKS = ImpulseResponse.MAX_SAMPLES / BLOCK + 2

        /** How far the engine drops back with the throttle shut at revs, and how quickly, per block. */
        private const val OVERRUN_LEVEL = 0.5
        private const val DUCK_STEP = 0.05

        /** The bang a few times above the thump, shorter, for speakers too small for the thump. */
        private const val BANG = 0.6
        private const val BANG_NOTE = 4.0
        private const val BANG_SECONDS = 0.025

        /**
         * Brings the convolved signal up to play as loud as music: in a car, next to a song at
         * the same volume, the engine was 2 LU quieter. The rev preview now plays near -8 LUFS
         * at the default volume and -6 at full, where about a sixth of it goes through the soft
         * clip's knee.
         */
        private const val OUTPUT_GAIN = 0.085
        private const val COMPRESS_ABOVE_DB = -19.0
        private const val COMPRESS_RATIO = 2.0

        /** Where Bass lifts the low end, below the boom of the exhaust's resonances. */
        private const val BASS_CORNER = 100.0

        /** Below what car speakers play, and where a boost would only eat headroom or muddy the note. */
        private const val RUMBLE_CORNER = 45.0

        /**
         * The muffler's corner idling, and wide open at [OPEN_RPM] and above; between, it opens
         * with the load squared and the square root of the revs. A V8 starting and revving,
         * recorded, has its idle and the flare as it catches almost all below 180 Hz, the rest
         * 20-30 dB down, and comes up 6-10 dB above 180 Hz when blipped.
         */
        private const val MUFFLED_HZ = 200.0
        private const val OPEN_HZ = 4000.0
        private const val OPEN_RPM = 4000.0

        /** Per block: opens and closes over about a tenth of a second. */
        private const val MUFFLE_STEP = 0.06

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

        /**
         * engine-sim low-passes each exhaust at 1.9 kHz, which left the engine 20 dB short of
         * music above 2.5 kHz in a car; this keeps the pulses' overtones up to where the ear is
         * keenest.
         */
        private const val ANTIALIAS_HZ = 6000.0

        /**
         * How loud the firing note plays underneath, below [BODY_HZ]: the derivative's edge thins
         * it out, which left the engine 8-10 dB short of music below 80 Hz. Bass turns it from
         * half this to one and a half times.
         */
        private const val BODY = 50.0
        private const val BODY_HZ = 150.0

        /** How loud the tailpipe's hiss plays cruising, between [RASP_FROM] and [RASP_TO]. */
        private const val RASP = 50.0

        /** Load times rpm cruising, where the hiss is [RASP]; it follows the square root, within these. */
        private const val CRUISE_FLOW = 600.0
        private const val MIN_HISS = 0.1

        /** How quickly the hiss follows each pulse. */
        private const val HISS_ENVELOPE_HZ = 300.0
        private const val MAX_HISS = 2.0
        private const val RASP_FROM = 1000.0
        private const val RASP_TO = 5000.0

        /**
         * How loud the starter plays, its parts, and how much it slows and strains into each
         * compression of a four-cylinder. Its pinion strikes a flywheel of [RING_TEETH], each
         * strike ringing the housing at [HOUSING_LOW_HZ] and [HOUSING_HIGH_HZ], and its armature
         * turns at [MOTOR_PER_TOOTH] of the strikes. A V8 recorded starting ground mostly between
         * 0.7 and 5.6 kHz, peaking near 1 and 2.4 kHz, 10-14 dB(A) over its idle and pulsing with
         * every compression.
         */
        private const val STARTER = 1.3
        private const val GRIND = 1.0
        private const val MESH = 0.6
        private const val MESH_HZ = 600.0
        private const val MESH_SECONDS = 0.003
        private const val HOUSING_LOW_HZ = 1000.0
        private const val HOUSING_LOW_SECONDS = 0.006
        private const val HOUSING_HIGH_HZ = 2400.0
        private const val HOUSING_HIGH_SECONDS = 0.004
        private const val HOUSING_HIGH = 1.2
        private const val STRIKE = 0.8
        private const val STRIKE_FROM = 3000.0
        private const val TOOTH_SPREAD = 0.4
        private const val HUM = 0.1
        private const val BRUSHES = 2.0
        private const val STRAIN = 0.9
        private const val CRANK_WOBBLE = 0.4

        /** How much quieter the pulses are turned over by the starter, before anything fires. */
        private const val UNFIRED = 0.7
        private const val RING_TEETH = 130.0
        private const val MOTOR_PER_TOOTH = 0.4
        private const val BRUSHES_FROM = 1000.0
        private const val BRUSHES_TO = 8000.0

        /** How hard the first firings bang through the pipes as the engine catches, as a pop does. */
        private const val CATCH_BANG = 2.5

        /** The solenoid throwing the pinion in: a thud, and the metal's clack on top. */
        private const val CLUNK = 1.5
        private const val CLUNK_HZ = 95.0
        private const val CLUNK_SECONDS = 0.06
        private const val CLACK = 0.6
        private const val CLACK_HZ = 2300.0
        private const val CLACK_SECONDS = 0.012

        /** The compressor listens above this, so the low end doesn't turn the rest down. */
        private const val SIDECHAIN_HZ = 120.0

        /** Readies [note]'s [impulse], as [ImpulseResponse.read] gives it, to play [layout] through. */
        fun prepare(layout: EngineLayout, note: ExhaustNote, impulse: FloatArray): Recording {
            // No two pipes are quite the same length: heard a little longer on the left and
            // shorter on the right, the echoes part ways while the pulses stay together. Only the
            // difference goes to the sides, so both together sound just as before.
            val longer = ImpulseResponse.stretched(impulse, 1 + PIPE_DIFFERENCE)
            val shorter = ImpulseResponse.stretched(impulse, 1 - PIPE_DIFFERENCE)
            return Recording(
                layout,
                note,
                mid = Convolver(impulse, BLOCK),
                side = if (layout.exhaustMetres.size > 1) Convolver(impulse, BLOCK) else null,
                width = Convolver(FloatArray(longer.size) { (WIDTH * (longer[it] - shorter.getOrElse(it) { 0f }) / 2).toFloat() }, BLOCK),
                crack = Convolver(impulse, BLOCK),
            )
        }

        /** A fraction of a turn moved on by less than one, kept under one. */
        private fun turn(turns: Double) = if (turns >= 1) turns - 1 else turns

        /** Into 0 until 720°; no floor(), which is a slow native call in debug builds. */
        private fun wrap(degrees: Double): Double {
            var d = degrees
            while (d < 0) d += CYCLE
            while (d >= CYCLE) d -= CYCLE
            return d
        }
    }
}
