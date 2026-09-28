package com.vivekkaushik.revv.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTest {

    private fun track(
        isPlaying: Boolean = true,
        positionMs: Long = 10_000,
        updatedAt: Long = 1_000,
        durationMs: Long = 60_000,
        speed: Float = 1f,
    ) = NowPlaying(
        packageName = "com.example.music",
        appLabel = "Music",
        title = "Night Drive",
        subtitle = "The Rev Limiters",
        art = null,
        isPlaying = isPlaying,
        durationMs = durationMs,
        positionMs = positionMs,
        positionUpdatedAt = updatedAt,
        playbackSpeed = speed,
        canSkipPrevious = true,
        canSkipNext = true,
    )

    @Test
    fun playing_extrapolatesFromTheLastUpdate() = assertEquals(15_000L, track().positionAt(6_000))

    @Test
    fun playing_respectsPlaybackSpeed() = assertEquals(20_000L, track(speed = 2f).positionAt(6_000))

    @Test
    fun paused_staysPut() = assertEquals(10_000L, track(isPlaying = false).positionAt(60_000))

    @Test
    fun position_neverPassesTheDuration() = assertEquals(60_000L, track().positionAt(1_000_000))

    @Test
    fun unknownUpdateTime_isNotExtrapolated() = assertEquals(10_000L, track(updatedAt = 0).positionAt(50_000))

    @Test
    fun formatsTrackTimes() {
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("3:07", formatDuration(187_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}
