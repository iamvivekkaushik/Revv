package com.vivekkaushik.revv.media

import android.graphics.Bitmap

/** Snapshot of the media session Revv is following. Times are in milliseconds. */
data class NowPlaying(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val subtitle: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    /** Zero or less when the player doesn't report one. */
    val durationMs: Long,
    val positionMs: Long,
    /** `SystemClock.elapsedRealtime()` when [positionMs] was measured; zero if unknown. */
    val positionUpdatedAt: Long,
    val playbackSpeed: Float,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    /** The player takes a position to jump to, and has said how long the track is. */
    val canSeek: Boolean = false,
    /** The phone's own player, heard over Bluetooth: the head unit's Bluetooth publishes it as a session. */
    val fromPhone: Boolean = false,
) {
    /** Playback position extrapolated to [nowElapsed], the way the system media controls do it. */
    fun positionAt(nowElapsed: Long): Long {
        val base = positionMs.coerceAtLeast(0)
        if (!isPlaying || positionUpdatedAt <= 0) return base
        val position = base + ((nowElapsed - positionUpdatedAt) * playbackSpeed).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

/** Formats a track time as `m:ss`, or `h:mm:ss` past an hour. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
