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
import android.widget.Toast
import com.vivekkaushik.revv.nav.Turn
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * RevvCarPlay, the separate (GPL) companion app that runs the CarPlay stack. Revv binds to its
 * embed service, hands it a SurfaceView's host token, and gets a SurfacePackage back: CarPlay then
 * draws and takes touch inside Revv's own layout while the session itself lives in the companion.
 * RevvCarPlay's settings (identity, link, display, audio, location) are read and changed here too;
 * the companion keeps no screen of its own for them. Main thread.
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
            /**
             * Stopped because another Wi-Fi Direct connection, such as screen mirroring, holds the
             * radio ([detail] names it): [resetWifiDirect] ends it and connects.
             */
            val resetWifiDirect: Boolean = false,
        ) : State
        /** The companion refused the view (ERROR_*). */
        data class Refused(val error: String) : State
    }

    /** RevvCarPlay's settings as it last reported them. Sizes and streams are the companion's own units. */
    data class Settings(
        val identityInstalled: Boolean,
        val wireless: Boolean,
        /** [HOTSPOT_P2P] or [HOTSPOT_MANUAL]. */
        val hotspotMode: String,
        /** The saved car hotspot name; the password stays in the companion. */
        val hotspotSsid: String,
        val hotspotReady: Boolean,
        /** The head unit's hotspot is on; null when its firmware hides that. */
        val hotspotOn: Boolean?,
        /** RevvCarPlay may turn the hotspot on itself ("Modify system settings" granted to it). */
        val hotspotSwitchAllowed: Boolean,
        val phoneAddress: String?,
        val phoneName: String,
        /** Assumed CarPlay width in mm: [SIZE_LARGE], [SIZE_MEDIUM] or [SIZE_SMALL]. */
        val carPlaySize: Int,
        /** Tenths of the view's resolution: 10, 8 or 6. */
        val resolution: Int,
        val frameRate: Int,
        val hevc: Boolean,
        val rightHandDrive: Boolean,
        val audioFocus: Boolean,
        /** 0 routes automatically; 1–20 are legacy Android audio streams. */
        val mediaStream: Int,
        val navigationStream: Int,
        val musicBufferMillis: Int,
        val advancedAudioAvailable: Boolean,
        val advancedAudio: Boolean,
        val locationReporting: Boolean,
        /** RevvCarPlay holds the precise location permission location reporting needs. */
        val locationPermitted: Boolean,
    )

    /**
     * CarPlay's route as the iPhone guides it. CarPlay names the destination but never sends where
     * it is, so Revv has to look the name up to route there itself.
     */
    data class Guidance(
        /** As Apple Maps shows it: a place or an address. Empty when CarPlay sent none. */
        val destination: String,
        /** What is left of CarPlay's route. */
        val routeMeters: Long?,
        /** Apple's RouteGuidanceManeuverType; null while the next maneuver is unknown. */
        val maneuverType: Int?,
        val maneuverMeters: Int,
        /** The road the next maneuver turns onto, else the one the car is on. */
        val road: String,
        val leftHandTraffic: Boolean,
        val arrivalEpochSeconds: Long?,
        val remainingSeconds: Long?,
    ) {
        /** The next maneuver as one of Revv's turns; null when there is none or it has no arrow. */
        val turn: Turn?
            get() = when (maneuverType) {
                null -> null
                1, 20 -> Turn.Left
                2, 21 -> Turn.Right
                47 -> Turn.SharpLeft
                48 -> Turn.SharpRight
                49 -> Turn.SlightLeft
                50 -> Turn.SlightRight
                13, 52 -> Turn.KeepLeft
                14, 53 -> Turn.KeepRight
                22 -> Turn.ExitLeft
                23 -> Turn.ExitRight
                // Ramps without a side: take the near side of the road.
                8 -> if (leftHandTraffic) Turn.ExitLeft else Turn.ExitRight
                9 -> if (leftHandTraffic) Turn.RampLeft else Turn.RampRight
                // U-turns cross the traffic, so they go right where cars keep left.
                4, 18, 26 -> if (leftHandTraffic) Turn.UTurnRight else Turn.UTurnLeft
                6, 19, in ROUNDABOUT_EXITS -> Turn.Roundabout
                7 -> Turn.LeaveRoundabout
                10, 12, 24, 25, 27 -> Turn.Arrive
                15, 16, 17 -> Turn.Ferry
                3, 5, 11, 51 -> Turn.Straight
                else -> null
            }

        /** Which exit, when the next maneuver is a numbered roundabout exit. */
        val roundaboutExit: Int?
            get() = maneuverType?.takeIf { it in ROUNDABOUT_EXITS }?.let { it - ROUNDABOUT_EXITS.first + 1 }

        private companion object {
            /** Apple's RoundaboutExit1 to RoundaboutExit19. */
            val ROUNDABOUT_EXITS = 28..46
        }
    }

    private class Attachment(val hostToken: IBinder, val displayId: Int, var width: Int, var height: Int, val screenWidth: Int, val screenHeight: Int)

    private val app = context.applicationContext
    private val _state = MutableStateFlow<State>(if (available(app)) State.Connecting else State.Unavailable)
    val state: StateFlow<State> = _state

    private val _settings = MutableStateFlow<Settings?>(null)
    /** Null until the companion answers. */
    val settings: StateFlow<Settings?> = _settings

    private val _guidance = MutableStateFlow<Guidance?>(null)
    /** CarPlay's route while the iPhone guides one; null otherwise, or when Revv may not read it. */
    val guidance: StateFlow<Guidance?> = _guidance

    private val _settingsRefused = MutableStateFlow(false)
    /** The companion takes settings only from an app signed with its own certificate, and Revv isn't. */
    val settingsRefused: StateFlow<Boolean> = _settingsRefused

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
            send(MSG_GET_SETTINGS) {}
            attachment?.let { sendAttach(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            attached = false
            _guidance.value = null
            if (_state.value !is State.Unavailable) _state.value = State.Connecting
        }

        // The companion was stopped or updated: Android will not bring it back for this binding.
        override fun onBindingDied(name: ComponentName) {
            service = null
            attached = false
            _guidance.value = null
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
        _guidance.value = null
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

    /** Asks the companion for its settings again, e.g. after its permissions changed in Android's settings. */
    fun refreshSettings() = send(MSG_GET_SETTINGS) {}

    /** Changes one setting (SETTING_*); a running session reconnects a moment after the last change. */
    fun setSetting(name: String, value: Int) = send(MSG_SET_SETTING) {
        putString(KEY_SETTING, name)
        putInt(KEY_VALUE, value)
    }

    fun setSetting(name: String, value: Boolean) = send(MSG_SET_SETTING) {
        putString(KEY_SETTING, name)
        putBoolean(KEY_VALUE, value)
    }

    /** Saves the car hotspot's details; wireless CarPlay then links over the car hotspot. */
    fun saveHotspot(ssid: String, passphrase: String) = send(MSG_SET_HOTSPOT) {
        putString(KEY_SSID, ssid)
        putString(KEY_PASSPHRASE, passphrase)
    }

    /** The paired iPhone wireless CarPlay connects to. */
    fun choosePhone(address: String, name: String) = send(MSG_SET_PHONE) {
        putString(KEY_ADDRESS, address)
        putString(KEY_NAME, name)
    }

    /** Picked files, name to bytes; the companion finds identity.pk8 and certificate.p7b among them. */
    fun importIdentity(files: Map<String, ByteArray>) = send(MSG_IMPORT_IDENTITY) {
        putBundle(KEY_FILES, Bundle().apply { files.forEach { (name, bytes) -> putByteArray(name, bytes) } })
    }

    fun removeIdentity() = send(MSG_REMOVE_IDENTITY) {}

    /** Saves a diagnostic report to the head unit's Downloads folder. */
    fun saveReport() = send(MSG_SAVE_REPORT) {}

    /** Turns the head unit's hotspot on now; choosing the car hotspot link and connecting over it do too. */
    fun turnOnHotspot() = send(MSG_HOTSPOT_ON) {}

    /**
     * Ends the head unit's other Wi-Fi Direct connection, whichever app made it, and connects
     * CarPlay. Only when the driver asks: it ends screen mirroring to a TV, for one.
     */
    fun resetWifiDirect() = send(MSG_RESET_WIFI_DIRECT) {}

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
                resetWifiDirect = message.data.getBoolean(KEY_RESET_WIFI_DIRECT),
            )
            MSG_SETTINGS -> message.data.getBundle(KEY_SETTINGS)?.let { _settings.value = settingsOf(it) }
            MSG_GUIDANCE -> _guidance.value = guidanceOf(message.data)
            // How an import, a hotspot save or a report went.
            MSG_NOTICE -> message.data.getString(KEY_NOTICE)?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
            MSG_ERROR -> when (val error = message.data.getString(KEY_ERROR) ?: "unknown") {
                ERROR_UNTRUSTED -> _settingsRefused.value = true
                else -> {
                    attached = false
                    _state.value = State.Refused(error)
                }
            }
        }
    }

    private fun guidanceOf(bundle: Bundle): Guidance? {
        fun long(key: String) = if (bundle.containsKey(key)) bundle.getLong(key) else null
        val guidance = Guidance(
            destination = bundle.getString(KEY_DESTINATION).orEmpty().trim(),
            routeMeters = long(KEY_ROUTE_METERS),
            maneuverType = if (bundle.containsKey(KEY_MANEUVER_TYPE)) bundle.getInt(KEY_MANEUVER_TYPE) else null,
            maneuverMeters = bundle.getInt(KEY_MANEUVER_METERS),
            road = bundle.getString(KEY_ROAD).orEmpty(),
            leftHandTraffic = bundle.getInt(KEY_DRIVING_SIDE) == 1,
            arrivalEpochSeconds = long(KEY_ARRIVAL),
            remainingSeconds = long(KEY_REMAINING_SECONDS),
        )
        return guidance.takeIf { it.destination.isNotEmpty() || it.maneuverType != null }
    }

    private fun settingsOf(bundle: Bundle) = Settings(
        identityInstalled = bundle.getBoolean(KEY_IDENTITY_INSTALLED),
        wireless = bundle.getBoolean(KEY_WIRELESS),
        hotspotMode = bundle.getString(KEY_HOTSPOT_MODE) ?: HOTSPOT_P2P,
        hotspotSsid = bundle.getString(KEY_HOTSPOT_SSID).orEmpty(),
        hotspotReady = bundle.getBoolean(KEY_HOTSPOT_READY),
        hotspotOn = if (bundle.containsKey(KEY_HOTSPOT_ON)) bundle.getBoolean(KEY_HOTSPOT_ON) else null,
        hotspotSwitchAllowed = bundle.getBoolean(KEY_HOTSPOT_SWITCH_ALLOWED),
        phoneAddress = bundle.getString(KEY_PHONE_ADDRESS),
        phoneName = bundle.getString(KEY_PHONE_NAME).orEmpty(),
        carPlaySize = bundle.getInt(SETTING_CARPLAY_SIZE, SIZE_MEDIUM),
        resolution = bundle.getInt(SETTING_RESOLUTION, 10),
        frameRate = bundle.getInt(SETTING_FRAME_RATE, 30),
        hevc = bundle.getBoolean(SETTING_HEVC),
        rightHandDrive = bundle.getBoolean(SETTING_RIGHT_HAND_DRIVE),
        audioFocus = bundle.getBoolean(SETTING_AUDIO_FOCUS),
        mediaStream = bundle.getInt(SETTING_MEDIA_STREAM),
        navigationStream = bundle.getInt(SETTING_NAVIGATION_STREAM),
        musicBufferMillis = bundle.getInt(SETTING_MUSIC_BUFFER, 300),
        advancedAudioAvailable = bundle.getBoolean(KEY_ADVANCED_AUDIO_AVAILABLE),
        advancedAudio = bundle.getBoolean(SETTING_ADVANCED_AUDIO),
        locationReporting = bundle.getBoolean(SETTING_LOCATION_REPORTING),
        locationPermitted = bundle.getBoolean(KEY_LOCATION_PERMITTED),
    )

    private fun send(what: Int, fill: Bundle.() -> Unit) {
        val target = service ?: return
        try {
            target.send(Message.obtain(null, what).apply {
                data = Bundle().apply(fill)
                replyTo = replies
            })
        } catch (error: RemoteException) {
            Log.w(TAG, "RevvCarPlay is gone", error)
            service = null
            attached = false
        }
    }

    companion object {
        private const val TAG = "CarPlayCompanion"

        // RevvCarPlay's protocol (CarPlayEmbedProtocol in the companion).
        const val ACTION = "com.vivekkaushik.revvcarplay.action.EMBED_CARPLAY"
        private const val MSG_ATTACH = 1
        private const val MSG_RESIZE = 2
        private const val MSG_DETACH = 3
        private const val MSG_STOP = 4
        private const val MSG_SIRI = 5
        private const val MSG_TOUCH = 6
        private const val MSG_CONFIGURE = 7
        private const val MSG_GET_SETTINGS = 8
        private const val MSG_SET_SETTING = 9
        private const val MSG_SET_HOTSPOT = 10
        private const val MSG_SET_PHONE = 11
        private const val MSG_IMPORT_IDENTITY = 12
        private const val MSG_REMOVE_IDENTITY = 13
        private const val MSG_SAVE_REPORT = 14
        private const val MSG_HOTSPOT_ON = 15
        private const val MSG_RESET_WIFI_DIRECT = 16
        private const val MSG_ATTACHED = 101
        private const val MSG_STATE = 102
        private const val MSG_SETTINGS = 103
        private const val MSG_NOTICE = 104
        private const val MSG_GUIDANCE = 105
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
        private const val KEY_RESET_WIFI_DIRECT = "resetWifiDirect"
        private const val KEY_ERROR = "error"
        private const val KEY_EVENT = "event"
        private const val KEY_HOTSPOT_MODE = "hotspotMode"
        private const val KEY_SETTING = "setting"
        private const val KEY_VALUE = "value"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_FILES = "files"
        private const val KEY_NOTICE = "notice"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_ROUTE_METERS = "routeMeters"
        private const val KEY_MANEUVER_TYPE = "maneuverType"
        private const val KEY_MANEUVER_METERS = "maneuverMeters"
        private const val KEY_ROAD = "road"
        private const val KEY_DRIVING_SIDE = "drivingSide"
        private const val KEY_ARRIVAL = "arrival"
        private const val KEY_REMAINING_SECONDS = "remainingSeconds"
        private const val ERROR_UNTRUSTED = "untrusted"
        private const val KEY_IDENTITY_INSTALLED = "identityInstalled"
        private const val KEY_HOTSPOT_SSID = "hotspotSsid"
        private const val KEY_HOTSPOT_READY = "hotspotReady"
        private const val KEY_HOTSPOT_ON = "hotspotOn"
        private const val KEY_HOTSPOT_SWITCH_ALLOWED = "hotspotSwitchAllowed"
        private const val KEY_PHONE_ADDRESS = "phoneAddress"
        private const val KEY_PHONE_NAME = "phoneName"
        private const val KEY_ADVANCED_AUDIO_AVAILABLE = "advancedAudioAvailable"
        private const val KEY_LOCATION_PERMITTED = "locationPermitted"

        // Settings Revv may change.
        const val SETTING_CARPLAY_SIZE = "carPlaySize"
        const val SETTING_RESOLUTION = "resolution"
        const val SETTING_FRAME_RATE = "frameRate"
        const val SETTING_HEVC = "hevc"
        const val SETTING_RIGHT_HAND_DRIVE = "rightHandDrive"
        const val SETTING_AUDIO_FOCUS = "audioFocus"
        const val SETTING_MEDIA_STREAM = "mediaStream"
        const val SETTING_NAVIGATION_STREAM = "navigationStream"
        const val SETTING_MUSIC_BUFFER = "musicBuffer"
        const val SETTING_ADVANCED_AUDIO = "advancedAudio"
        const val SETTING_LOCATION_REPORTING = "locationReporting"

        /** CarPlay sizes, as the assumed screen width in mm: wider means smaller icons and text. */
        const val SIZE_LARGE = 250
        const val SIZE_MEDIUM = 300
        const val SIZE_SMALL = 350

        /** Identity files are a few hundred bytes; the companion refuses anything over this. */
        const val MAX_IDENTITY_FILE_BYTES = 16 * 1024

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

        /** Whether RevvCarPlay is installed and this Android can host its view (11+). */
        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && serviceIntent(context) != null

        /** RevvCarPlay's package (release or debug), or null when it isn't installed. */
        fun packageName(context: Context): String? = serviceIntent(context)?.component?.packageName

        /** Opens RevvCarPlay's own screen, for its one-time permission and VPN prompts. */
        fun launchIntent(context: Context): Intent? {
            val pkg = serviceIntent(context)?.component?.packageName ?: return null
            return context.packageManager.getLaunchIntentForPackage(pkg)
        }

        /** Android's "Modify system settings" page for RevvCarPlay, which lets it turn the hotspot on. */
        fun writeSettingsIntent(context: Context): Intent? {
            val pkg = serviceIntent(context)?.component?.packageName ?: return null
            return Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** Android's page for RevvCarPlay, where its permissions (location, nearby devices, microphone) are granted. */
        fun appInfoIntent(context: Context): Intent? {
            val pkg = serviceIntent(context)?.component?.packageName ?: return null
            return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        private fun serviceIntent(context: Context): Intent? {
            val intent = Intent(ACTION)
            val info = context.packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo ?: return null
            return intent.setClassName(info.packageName, info.name)
        }
    }
}
