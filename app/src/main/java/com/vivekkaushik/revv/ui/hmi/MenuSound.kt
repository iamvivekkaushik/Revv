package com.vivekkaushik.revv.ui.hmi

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.compositionLocalOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Which Settings › Sound switch governs a sound. */
enum class SoundGroup { Menu, Dialer, Keyboard }

/**
 * The sounds taps make, each made in code so the app carries no audio files. Menu buttons get a
 * two-note blip, the dialer's keys a phone-style dual tone each (the real touch-tone pairs), and
 * the on-screen keyboard a short dry tick.
 */
enum class UiSound(val group: SoundGroup, val frequencies: List<Double>, val millis: Int, val rising: Double? = null) {
    Menu(SoundGroup.Menu, listOf(784.0), 90, rising = 1175.0),
    Key(SoundGroup.Keyboard, listOf(2200.0, 3300.0), 28),
    DialDelete(SoundGroup.Dialer, listOf(520.0), 45),
    Dial1(SoundGroup.Dialer, listOf(697.0, 1209.0), 110),
    Dial2(SoundGroup.Dialer, listOf(697.0, 1336.0), 110),
    Dial3(SoundGroup.Dialer, listOf(697.0, 1477.0), 110),
    Dial4(SoundGroup.Dialer, listOf(770.0, 1209.0), 110),
    Dial5(SoundGroup.Dialer, listOf(770.0, 1336.0), 110),
    Dial6(SoundGroup.Dialer, listOf(770.0, 1477.0), 110),
    Dial7(SoundGroup.Dialer, listOf(852.0, 1209.0), 110),
    Dial8(SoundGroup.Dialer, listOf(852.0, 1336.0), 110),
    Dial9(SoundGroup.Dialer, listOf(852.0, 1477.0), 110),
    DialStar(SoundGroup.Dialer, listOf(941.0, 1209.0), 110),
    Dial0(SoundGroup.Dialer, listOf(941.0, 1336.0), 110),
    DialHash(SoundGroup.Dialer, listOf(941.0, 1477.0), 110),
    ;

    companion object {
        /** The tone for a dialer key: its digit, or * or #; anything else (such as +) falls back to the 0 tone. */
        fun dial(key: String): UiSound = when (key) {
            "1" -> Dial1
            "2" -> Dial2
            "3" -> Dial3
            "4" -> Dial4
            "5" -> Dial5
            "6" -> Dial6
            "7" -> Dial7
            "8" -> Dial8
            "9" -> Dial9
            "*" -> DialStar
            "#" -> DialHash
            else -> Dial0
        }
    }
}

/** What tapping plays; the player is null in previews. Each [SoundGroup] can be switched off in Settings. */
val LocalMenuSound = compositionLocalOf<MenuSound?> { null }

/** Plays [UiSound]s on the system's UI-sound volume. Call [release] when done. */
class MenuSound(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids = mutableMapOf<UiSound, Int>()
    private val ready = mutableSetOf<Int>()

    /** The groups whose sounds are on. */
    @Volatile var enabled: Set<SoundGroup> = SoundGroup.entries.toSet()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready += id } }
        runCatching {
            UiSound.entries.forEach { sound ->
                val file = File(context.cacheDir, "ui_${sound.name.lowercase()}.wav")
                file.writeBytes(wav(sound))
                ids[sound] = pool.load(file.path, 1)
            }
        }
    }

    fun play(sound: UiSound) {
        if (sound.group !in enabled) return
        val id = ids[sound] ?: return
        if (synchronized(ready) { id in ready }) pool.play(id, VOLUME, VOLUME, 1, 0, 1f)
    }

    fun release() = pool.release()

    private companion object {
        const val VOLUME = 0.6f
        const val SAMPLE_RATE = 22_050

        /** The sound as a 16-bit mono WAV file: its tones, with a quick attack and a fade. */
        fun wav(sound: UiSound): ByteArray {
            val samples = SAMPLE_RATE * sound.millis / 1000
            val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            val phases = DoubleArray(sound.frequencies.size)
            for (i in 0 until samples) {
                val t = i.toDouble() / samples
                var value = 0.0
                sound.frequencies.forEachIndexed { index, frequency ->
                    val now = if (sound.rising != null && t >= 0.4) sound.rising else frequency
                    phases[index] += 2 * PI * now / SAMPLE_RATE
                    value += sin(phases[index])
                }
                value /= sound.frequencies.size
                val attack = (i / (SAMPLE_RATE * 0.003)).coerceAtMost(1.0)
                val fade = exp(-4.0 * t)
                pcm.putShort((value * attack * fade * Short.MAX_VALUE * 0.5).toInt().toShort())
            }
            val data = pcm.array()
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + data.size)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(SAMPLE_RATE)
                putInt(SAMPLE_RATE * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(data.size)
            }
            return header.array() + data
        }
    }
}
