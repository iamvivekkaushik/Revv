package com.vivekkaushik.revv.media

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.vivekkaushik.revv.system.applicationLabelOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows the most relevant media session on the device (the one playing, otherwise the most
 * recently active) and exposes it as [nowPlaying]. Needs notification access, see
 * [MediaListenerService]. All methods must be called on the main thread.
 */
class MediaSessionMonitor(private val context: Context) {

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appLabels = mutableMapOf<String, String>()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var controller: MediaController? = null
    private var listening = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        follow(sessions.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = follow(activeSessions())
    }

    fun hasAccess(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** Starts following sessions. Does nothing without notification access. */
    fun start() {
        if (listening || !hasAccess()) return
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, mainHandler)
        } catch (e: SecurityException) {
            return
        }
        listening = true
        follow(activeSessions())
    }

    fun stop() {
        if (!listening) return
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(controllerCallback)
        controller = null
        listening = false
    }

    fun playPause() {
        val controller = controller ?: return
        if (controller.playbackState.isPlaying) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }

    fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    /** Jumps to [positionMs] into the track, if the player takes it. */
    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Opens the player behind the current session. False if there is nothing to open. */
    fun openPlayer(): Boolean {
        val controller = controller ?: return false
        controller.sessionActivity?.let { playerScreen ->
            try {
                playerScreen.send(context, 0, null, null, null, null, allowActivityStart())
                return true
            } catch (e: PendingIntent.CanceledException) {
                // Fall through to the app's launcher entry.
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(controller.packageName) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun activeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun follow(sessions: List<MediaController>) {
        val next = sessions.firstOrNull { it.playbackState.isPlaying } ?: sessions.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = next
            next?.registerCallback(controllerCallback, mainHandler)
        }
        publish()
    }

    private fun publish() {
        _nowPlaying.value = controller?.let(::snapshot)
    }

    private fun snapshot(controller: MediaController): NowPlaying? {
        val fromPhone = controller.packageName in BLUETOOTH_PACKAGES
        val metadata = controller.metadata
        val description = metadata?.description
        val title = description?.title?.toString()?.takeIf { it.isNotBlank() }
            // A phone may not have named its track yet, but play and skip reach it all the same.
            ?: if (fromPhone) PHONE_AUDIO else return null
        val state = controller.playbackState
        val actions = state?.actions ?: 0L
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return NowPlaying(
            packageName = controller.packageName,
            appLabel = appLabel(controller.packageName),
            title = title,
            subtitle = description?.subtitle?.toString().orEmpty(),
            art = description?.iconBitmap,
            isPlaying = state.isPlaying,
            durationMs = durationMs,
            positionMs = state?.position ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L,
            playbackSpeed = state?.playbackSpeed ?: 1f,
            // Players that don't declare their actions usually still handle skips.
            canSkipPrevious = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canSkipNext = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            // Unlike skips, only when declared: a phone's Bluetooth can't seek, nor can live radio.
            canSeek = durationMs > 0 && (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            fromPhone = fromPhone,
        )
    }

    private fun appLabel(packageName: String): String = appLabels.getOrPut(packageName) {
        context.packageManager.applicationLabelOrNull(packageName) ?: packageName
    }

    /** Lets the player's PendingIntent open its screen; Revv is in the foreground when this runs. */
    private fun allowActivityStart(): Bundle? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            .toBundle()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
            .toBundle()
        else -> null
    }

    private companion object {
        /**
         * The Bluetooth stack's packages, AOSP's and Google's. A head unit's Bluetooth plays the
         * phone's audio and publishes the phone's player as a media session under one of them.
         */
        val BLUETOOTH_PACKAGES = setOf("com.android.bluetooth", "com.google.android.bluetooth")

        const val PHONE_AUDIO = "Phone audio"
    }
}

private val PlaybackState?.isPlaying: Boolean
    get() = when (this?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }
