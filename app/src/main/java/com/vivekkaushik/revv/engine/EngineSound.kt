package com.vivekkaushik.revv.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.max

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
) {
    /**
     * The volume as a gain, on a curve so each step sounds about as big as the last. The top
     * third goes past 1, up to [EngineSynth.MAX_GAIN], driving the peaks into the soft clip:
     * louder, and a little rougher, to hold its own against music.
     */
    val gain: Double get() = (volume.coerceIn(0, MAX_VOLUME).toDouble() / MAX_VOLUME).let { it * it * EngineSynth.MAX_GAIN }

    /** The bass as decibels added to the low end, up to [MAX_BASS_DB]. */
    val bassDb: Double get() = bass.coerceIn(0, MAX_BASS) * MAX_BASS_DB / MAX_BASS

    companion object {
        const val MAX_VOLUME = 30
        const val DEFAULT_VOLUME = 24
        const val MAX_BASS = 30
        const val DEFAULT_BASS = 18
        private const val MAX_BASS_DB = 15.0
    }
}

/**
 * Plays [EngineSynth] through the speakers on the media stream while the engine runs. Its own
 * thread renders and writes a block at a time, and pauses when there's nothing to hear. It takes
 * no audio focus, so music keeps playing over it.
 */
class EngineSound(private val context: Context) {

    private val follower = EngineFollower()
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
        val synth = EngineSynth()
        val impulses = HashMap<ExhaustNote, FloatArray>()
        val block = FloatArray(EngineSynth.BLOCK)
        var applied: EngineSoundSettings? = null
        var level = 0.0
        var quietSince = SystemClock.elapsedRealtimeNanos()
        track.play()
        while (!released) {
            val settings = settings
            if (applied == null || settings.layout != applied.layout || settings.note != applied.note || settings.crackle != applied.crackle || settings.bass != applied.bass) {
                val impulse = impulses.getOrPut(settings.note) { readImpulse(settings.note) }
                synth.configure(settings.layout, settings.note, impulse, settings.crackle, settings.bassDb)
                applied = settings
            }
            val now = SystemClock.elapsedRealtimeNanos()
            // Kept moving even while previewing, so it doesn't jump when the preview ends.
            val live = follower.advance(now)
            val state = if (previewing(now)) PreviewRev.at(now - previewFrom) else if (settings.enabled) live else EngineState.Off
            val volume = if (muted || !state.running) 0.0 else settings.gain
            level += (volume - level) * FADE_STEP
            if (volume == 0.0 && level < SILENT) level = 0.0
            synth.render(block, state, level)
            val written = track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
            check(written >= 0) { "AudioTrack write failed: $written" }

            if (level > 0 || synth.peak > SILENT) quietSince = now
            if (now - quietSince > QUIET_NANOS) {
                if (!wanted()) return
                // Nothing to play: stop writing silence until the engine starts or a preview does.
                track.pause()
                track.flush()
                while (!released && !audible()) {
                    if (!wanted()) return
                    Thread.sleep(IDLE_POLL_MILLIS)
                }
                if (released) return
                track.play()
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

    private fun readImpulse(note: ExhaustNote): FloatArray =
        context.assets.open(note.impulse).use { ImpulseResponse.read(it.readBytes()) }

    private fun openTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(EngineSynth.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(EngineSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum, EngineSynth.BLOCK * Float.SIZE_BYTES * BUFFER_BLOCKS))
            .build()
    }

    private companion object {
        const val TAG = "EngineSound"
        const val SILENT = 1e-4
        const val QUIET_NANOS = 2_000_000_000L
        const val IDLE_POLL_MILLIS = 100L
        const val RETRY_MILLIS = 1_000L

        /** Per block: fades in and out over about 150 ms rather than clicking. */
        const val FADE_STEP = 0.04

        /** About 46 ms queued: enough to ride out a garbage collection, short enough to follow the revs. */
        const val BUFFER_BLOCKS = 8
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
}
