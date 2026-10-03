package com.vivekkaushik.revv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Revv CarPlay, the separate (GPL) companion app that runs the CarPlay stack. Revv binds to its
 * embed service, hands it a SurfaceView's host token, and gets a SurfacePackage back: CarPlay then
 * draws and takes touch inside Revv's own layout while the session itself lives in the companion.
 * Main thread.
 */
class CarPlayCompanion(context: Context) {
    sealed interface State {
        /** The companion is not installed, or Android is older than 11. */
        data object Unavailable : State
        /** Bound, or binding; no word from the companion yet. */
        data object Connecting : State
        /** The companion's own report. [phase] is one of the PHASE_* values. */
        data class Session(
            val phase: String,
            val detail: String,
            val wireless: Boolean,
            /** [HOTSPOT_P2P] (Wi-Fi Direct) or [HOTSPOT_MANUAL] (the car's hotspot). */
            val hotspotMode: String,
            val videoActive: Boolean,
            /** What the driver must do in the companion first (SETUP_*), when [phase] is [PHASE_SETUP_REQUIRED]. */
            val missing: List<String>,
        ) : State
        /** The companion refused the view (ERROR_*). */
        data class Refused(val error: String) : State
    }

    private class Attachment(val hostToken: IBinder, val displayId: Int, var width: Int, var height: Int, val screenWidth: Int, val screenHeight: Int)

    private val app = context.applicationContext
    private val _state = MutableStateFlow<State>(if (available(app)) State.Connecting else State.Unavailable)
    val state: StateFlow<State> = _state

    /** Set by the view that shows CarPlay; called with the package to put into its SurfaceView. */
    var onSurfacePackage: ((SurfaceControlViewHost.SurfacePackage) -> Unit)? = null

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message -> onReply(message); true })
    private var service: Messenger? = null
    private var bound = false
    private var attachment: Attachment? = null
    private var attached = false
    private var hostFocused = true

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            attachment?.let { sendAttach(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            attached = false
            if (_state.value !is State.Unavailable) _state.value = State.Connecting
        }

        // The companion was stopped or updated: Android will not bring it back for this binding.
        override fun onBindingDied(name: ComponentName) {
            service = null
            attached = false
            if (bound) runCatching { app.unbindService(this) }
            bound = false
            _state.value = State.Connecting
            bind()
        }
    }

    fun bind() {
        if (bound) return
        val intent = serviceIntent(app) ?: run { _state.value = State.Unavailable; return }
        // BIND_INCLUDE_CAPABILITIES lends Revv's foreground capabilities (location above all) to the
        // companion while Revv is on screen. The companion has no visible window of its own here, and
        // without location Android 11/12 hides its own leftover Wi-Fi Direct group from it, so it can
        // neither reclaim that group nor create a new one ("Wi-Fi P2P is busy").
        val flags = Context.BIND_AUTO_CREATE or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Context.BIND_INCLUDE_CAPABILITIES else 0)
        bound = app.bindService(intent, connection, flags)
        if (!bound) _state.value = State.Refused("bind_failed")
    }

    fun unbind() {
        detach()
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        service = null
    }

    /** Shows CarPlay in the view behind [hostToken], [width]x[height] px on display [displayId]. */
    fun attach(hostToken: IBinder, displayId: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        val request = Attachment(hostToken, displayId, width, height, screenWidth, screenHeight)
        attachment = request
        attached = false
        if (service != null) sendAttach(request)
    }

    /** Asks for CarPlay again in the same view, after the session ended or failed. */
    fun retry() {
        val request = attachment ?: return
        attached = false
        if (service != null) sendAttach(request)
    }

    fun resize(width: Int, height: Int) {
        val request = attachment ?: return
        request.width = width
        request.height = height
        sendSize(request)
    }

    /**
     * Whether Revv's window has focus. Pulling the notification shade takes it and shows the system
     * bars, which shrinks Revv's layout for a moment; sizes sent meanwhile only letterbox CarPlay
     * instead of reconnecting it. On focus return the current size goes out again, settled.
     */
    fun setHostFocused(focused: Boolean) {
        if (hostFocused == focused) return
        hostFocused = focused
        attachment?.let(::sendSize)
    }

    private fun sendSize(request: Attachment) {
        if (!attached) return
        send(MSG_RESIZE) {
            putInt(KEY_WIDTH, request.width)
            putInt(KEY_HEIGHT, request.height)
            putBoolean(KEY_SETTLED, hostFocused)
        }
    }

    /** The view is gone. The CarPlay session keeps running in the companion until [stop]. */
    fun detach() {
        if (attached) send(MSG_DETACH) {}
        attached = false
        attachment = null
    }

    fun stop() = send(MSG_STOP) {}

    fun siri() = send(MSG_SIRI) {}

    /** Chooses the link: USB ([wireless] false), or wireless over Wi-Fi Direct / the car hotspot. */
    fun configure(wireless: Boolean, hotspotMode: String? = null) = send(MSG_CONFIGURE) {
        putBoolean(KEY_WIRELESS, wireless)
        hotspotMode?.let { putString(KEY_HOTSPOT_MODE, it) }
    }

    /** A touch on the view, [width]x[height] px, for the iPhone. The embedded view itself is not touchable. */
    fun touch(event: MotionEvent, width: Int, height: Int) {
        if (!attached) return
        send(MSG_TOUCH) {
            putParcelable(KEY_EVENT, event)
            putInt(KEY_WIDTH, width)
            putInt(KEY_HEIGHT, height)
        }
    }

    private fun sendAttach(request: Attachment) {
        send(MSG_ATTACH) {
            putBinder(KEY_HOST_TOKEN, request.hostToken)
            putInt(KEY_DISPLAY_ID, request.displayId)
            putInt(KEY_WIDTH, request.width)
            putInt(KEY_HEIGHT, request.height)
            putInt(KEY_SCREEN_WIDTH, request.screenWidth)
            putInt(KEY_SCREEN_HEIGHT, request.screenHeight)
        }
    }

    private fun onReply(message: Message) {
        when (message.what) {
            MSG_ATTACHED -> {
                @Suppress("DEPRECATION")
                val surfacePackage = message.data.getParcelable<SurfaceControlViewHost.SurfacePackage>(KEY_SURFACE_PACKAGE)
                if (surfacePackage == null) {
                    _state.value = State.Refused("no_surface")
                    return
                }
                attached = true
                onSurfacePackage?.invoke(surfacePackage)
            }
            MSG_STATE -> _state.value = State.Session(
                phase = message.data.getString(KEY_PHASE) ?: PHASE_IDLE,
                detail = message.data.getString(KEY_DETAIL) ?: "",
                wireless = message.data.getBoolean(KEY_WIRELESS),
                hotspotMode = message.data.getString(KEY_HOTSPOT_MODE) ?: HOTSPOT_P2P,
                videoActive = message.data.getBoolean(KEY_VIDEO_ACTIVE),
                missing = message.data.getStringArray(KEY_MISSING)?.toList().orEmpty(),
            )
            MSG_ERROR -> {
                attached = false
                _state.value = State.Refused(message.data.getString(KEY_ERROR) ?: "unknown")
            }
        }
    }

    private fun send(what: Int, fill: Bundle.() -> Unit) {
        val target = service ?: return
        try {
            target.send(Message.obtain(null, what).apply {
                data = Bundle().apply(fill)
                replyTo = replies
            })
        } catch (error: RemoteException) {
            Log.w(TAG, "Revv CarPlay is gone", error)
            service = null
            attached = false
        }
    }

    companion object {
        private const val TAG = "CarPlayCompanion"

        // Revv CarPlay's protocol (CarPlayEmbedProtocol in the companion).
        const val ACTION = "com.vivekkaushik.revvcarplay.action.EMBED_CARPLAY"
        private const val MSG_ATTACH = 1
        private const val MSG_RESIZE = 2
        private const val MSG_DETACH = 3
        private const val MSG_STOP = 4
        private const val MSG_SIRI = 5
        private const val MSG_TOUCH = 6
        private const val MSG_CONFIGURE = 7
        private const val MSG_ATTACHED = 101
        private const val MSG_STATE = 102
        private const val MSG_ERROR = 199
        private const val KEY_HOST_TOKEN = "hostToken"
        private const val KEY_DISPLAY_ID = "displayId"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_SETTLED = "settled"
        private const val KEY_SCREEN_WIDTH = "screenWidth"
        private const val KEY_SCREEN_HEIGHT = "screenHeight"
        private const val KEY_SURFACE_PACKAGE = "surfacePackage"
        private const val KEY_PHASE = "phase"
        private const val KEY_DETAIL = "detail"
        private const val KEY_WIRELESS = "wireless"
        private const val KEY_VIDEO_ACTIVE = "videoActive"
        private const val KEY_MISSING = "missing"
        private const val KEY_ERROR = "error"
        private const val KEY_EVENT = "event"
        private const val KEY_HOTSPOT_MODE = "hotspotMode"

        const val PHASE_SETUP_REQUIRED = "setup_required"
        const val PHASE_IDLE = "idle"
        const val PHASE_STARTING = "starting"
        const val PHASE_CONNECTING = "connecting"
        const val PHASE_CONNECTED = "connected"
        const val PHASE_RECONNECTING = "reconnecting"
        const val PHASE_FAILED = "failed"

        const val SETUP_IDENTITY = "identity"
        const val SETUP_VPN = "vpn"
        const val SETUP_WIRELESS_PERMISSIONS = "wireless_permissions"
        const val SETUP_HOTSPOT = "hotspot"

        const val HOTSPOT_P2P = "p2p"
        const val HOTSPOT_MANUAL = "manual"

        /** Whether Revv CarPlay is installed and this Android can host its view (11+). */
        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && serviceIntent(context) != null

        /** Opens Revv CarPlay's own screen, for setup and settings. */
        fun launchIntent(context: Context): Intent? {
            val pkg = serviceIntent(context)?.component?.packageName ?: return null
            return context.packageManager.getLaunchIntentForPackage(pkg)
        }

        private fun serviceIntent(context: Context): Intent? {
            val intent = Intent(ACTION)
            val info = context.packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo ?: return null
            return intent.setClassName(info.packageName, info.name)
        }
    }
}
