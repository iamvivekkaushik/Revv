package com.vivekkaushik.revv.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

/** Settings › Sound › Engine sound. */
data class EngineSoundSettings(
    val enabled: Boolean = false,
    val layout: EngineLayout = EngineLayout.DEFAULT,
    val note: ExhaustNote = ExhaustNote.DEFAULT,
    /** 0 to [MAX_VOLUME]. */
    val volume: Int = DEFAULT_VOLUME,
    val crackle: Boolean = true,
    /** 0 to [MAX_BASS]. */
    val bass: Int = DEFAULT_BASS,
    val surround: Boolean = true,
    /** 0 to [MAX_SMOOTHING]. */
    val smoothing: Int = DEFAULT_SMOOTHING,
) {
    /**
     * The volume as a gain, on a curve so each step sounds about as big as the last. The top
     * third goes past 1, up to [EngineSynth.MAX_GAIN], driving the peaks into the soft clip:
     * louder, and a little rougher, to hold its own against music.
     */
    val gain: Double get() = (volume.coerceIn(0, MAX_VOLUME).toDouble() / MAX_VOLUME).let { it * it * EngineSynth.MAX_GAIN }

    /** Rev smoothing as [EngineFollower.smoothing] takes it, 0 to 1. */
    val smoothingFraction: Double get() = smoothing.coerceIn(0, MAX_SMOOTHING).toDouble() / MAX_SMOOTHING

    /** The bass as decibels added to the low end, up to [EngineSynth.MAX_BASS_DB]. */
    val bassDb: Double get() = bass.coerceIn(0, MAX_BASS) * EngineSynth.MAX_BASS_DB / MAX_BASS

    companion object {
        const val MAX_VOLUME = 30
        const val DEFAULT_VOLUME = 24
        const val MAX_BASS = 30
        const val DEFAULT_BASS = 18
        const val MAX_SMOOTHING = 30
        const val DEFAULT_SMOOTHING = 15
    }
}

/**
 * Plays [EngineSynth] through the speakers on the media stream while the engine runs. Its own
 * thread renders and writes a block at a time, and pauses when there's nothing to hear. It takes
 * no audio focus, so music keeps playing over it.
 */
class EngineSound(private val context: Context) {

    private val follower = EngineFollower()

    /** Each exhaust's recording, read once, from whichever thread gets there first. */
    private val impulses = ConcurrentHashMap<ExhaustNote, FloatArray>()
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Phones have one; a head unit's built-in speakers are the car's. */
    private val hasEarpiece by lazy {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
    private val lock = Any()
    private var thread: Thread? = null

    @Volatile
    private var settings = EngineSoundSettings()

    @Volatile
    private var previewFrom = 0L

    @Volatile
    private var released = false

    /** Silent while true, e.g. during a phone call. */
    @Volatile
    var muted = false

    fun apply(settings: EngineSoundSettings) {
        this.settings = settings
        wake()
    }

    /** The engine as the car or the demo drive last reported it. */
    fun report(reading: EngineReading) {
        follower.report(reading)
        wake()
    }

    /** Revs the chosen engine for a few seconds, so it can be heard parked. */
    fun preview() {
        previewFrom = SystemClock.elapsedRealtimeNanos()
        wake()
    }

    fun release() {
        released = true
        synchronized(lock) { thread?.interrupt() }
    }

    private fun previewing(now: Long) = previewFrom != 0L && now - previewFrom < PreviewRev.NANOS

    private fun wanted() = !released && (settings.enabled || previewing(SystemClock.elapsedRealtimeNanos()))

    private fun wake() = synchronized(lock) {
        if (thread == null && wanted()) thread = Thread(::play, "EngineSound").also { it.start() }
    }

    private fun play() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        try {
            val track = openTrack()
            try {
                render(track)
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        } catch (e: InterruptedException) {
            // Released.
        } catch (e: Exception) {
            Log.w(TAG, "Engine sound stopped", e)
            // Try again shortly, e.g. after the audio server restarted, rather than in a tight loop.
            runCatching { Thread.sleep(RETRY_MILLIS) }
        } finally {
            synchronized(lock) {
                thread = null
                // Switched back on while this thread was on its way out.
                if (wanted()) thread = Thread(::play, "EngineSound").also { it.start() }
            }
        }
    }

    private fun render(track: AudioTrack) {
        val preparer = Executors.newSingleThreadExecutor { Thread(it, "EngineSoundPrepare").apply { isDaemon = true } }
        try {
            render(track, preparer)
        } finally {
            preparer.shutdownNow()
        }
    }

    private fun render(track: AudioTrack, preparer: ExecutorService) {
        val synth = EngineSynth()
        val block = FloatArray(EngineSynth.BLOCK * EngineSynth.CHANNELS)
        var applied: EngineSoundSettings? = null
        var blocks = 0
        var level = 0.0
        var quietSince = SystemClock.elapsedRealtimeNanos()
        val output = Output(track)
        // The preview is read like a car, a few times a second, so rev smoothing can be heard parked.
        var previewFollower = EngineFollower()
        var previewStarted = 0L
        var previewReadAt = 0L
        var recording = prepare(settings.layout, settings.note)
        var preparing: Future<EngineSynth.Recording>? = null

        /**
         * Another engine or exhaust is readied away from this thread, which carries on playing the
         * last one meanwhile: building it takes longer than the buffer lasts, and would drop out.
         */
        fun configure(settings: EngineSoundSettings) {
            if (settings.layout != recording.layout || settings.note != recording.note) {
                val pending = preparing
                if (pending == null) {
                    preparing = preparer.submit<EngineSynth.Recording> { prepare(settings.layout, settings.note) }
                    return
                }
                if (!pending.isDone) return
                preparing = null
                val ready = pending.get()
                // Changed again while it was being readied: ready that one next time round.
                if (ready.layout != settings.layout || ready.note != settings.note) return
                recording = ready
                applied = null
            }
            val last = applied
            if (last == null || settings.crackle != last.crackle || settings.bass != last.bass) {
                synth.configure(recording, settings.crackle, settings.bassDb)
                applied = settings
            }
        }
        // Ready before the track starts, so it doesn't start by running dry.
        configure(settings)
        output.start()
        while (!released) {
            val settings = settings
            configure(settings)
            synth.surround = settings.surround
            follower.smoothing = settings.smoothingFraction
            // Headphones, Bluetooth and the car come and go: check every half second or so.
            if (blocks++ % ROUTE_CHECK_BLOCKS == 0) synth.smallSpeaker = playsOnPhoneSpeaker(track)
            val now = SystemClock.elapsedRealtimeNanos()
            // Kept moving even while previewing, so it doesn't jump when the preview ends. Rendered
            // for when it will be heard, which over Bluetooth is a fifth of a second away.
            val live = follower.advance(now + output.latencyNanos)
            val state = if (previewing(now)) {
                if (previewFrom != previewStarted) {
                    previewStarted = previewFrom
                    previewFollower = EngineFollower()
                    previewReadAt = 0L
                }
                if (now - previewReadAt >= PREVIEW_READ_NANOS) {
                    previewReadAt = now
                    previewFollower.report(PreviewRev.reading(now - previewStarted, now))
                }
                previewFollower.smoothing = settings.smoothingFraction
                previewFollower.advance(now + output.latencyNanos)
            } else if (settings.enabled) {
                live
            } else {
                EngineState.Off
            }
            val volume = if (muted || !state.running) 0.0 else settings.gain
            level += (volume - level) * FADE_STEP
            if (volume == 0.0 && level < SILENT) level = 0.0
            synth.render(block, state, level)
            val written = track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
            check(written >= 0) { "AudioTrack write failed: $written" }
            output.wrote(written / EngineSynth.CHANNELS)

            if (level > 0 || synth.peak > SILENT) quietSince = now
            if (now - quietSince > QUIET_NANOS) {
                if (!wanted()) return
                // Nothing to play: stop writing silence until the engine starts or a preview does.
                track.pause()
                track.flush()
                output.flushed()
                while (!released && !audible()) {
                    if (!wanted()) return
                    Thread.sleep(IDLE_POLL_MILLIS)
                }
                if (released) return
                output.start()
                quietSince = SystemClock.elapsedRealtimeNanos()
            }
        }
    }

    private fun audible(): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        if (muted) return false
        if (previewing(now)) return true
        return settings.enabled && follower.advance(now).running
    }

    private fun playsOnPhoneSpeaker(track: AudioTrack): Boolean {
        val type = track.routedDevice?.type ?: return false
        return hasEarpiece && (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
    }

    private fun prepare(layout: EngineLayout, note: ExhaustNote): EngineSynth.Recording =
        EngineSynth.prepare(layout, note, impulses.computeIfAbsent(note, ::readImpulse))

    private fun readImpulse(note: ExhaustNote): FloatArray =
        context.assets.open(note.impulse).use { ImpulseResponse.read(it.readBytes()) }

    private fun openTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(EngineSynth.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(EngineSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(minimum, EngineSynth.BLOCK * EngineSynth.CHANNELS * Float.SIZE_BYTES * MAX_BUFFER_BLOCKS))
            .build()
    }

    /**
     * Keeps as little queued as the device manages without running dry, starting small and
     * growing a block each time it does, and measures how long a sample takes from being written
     * to being heard, Bluetooth's delay included where Android knows it.
     */
    private class Output(private val track: AudioTrack) {
        private val timestamp = AudioTimestamp()
        private val silence = FloatArray(EngineSynth.BLOCK * EngineSynth.CHANNELS)
        private var written = 0L
        private var underruns = track.underrunCount
        private var writes = 0
        private var startingFor = 0

        /** From writing a sample to hearing it. */
        var latencyNanos = 0L
            private set

        init {
            track.setBufferSizeInFrames(EngineSynth.BLOCK * START_BUFFER_BLOCKS)
        }

        /** Starts playing with a little silence queued, so it doesn't begin by running dry. */
        fun start() {
            repeat(PRIME_BLOCKS) {
                val frames = track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING) / EngineSynth.CHANNELS
                if (frames > 0) written += frames
            }
            track.play()
            startingFor = START_WRITES
        }

        fun wrote(frames: Int) {
            written += frames
            val now = track.underrunCount
            // The mixer may miss a beat while the track starts; only running dry later says the buffer is short.
            if (startingFor > 0) {
                startingFor--
                underruns = now
            } else if (now > underruns) {
                underruns = now
                val size = min(track.bufferCapacityInFrames, track.bufferSizeInFrames + EngineSynth.BLOCK)
                Log.i(TAG, "Ran dry; now queueing ${track.setBufferSizeInFrames(size)} frames")
            }
            if (writes++ % LATENCY_EVERY != 0 || !track.getTimestamp(timestamp)) return
            val queued = (written - timestamp.framePosition) * 1_000_000_000L / EngineSynth.SAMPLE_RATE
            val nanos = queued - (System.nanoTime() - timestamp.nanoTime)
            if (nanos in 0..MAX_LATENCY_NANOS) latencyNanos += (nanos - latencyNanos) / 4
        }

        /** Flushing starts the count of frames played from nothing again. */
        fun flushed() {
            written = 0
        }
    }

    private companion object {
        const val TAG = "EngineSound"
        const val SILENT = 1e-4
        const val QUIET_NANOS = 2_000_000_000L
        const val IDLE_POLL_MILLIS = 100L
        const val RETRY_MILLIS = 1_000L
        const val ROUTE_CHECK_BLOCKS = 80

        /** Per block: fades in and out over about 150 ms rather than clicking. */
        const val FADE_STEP = 0.04

        /** About 23 ms queued to start with, growing towards 46 if the device can't keep up. */
        const val START_BUFFER_BLOCKS = 4
        const val MAX_BUFFER_BLOCKS = 8

        /** How often the preview is read, as a typical adapter reads the car. */
        const val PREVIEW_READ_NANOS = 250_000_000L

        /** Silent blocks queued before starting, and writes after it before running dry counts. */
        const val PRIME_BLOCKS = 2
        const val START_WRITES = 20

        /** Writes between measuring the output's delay, about a tenth of a second. */
        const val LATENCY_EVERY = 16

        /** Longer than this, the measurement is off rather than the delay real. */
        const val MAX_LATENCY_NANOS = 500_000_000L
    }
}

/** A few seconds of revving, parked: idle, a blip, a bigger one, back to idle. */
internal object PreviewRev {
    /** Seconds, rpm and load: rising revs are throttle open, falling ones shut. */
    private val keys = arrayOf(
        doubleArrayOf(0.0, 850.0, 0.1),
        doubleArrayOf(0.9, 850.0, 0.1),
        doubleArrayOf(1.25, 4200.0, 1.0),
        doubleArrayOf(1.35, 4300.0, 1.0),
        doubleArrayOf(2.2, 1000.0, 0.0),
        doubleArrayOf(2.6, 850.0, 0.1),
        doubleArrayOf(3.2, 850.0, 0.1),
        doubleArrayOf(3.75, 6300.0, 1.0),
        doubleArrayOf(4.0, 6400.0, 1.0),
        doubleArrayOf(5.1, 1100.0, 0.0),
        doubleArrayOf(5.5, 850.0, 0.1),
        doubleArrayOf(6.5, 850.0, 0.1),
    )

    val NANOS = (keys.last()[0] * 1e9).toLong()

    fun at(elapsedNanos: Long): EngineState {
        val seconds = elapsedNanos / 1e9
        val next = keys.indexOfFirst { it[0] > seconds }
        if (next <= 0) return if (next == 0) state(keys[0]) else EngineState.Off
        val from = keys[next - 1]
        val to = keys[next]
        val t = ((seconds - from[0]) / (to[0] - from[0])).let { it * it * (3 - 2 * it) }
        val rpm = from[1] + (to[1] - from[1]) * t
        // The throttle snaps open or shut at the start of each stretch.
        return EngineState(rpm, to[2], overrun = to[1] < from[1] && to[2] == 0.0, running = true)
    }

    private fun state(key: DoubleArray) = EngineState(key[1], key[2], overrun = false, running = true)

    /**
     * The preview as an adapter would read it at [atNanos]: air flow and throttle from the load,
     * the throttle resting where a real one does.
     */
    fun reading(elapsedNanos: Long, atNanos: Long): EngineReading {
        val state = at(elapsedNanos)
        return EngineReading(
            rpm = state.rpm.toInt(),
            speedKmh = 0,
            gear = null,
            airFill = (SHUT_FILL + (1 - SHUT_FILL) * state.load).toFloat(),
            engineLoad = null,
            throttle = (SHUT_THROTTLE + (OPEN_THROTTLE - SHUT_THROTTLE) * state.load).toInt(),
            atNanos = atNanos,
        )
    }

    private const val SHUT_FILL = 0.25
    private const val SHUT_THROTTLE = 14
    private const val OPEN_THROTTLE = 84
}
