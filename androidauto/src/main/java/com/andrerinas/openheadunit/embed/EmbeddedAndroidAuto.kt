package com.andrerinas.openheadunit.embed

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.ProjectionWatchdogPolicy
import com.andrerinas.openheadunit.aap.TouchCoordinateMapper
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.decoder.DecoderStopPolicy
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * An Android Auto session for a view inside the app's own screen (Revv's Auto screen). The
 * session itself is [AapService]'s, as in DiAuto; this stands where DiAuto's projection activity
 * stood: it hands the decoder the view's surface, maps the view's touches onto the phone's
 * picture, asks the phone for video again when the picture stops, and reports the session as
 * a [Status]. The service keeps running while the app shows another screen, so the phone's
 * audio carries on; the picture is asked for again when a view comes back. Main thread.
 */
object EmbeddedAndroidAuto {
    enum class Phase { IDLE, STARTING, WAITING, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val detail: String = "",
        /** A wireless link is armed besides USB: the phone can join over Wi-Fi after a Bluetooth wake. */
        val wireless: Boolean = false,
        /** The phone's picture is on the view. */
        val videoActive: Boolean = false,
        /** The phone ended the session itself (Exit in Android Auto); asking again re-arms the link. */
        val phoneExited: Boolean = false,
    )

    private const val TAG = "RevvAndroidAuto-Embed"
    private const val WATCHDOG_MILLIS = 1_500L
    /** A handshake that has not finished by then has hung: the phone is asked again. */
    private const val HANDSHAKE_STALL_MS = 30_000L
    /** A running session whose phone has sent nothing for this long has lost its link (its pings come every few seconds). */
    private const val LINK_LOST_MS = 40_000L
    /** Native-focus requests closer together than this are one Exit tap. */
    private const val EXIT_REPEAT_MS = 1_500L
    /** How often a still picture is nudged for video, mid-session, in case it stopped rather than paused. */
    private const val STILL_PICTURE_NUDGE_MS = 15_000L
    /** Forced reconnects come no closer together than this, and stop after [MAX_FORCED_RESTARTS] in a row. */
    private const val FORCED_RESTART_GAP_MS = 30_000L
    private const val MAX_FORCED_RESTARTS = 4

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var surface: Surface? = null
    private var viewSize = Size(0, 0)
    private var displaySize = Size(0, 0)
    private var running = false
    private var renderedSinceSurface = false
    private var lastDisconnect: CommManager.ConnectionState.Disconnected? = null
    private var lastError: String? = null
    private var lastFocusRequestMs = 0L
    // Recovering a stuck session: when the handshake began, when the transport came up, and how
    // often the session was ended for the service to bring back (see recoverIfStuck).
    private var handshakeSinceMs = 0L
    private var transportSinceMs = 0L
    private var lastForcedRestartMs = 0L
    private var forcedRestarts = 0
    /** Why the engine stopped trying after repeated stalls; shown until the driver asks again. */
    private var gaveUp: String? = null
    private var lastExitMs = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, WATCHDOG_MILLIS)
        }
    }

    var status = Status()
        private set

    /** How many host views show the session; read by the service's callbacks. */
    val hostViews = AtomicInteger()

    /** The phone's picture should be shown: the handshake finished with no view, or the phone asked. */
    @Volatile
    var onProjectionWanted: (() -> Unit)? = null

    /**
     * The driver tapped Exit in Android Auto on the phone: the host's own screen is wanted. The
     * session stays up (the phone holds the screen back until a view asks for its picture
     * again), unless the phone ended it, which is reported here too.
     */
    @Volatile
    var onPhoneExited: (() -> Unit)? = null

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }

    /** A session is wanted: the service is running or being started. */
    val isRunning: Boolean get() = running

    /**
     * Hears the service from now on, before any view asks for a session: a phone plugged in
     * starts the service by itself (UsbAttachedActivity), and its handshake then reaches
     * [onProjectionWanted] so the host can show a view.
     */
    fun bind(context: Context) {
        val app = context.applicationContext
        appContext = app
        val component = App.provide(app)
        if (scope == null) {
            val created = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = created
            created.launch { component.commManager.connectionState.collect { onState(it) } }
        }
        App.onHandshakeComplete = { main.post { handshakeComplete() } }
        App.onProjectionRequested = { main.post { onProjectionWanted?.invoke() } }
        // Exit in Android Auto: the phone keeps the session and waits for the view to ask again.
        // The phone can send the request twice in a row; one tap means one step back for the host.
        App.onNativeUiRequested = {
            main.post {
                val now = SystemClock.elapsedRealtime()
                if (now - lastExitMs > EXIT_REPEAT_MS) {
                    lastExitMs = now
                    Log.i(TAG, "the driver exited Android Auto on the phone; the session stays up")
                    renderedSinceSurface = false
                    onPhoneExited?.invoke()
                }
                publish()
            }
        }
        component.videoDecoder.onFirstFrameListener = {
            main.post {
                renderedSinceSurface = true
                publish()
            }
        }
    }

    /**
     * Starts Android Auto for a host view of [view] pixels on a [display]-pixel screen, or keeps
     * the running session. The phone is told the view's size, so it draws into exactly that; a
     * session that began before the view (a phone plugged in) reconnects if it needs another size.
     */
    fun start(context: Context, view: Size, display: Size, metrics: DisplayMetrics) {
        bind(context)
        val app = appContext ?: return
        viewSize = view
        displaySize = display
        fitView(app, metrics)
        // The view came back to a session still waiting: the driver wants the phone now.
        if (running) wakeSavedPhone()
        if (!running) {
            running = true
            lastDisconnect = null
            lastError = null
            Log.i(TAG, "starting the Android Auto service for a ${view.width}x${view.height} view")
            ContextCompat.startForegroundService(app, Intent(app, AapService::class.java))
        }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MILLIS)
        publish()
    }

    /**
     * Listens for the phone before any view asks: starts the service, which arms the wireless
     * link and wakes the saved phone itself. For a host that shows Android Auto and has a wireless
     * link set up, so the car's phone connects as soon as Revv is up.
     */
    fun arm(context: Context) {
        bind(context)
        if (running) return
        retry()
    }

    /**
     * Asks for a phone again: starts the service when it is off; while it waits, wakes the saved
     * phone over Bluetooth so it joins the wireless link (the poke re-arms a link the phone
     * closed), else re-arms the link and looks at USB.
     */
    fun retry() {
        val app = appContext ?: return
        lastDisconnect = null
        lastError = null
        gaveUp = null
        forcedRestarts = 0
        if (running && wakeSavedPhone()) {
            publish()
            return
        }
        val intent = Intent(app, AapService::class.java)
        if (running) intent.action = AapService.ACTION_START_WIRELESS_SCAN
        running = true
        ContextCompat.startForegroundService(app, intent)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MILLIS)
        publish()
    }

    /**
     * Wakes the phone chosen for the wireless link over Bluetooth, so it joins the head unit's
     * Wi-Fi: what the service does by itself once its link is up, asked for again now. False
     * when there is nothing to wake: no wireless link, no phone chosen, or a session already up.
     */
    private fun wakeSavedPhone(): Boolean {
        val app = appContext ?: return false
        if (!running) return false
        val component = App.provide(app)
        val settings = component.settings
        if (settings.wifiConnectionMode != 3) return false
        val address = settings.autoStartBluetoothDeviceMacs.firstOrNull() ?: return false
        val state = component.commManager.connectionState.value
        if (state !is CommManager.ConnectionState.Disconnected && state !is CommManager.ConnectionState.Error) return false
        // Woken a moment ago already (the view came back, or the phone's own Bluetooth connected):
        // the phone is on its way, and asking again would disturb its handshake.
        if (!App.mayWakePhone()) return true
        Log.i(TAG, "waking the chosen phone over Bluetooth")
        wakePhone(address)
        return true
    }

    /**
     * The host view changed size. The phone draws at one resolution per session, so a view that
     * needs another reconnects; one that fits the same resolution only gets new margins.
     */
    fun resize(view: Size, metrics: DisplayMetrics) {
        if (view.width <= 0 || view.height <= 0 || view == viewSize) return
        val app = appContext ?: return
        viewSize = view
        fitView(app, metrics)
        publish()
    }

    /**
     * Sizes the phone's picture for the host view. Once the phone has agreed a resolution the
     * screen config keeps it locked, so the lock is lifted to see what the view needs now: a
     * session that needs another resolution reconnects (the service brings the phone back and
     * the new handshake locks the new one), and one that fits keeps its resolution and gets the
     * view's margins. Returns true when it reconnected.
     */
    private fun fitView(app: Context, metrics: DisplayMetrics): Boolean {
        val comm = App.provide(app).commManager
        val state = comm.connectionState.value
        val negotiated = state is CommManager.ConnectionState.StartingTransport ||
            state is CommManager.ConnectionState.HandshakeComplete ||
            state is CommManager.ConnectionState.TransportStarted
        val before = HeadUnitScreenConfig.negotiatedResolutionType
        HeadUnitScreenConfig.unlockResolution()
        configureScreen(app, metrics)
        if (!negotiated) return false
        if (HeadUnitScreenConfig.negotiatedResolutionType != before) {
            Log.i(TAG, "the phone draws at $before; the ${viewSize.width}x${viewSize.height} view needs ${HeadUnitScreenConfig.negotiatedResolutionType}; reconnecting")
            comm.disconnect(sendByeBye = true, isUserExit = false)
            return true
        }
        HeadUnitScreenConfig.lockResolution()
        if (state is CommManager.ConnectionState.TransportStarted) {
            comm.sendUpdateUiConfigRequest(
                HeadUnitScreenConfig.getLeftMargin(), HeadUnitScreenConfig.getTopMargin(),
                HeadUnitScreenConfig.getRightMargin(), HeadUnitScreenConfig.getBottomMargin(),
            )
        }
        return false
    }

    /** Tells the service the link settings changed: it re-arms wireless and looks at USB again. */
    fun linkChanged() {
        val app = appContext ?: return
        if (!running) return
        ContextCompat.startForegroundService(app, Intent(app, AapService::class.java).setAction(AapService.ACTION_START_WIRELESS_SCAN))
        publish()
    }

    /**
     * A display or audio setting changed. The phone takes them at connection time, so a running
     * session reconnects; the service brings the phone back by itself.
     */
    fun reconnectForSettings() {
        val app = appContext ?: return
        val comm = App.provide(app).commManager
        if (!comm.isConnected) return
        Log.i(TAG, "settings changed; reconnecting")
        HeadUnitScreenConfig.unlockResolution()
        comm.disconnect(sendByeBye = true, isUserExit = false)
    }

    private fun configureScreen(app: Context, metrics: DisplayMetrics) {
        HeadUnitScreenConfig.hostView = HeadUnitScreenConfig.HostView(viewSize.width, viewSize.height, displaySize.width, displaySize.height)
        HeadUnitScreenConfig.init(app, metrics, App.provide(app).settings)
    }

    /** The view's surface, [width] by [height] pixels: the decoder draws there from now on. */
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        val app = appContext ?: return
        this.surface = surface
        renderedSinceSurface = false
        val component = App.provide(app)
        component.videoDecoder.setSurface(surface)
        val comm = component.commManager
        if (HeadUnitScreenConfig.updateSurfaceDimensions(width, height) && comm.connectionState.value is CommManager.ConnectionState.TransportStarted) {
            comm.sendUpdateUiConfigRequest(
                HeadUnitScreenConfig.getLeftMargin(), HeadUnitScreenConfig.getTopMargin(),
                HeadUnitScreenConfig.getRightMargin(), HeadUnitScreenConfig.getBottomMargin(),
            )
        }
        when (comm.connectionState.value) {
            // The service starts the handshake itself; this is the fallback DiAuto's activity kept.
            is CommManager.ConnectionState.Connected -> scope?.launch { comm.startHandshake() }
            // The handshake finished before the view came: reading starts now that there is a surface.
            is CommManager.ConnectionState.HandshakeComplete -> scope?.launch { comm.startReading() }
            // Back from another screen: the phone stopped sending video when the view went; ask again.
            is CommManager.ConnectionState.TransportStarted -> comm.send(VideoFocusEvent(gain = true, unsolicited = true))
            else -> {}
        }
        publish()
    }

    /** The view's surface is gone. The phone stops its video; its audio carries on. */
    fun clearSurface(surface: Surface) {
        val app = appContext
        if (app != null) {
            val component = App.provide(app)
            if (component.videoDecoder.isCurrentSurface(surface)) {
                component.commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
                component.videoDecoder.stopIfCurrentSurface(surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
            }
        }
        if (this.surface === surface) this.surface = null
        renderedSinceSurface = false
        publish()
    }

    private fun handshakeComplete() {
        val app = appContext ?: return
        HeadUnitScreenConfig.lockResolution()
        if (surface != null) {
            scope?.launch { App.provide(app).commManager.startReading() }
        } else {
            Log.i(TAG, "handshake done with no view attached; asking the host to show one")
            onProjectionWanted?.invoke()
        }
        publish()
    }

    /** Forwards a touch on a host view of the given size to the phone. */
    fun sendTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val app = appContext ?: return false
        val component = App.provide(app)
        val comm = component.commManager
        if (comm.connectionState.value !is CommManager.ConnectionState.TransportStarted) return false
        val action = TouchEvent.motionEventToAction(event) ?: return false
        val videoW = HeadUnitScreenConfig.getNegotiatedWidth()
        val videoH = HeadUnitScreenConfig.getNegotiatedHeight()
        if (videoW <= 0 || videoH <= 0 || viewWidth <= 0 || viewHeight <= 0) return false
        val settings = component.settings
        val marginW = HeadUnitScreenConfig.getWidthMargin().toFloat()
        val marginH = HeadUnitScreenConfig.getHeightMargin().toFloat()
        val pointers = (0 until event.pointerCount).map { index ->
            val point = TouchCoordinateMapper.map(
                rawX = event.getX(index),
                rawY = event.getY(index),
                inputSurfaceWidth = viewWidth.toFloat(),
                inputSurfaceHeight = viewHeight.toFloat(),
                negotiatedWidth = videoW,
                negotiatedHeight = videoH,
                marginWidth = marginW,
                marginHeight = marginH,
                stretchToFill = settings.stretchToFill,
                hudMirroring = settings.hudMirroring,
            )
            Triple(event.getPointerId(index), point.x, point.y)
        }
        comm.send(TouchEvent(SystemClock.elapsedRealtime(), action, event.actionIndex, pointers))
        return true
    }

    /** Opens the phone's assistant, or closes it when it is listening. */
    fun assistant(): Boolean {
        val app = appContext ?: return false
        App.provide(app).commManager.sendToggleVoiceAssistant()
        return true
    }

    /** Wakes the paired phone at [address] over Bluetooth so it joins the wireless link. */
    fun wakePhone(address: String) {
        val app = appContext ?: return
        if (!running) return
        val intent = Intent(app, AapService::class.java).setAction(AapService.ACTION_NATIVE_AA_POKE).putExtra(AapService.EXTRA_MAC, address)
        ContextCompat.startForegroundService(app, intent)
    }

    /** Ends the session and the service: Android Auto is off until [start] or [retry]. */
    fun stop() {
        val app = appContext ?: return
        running = false
        lastDisconnect = null
        lastError = null
        main.removeCallbacks(watchdog)
        ContextCompat.startForegroundService(app, Intent(app, AapService::class.java).setAction(AapService.ACTION_STOP_SERVICE))
        publish()
    }

    private fun onState(state: CommManager.ConnectionState) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            is CommManager.ConnectionState.Disconnected -> {
                lastDisconnect = state
                renderedSinceSurface = false
                handshakeSinceMs = 0L
                transportSinceMs = 0L
                if (state.isUserExit && running) {
                    Log.i(TAG, "the driver exited Android Auto on the phone")
                    onPhoneExited?.invoke()
                }
            }
            is CommManager.ConnectionState.Error -> {
                lastError = state.message
                handshakeSinceMs = 0L
                transportSinceMs = 0L
            }
            is CommManager.ConnectionState.TransportStarted -> {
                lastError = null
                lastDisconnect = null
                handshakeSinceMs = 0L
                if (transportSinceMs == 0L) transportSinceMs = now
            }
            is CommManager.ConnectionState.Connected,
            is CommManager.ConnectionState.StartingTransport,
            is CommManager.ConnectionState.HandshakeComplete -> {
                if (handshakeSinceMs == 0L) handshakeSinceMs = now
                transportSinceMs = 0L
            }
            else -> {
                handshakeSinceMs = 0L
                transportSinceMs = 0L
            }
        }
        // A phone plugged in starts the service without any view asking: the session is on then.
        if (!running && state !is CommManager.ConnectionState.Disconnected && state !is CommManager.ConnectionState.Error) {
            Log.i(TAG, "the service connected a phone by itself")
            running = true
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, WATCHDOG_MILLIS)
        }
        publish()
    }

    /**
     * Asks the phone for its picture while there is none: once before the first frame of a surface
     * (every tick), and again, throttled, when a running stream stops for longer than the phone
     * ever pauses on its own.
     */
    private fun tick() {
        val app = appContext ?: return
        val component = App.provide(app)
        val comm = component.commManager
        val decoder = component.videoDecoder
        val now = SystemClock.elapsedRealtime()
        if (recoverIfStuck(now, comm, decoder)) return
        val live = ProjectionWatchdogPolicy.isSessionLive(comm.connectionState.value)
        val rendered = decoder.lastFrameRenderedMs > 0
        if (rendered && !renderedSinceSurface) {
            renderedSinceSurface = true
            publish()
        }
        // A picture moving again means the session is healthy: forced reconnects start afresh.
        if (rendered && now - decoder.lastFrameRenderedMs < ProjectionWatchdogPolicy.FRAME_GAP_MS) forcedRestarts = 0
        if (!live || surface == null) return
        if (!rendered) {
            if (ProjectionWatchdogPolicy.shouldNudgeForFirstFrame(live, surfaceSet = true, renderedSinceSurfaceSet = false, warmRelaunchCycleSpent = false)) {
                lastFocusRequestMs = now
                comm.send(VideoFocusEvent(gain = true, unsolicited = true))
            }
            return
        }
        // Android Auto sends no frames while nothing on its screen moves, so a still picture is
        // usually just that: asking for video again costs the phone a keyframe each time, and
        // once in a while is enough to bring back one that really stopped.
        val stopped = now - decoder.lastFrameRenderedMs > ProjectionWatchdogPolicy.FRAME_GAP_MS
        if (ProjectionWatchdogPolicy.shouldRequestVideoFocus(stopped, now, lastFocusRequestMs) && now - lastFocusRequestMs > STILL_PICTURE_NUDGE_MS) {
            lastFocusRequestMs = now
            Log.i(TAG, "no frames for ${now - decoder.lastFrameRenderedMs}ms; asking for video again")
            comm.send(VideoFocusEvent(gain = true, unsolicited = true))
        }
    }

    /**
     * A session the phone has stopped answering is ended so the service brings it back, as
     * CarPlay's engine does: a handshake that never finishes, or a running session whose phone
     * has gone silent (its picture stopped and nothing came over the link for [LINK_LOST_MS]).
     * The service reconnects by itself after a disconnect that was not the driver's: USB looks at
     * the phone again, a wireless link re-arms and wakes the phone. Forced no closer together than
     * [FORCED_RESTART_GAP_MS], and given up after [MAX_FORCED_RESTARTS] in a row without a picture,
     * so a phone that keeps stalling does not loop forever: the driver's Connect tries again.
     * Returns true when it ended the session.
     */
    private fun recoverIfStuck(now: Long, comm: CommManager, decoder: VideoDecoder): Boolean {
        if (!running || gaveUp != null) return false
        val state = comm.connectionState.value
        val reason = when {
            handshakeSinceMs != 0L && now - handshakeSinceMs > HANDSHAKE_STALL_MS ->
                "the handshake did not finish in ${(now - handshakeSinceMs) / 1000}s"
            state is CommManager.ConnectionState.TransportStarted && transportSinceMs != 0L && now - transportSinceMs > LINK_LOST_MS -> {
                val lastMessage = comm.lastAapMessageMs
                val linkQuiet = if (lastMessage == 0L) now - transportSinceMs else now - lastMessage
                val pictureGap = if (decoder.lastFrameRenderedMs == 0L) now - transportSinceMs else now - decoder.lastFrameRenderedMs
                if (linkQuiet > LINK_LOST_MS && pictureGap > ProjectionWatchdogPolicy.FRAME_GAP_MS) {
                    "the phone has sent nothing for ${linkQuiet / 1000}s"
                } else {
                    null
                }
            }
            else -> null
        } ?: return false
        if (now - lastForcedRestartMs < FORCED_RESTART_GAP_MS) return false
        lastForcedRestartMs = now
        forcedRestarts += 1
        handshakeSinceMs = 0L
        transportSinceMs = 0L
        if (forcedRestarts > MAX_FORCED_RESTARTS) {
            Log.w(TAG, "$reason, for the ${forcedRestarts}th time without a picture in between; giving up until asked again")
            comm.disconnect(sendByeBye = false, isUserExit = false)
            stop()
            gaveUp = appContext?.getString(R.string.aa_embed_kept_stalling)
            publish()
            return true
        }
        Log.w(TAG, "$reason; ending the session for the service to bring it back (attempt $forcedRestarts)")
        lastError = appContext?.getString(R.string.aa_embed_stalled)
        comm.disconnect(sendByeBye = false, isUserExit = false)
        publish()
        return true
    }

    private fun usbPhonePresent(app: Context): Boolean {
        val usb = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return runCatching { usb.deviceList.values.any { UsbDeviceCompat.isAndroidDevice(it) } }.getOrDefault(false)
    }

    private fun publish() {
        val app = appContext
        val component = app?.let { App.provide(it) }
        val state = component?.commManager?.connectionState?.value
        val wireless = component?.settings?.wifiConnectionMode == 3
        var exited = false
        val phase: Phase
        val detail: String
        val stalledForGood = gaveUp
        when {
            app == null || component == null -> {
                phase = Phase.IDLE
                detail = ""
            }
            !running && stalledForGood != null -> {
                phase = Phase.FAILED
                detail = stalledForGood
            }
            !running -> {
                phase = Phase.IDLE
                detail = app.getString(R.string.aa_embed_idle)
            }
            state is CommManager.ConnectionState.Connecting -> {
                phase = Phase.CONNECTING
                detail = app.getString(R.string.aa_embed_connecting)
            }
            state is CommManager.ConnectionState.Connected ||
                state is CommManager.ConnectionState.StartingTransport ||
                state is CommManager.ConnectionState.HandshakeComplete -> {
                phase = Phase.CONNECTING
                detail = app.getString(R.string.aa_embed_handshake)
            }
            state is CommManager.ConnectionState.TransportStarted -> {
                phase = Phase.CONNECTED
                detail = app.getString(R.string.aa_embed_connected)
            }
            state is CommManager.ConnectionState.Error -> {
                phase = Phase.FAILED
                detail = state.message
            }
            else -> {
                val last = lastDisconnect
                val error = lastError
                when {
                    last?.isUserExit == true -> {
                        phase = Phase.WAITING
                        detail = app.getString(R.string.aa_embed_phone_exited)
                        exited = true
                    }
                    error != null -> {
                        phase = Phase.RECONNECTING
                        detail = error
                    }
                    last != null -> {
                        phase = Phase.RECONNECTING
                        detail = app.getString(R.string.aa_embed_disconnected)
                    }
                    usbPhonePresent(app) -> {
                        phase = Phase.WAITING
                        detail = app.getString(R.string.aa_embed_switching_usb)
                    }
                    wireless -> {
                        phase = Phase.WAITING
                        detail = app.getString(R.string.aa_embed_waiting_wireless)
                    }
                    else -> {
                        phase = Phase.WAITING
                        detail = app.getString(R.string.aa_embed_waiting_usb)
                    }
                }
            }
        }
        status = Status(
            phase = phase,
            detail = detail,
            wireless = wireless,
            videoActive = phase == Phase.CONNECTED && surface != null && renderedSinceSurface,
            phoneExited = exited,
        )
        listeners.forEach { it(status) }
    }
}
