package com.vivekkaushik.revv.carplay

import android.content.Context
import android.content.Intent
import android.util.Size
import android.widget.Toast
import com.shilapi.xcertplay.embed.CarPlayHost
import com.shilapi.xcertplay.embed.EmbedSettings
import com.shilapi.xcertplay.embed.EmbeddedCarPlay
import com.vivekkaushik.revv.nav.ProjectionGuidance
import com.vivekkaushik.revv.nav.Turn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * CarPlay, built into Revv from DiPlay's stack (carplay/). The Auto screen gives it a view and
 * CarPlay draws and takes touch there while the session itself runs in [CarPlayHost] for as long
 * as Revv does, so leaving the Auto screen keeps the music going. Its settings (identity, link,
 * iPhone, display, audio, location) are read and changed here too, for Settings › CarPlay. The
 * one-time prompts (permissions, the VPN consent for USB) are Revv's own: see
 * [missingPermissions], [vpnConsentIntent] and [setupChanged]. Main thread.
 */
class CarPlay(context: Context) : AutoCloseable {
    /** The session as the engine reports it. [phase] is one of the PHASE_* values. */
    data class State(
        val phase: String,
        val detail: String,
        val wireless: Boolean,
        /** [HOTSPOT_P2P] (Wi-Fi Direct) or [HOTSPOT_MANUAL] (the car's hotspot). */
        val hotspotMode: String,
        val videoActive: Boolean,
        /** What the driver must do first (SETUP_*), when [phase] is [PHASE_SETUP_REQUIRED]. */
        val missing: List<String>,
        /**
         * Stopped because another Wi-Fi Direct connection, such as screen mirroring, holds the
         * radio ([detail] names it): [resetWifiDirect] ends it and connects.
         */
        val resetWifiDirect: Boolean = false,
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

        /** As Revv's map shows any projection's route. */
        fun toProjection() = ProjectionGuidance(
            source = "CARPLAY",
            destination = destination,
            turn = turn,
            roundaboutExit = roundaboutExit,
            maneuverMeters = maneuverMeters,
            road = road,
            routeMeters = routeMeters,
            remainingSeconds = remainingSeconds,
            arrivalEpochSeconds = arrivalEpochSeconds,
        )

        private companion object {
            /** Apple's RoundaboutExit1 to RoundaboutExit19. */
            val ROUNDABOUT_EXITS = 28..46
        }
    }

    private val app = context.applicationContext

    private val _state = MutableStateFlow(stateOf(EmbeddedCarPlay.status))
    val state: StateFlow<State> = _state

    private val _settings = MutableStateFlow(EmbedSettings.snapshot(app))
    /** CarPlay's settings as last read; sizes and streams are the engine's own units. */
    val settings: StateFlow<EmbedSettings.Snapshot> = _settings

    private val _guidance = MutableStateFlow<Guidance?>(null)
    /** CarPlay's route while the iPhone guides one; null otherwise. */
    val guidance: StateFlow<Guidance?> = _guidance

    private val _hostUiRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The driver tapped Revv's icon in CarPlay, asking for Revv's own screen. */
    val hostUiRequests: SharedFlow<Unit> = _hostUiRequests

    private val host = CarPlayHost(app, object : CarPlayHost.Listener {
        override fun onStatus(status: EmbeddedCarPlay.Status) { _state.value = stateOf(status) }
        override fun onSettings(settings: EmbedSettings.Snapshot) { _settings.value = settings }
        override fun onGuidance(guidance: CarPlayHost.Guidance?) { _guidance.value = guidance?.let(::guidanceOf) }
        // How an import, a hotspot save or a report went.
        override fun onNotice(text: String, ok: Boolean) { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        override fun onHostUiRequested() { _hostUiRequests.tryEmit(Unit) }
        override fun releasesWifiDirectGroup(networkName: String) = this@CarPlay.releasesWifiDirectGroup?.invoke(networkName) == true
    })

    /**
     * Whether a Wi-Fi Direct group CarPlay did not make may go for CarPlay's own: Revv answers
     * yes for a group its Android Auto stack left behind while that session is off. Any thread.
     */
    @Volatile
    var releasesWifiDirectGroup: ((networkName: String) -> Boolean)? = null
    private var hostFocused = true
    private var viewSize: Size? = null

    override fun close() = host.close()

    /** Shows CarPlay in a view [width]x[height] px on a [screenWidth]x[screenHeight] px display turned by [rotation]. */
    fun attach(width: Int, height: Int, screenWidth: Int, screenHeight: Int, rotation: Int) {
        viewSize = Size(width, height)
        host.attach(Size(width, height), Size(screenWidth, screenHeight), rotation)
    }

    /** Asks for CarPlay again in the same view, after the session ended or failed. */
    fun retry() = host.retry()

    fun resize(width: Int, height: Int) {
        viewSize = Size(width, height)
        host.resize(Size(width, height), settled = hostFocused)
    }

    /**
     * Whether Revv's window has focus. Pulling the notification shade takes it and shows the system
     * bars, which shrinks Revv's layout for a moment; sizes sent meanwhile only letterbox CarPlay
     * instead of reconnecting it. On focus return the current size goes out again, settled.
     */
    fun setHostFocused(focused: Boolean) {
        if (hostFocused == focused) return
        hostFocused = focused
        viewSize?.let { host.resize(it, settled = focused) }
    }

    /** The view is gone. The CarPlay session keeps running until [stop]. */
    fun detach() {
        viewSize = null
        host.detach()
    }

    fun stop() = host.stop()

    /** No session is running or wanted: off, failed, or waiting on setup. */
    val isOff: Boolean
        get() = state.value.phase.let { it == PHASE_IDLE || it == PHASE_FAILED || it == PHASE_SETUP_REQUIRED }

    /** Whether [networkName] is a Wi-Fi Direct group CarPlay made on this head unit. */
    fun ownsWifiDirectGroup(networkName: String): Boolean = host.ownsWifiDirectGroup(networkName)

    fun siri() = host.siri()

    /** Reads the settings again, e.g. after a permission changed in Android's settings. */
    fun refreshSettings() = host.refreshSettings()

    /**
     * A permission or the VPN consent was answered: CarPlay looks again at what setup is missing
     * and starts when it is complete and the Auto screen shows it.
     */
    fun setupChanged() = host.setupChanged()

    /** The runtime permissions CarPlay still lacks for the chosen link, Siri, its notification and location reporting. */
    fun missingPermissions(): List<String> = host.missingPermissions()

    /** Android's VPN consent dialog USB CarPlay needs once; null once it was given. */
    fun vpnConsentIntent(): Intent? = host.vpnConsentIntent()

    /** Changes one setting (SETTING_*); a running session reconnects a moment after the last change. */
    fun setSetting(name: String, value: Int) { host.set(name, value) }

    fun setSetting(name: String, value: Boolean) { host.set(name, value) }

    /** Saves the car hotspot's details; wireless CarPlay then links over the car hotspot. */
    fun saveHotspot(ssid: String, passphrase: String) = host.saveHotspot(ssid, passphrase)

    /** The paired iPhone wireless CarPlay connects to. */
    fun choosePhone(address: String, name: String) { host.savePhone(address, name) }

    /** Picked files, name to bytes; identity.pk8 and certificate.p7b are found among them and installed. */
    fun importIdentity(files: Map<String, ByteArray>) = host.importIdentity(files)

    fun removeIdentity() = host.removeIdentity()

    /** Saves a diagnostic report to the head unit's Downloads folder. */
    fun saveReport() = host.saveReport()

    /** Turns the head unit's hotspot on now; choosing the car hotspot link and connecting over it do too. */
    fun turnOnHotspot() = host.turnOnHotspot()

    /**
     * Ends the head unit's other Wi-Fi Direct connection, whichever app made it, and connects
     * CarPlay. Only when the driver asks: it ends screen mirroring to a TV, for one.
     */
    fun resetWifiDirect() = host.resetWifiDirect()

    /** Chooses the link: USB ([wireless] false), or wireless over Wi-Fi Direct / the car hotspot. */
    fun configure(wireless: Boolean, hotspotMode: String? = null) = host.configure(wireless, hotspotMode)

    private fun stateOf(status: EmbeddedCarPlay.Status) = State(
        phase = when (status.phase) {
            EmbeddedCarPlay.Phase.IDLE -> PHASE_IDLE
            EmbeddedCarPlay.Phase.SETUP_REQUIRED -> PHASE_SETUP_REQUIRED
            EmbeddedCarPlay.Phase.STARTING -> PHASE_STARTING
            EmbeddedCarPlay.Phase.CONNECTING -> PHASE_CONNECTING
            EmbeddedCarPlay.Phase.CONNECTED -> PHASE_CONNECTED
            EmbeddedCarPlay.Phase.RECONNECTING -> PHASE_RECONNECTING
            EmbeddedCarPlay.Phase.FAILED -> PHASE_FAILED
        },
        detail = status.detail,
        wireless = status.wireless,
        hotspotMode = status.hotspotMode,
        videoActive = status.videoActive,
        missing = status.missing,
        resetWifiDirect = status.resetWifiDirect,
    )

    private fun guidanceOf(guidance: CarPlayHost.Guidance) = Guidance(
        destination = guidance.destination,
        routeMeters = guidance.routeMeters,
        maneuverType = guidance.maneuverType,
        maneuverMeters = guidance.maneuverMeters,
        road = guidance.road,
        leftHandTraffic = guidance.drivingSide == 1,
        arrivalEpochSeconds = guidance.arrivalEpochSeconds,
        remainingSeconds = guidance.remainingSeconds,
    )

    companion object {
        // Settings Revv may change (EmbedSettings).
        const val SETTING_CARPLAY_SIZE = EmbedSettings.SETTING_CARPLAY_SIZE
        const val SETTING_RESOLUTION = EmbedSettings.SETTING_RESOLUTION
        const val SETTING_FRAME_RATE = EmbedSettings.SETTING_FRAME_RATE
        const val SETTING_HEVC = EmbedSettings.SETTING_HEVC
        const val SETTING_RIGHT_HAND_DRIVE = EmbedSettings.SETTING_RIGHT_HAND_DRIVE
        const val SETTING_AUDIO_FOCUS = EmbedSettings.SETTING_AUDIO_FOCUS
        const val SETTING_MEDIA_STREAM = EmbedSettings.SETTING_MEDIA_STREAM
        const val SETTING_NAVIGATION_STREAM = EmbedSettings.SETTING_NAVIGATION_STREAM
        const val SETTING_MUSIC_BUFFER = EmbedSettings.SETTING_MUSIC_BUFFER
        const val SETTING_ADVANCED_AUDIO = EmbedSettings.SETTING_ADVANCED_AUDIO
        const val SETTING_LOCATION_REPORTING = EmbedSettings.SETTING_LOCATION_REPORTING

        /** CarPlay sizes, as the assumed screen width in mm: wider means smaller icons and text. */
        const val SIZE_LARGE = 250
        const val SIZE_MEDIUM = 300
        const val SIZE_SMALL = 350

        /** Identity files are a few hundred bytes; anything over this is not one. */
        const val MAX_IDENTITY_FILE_BYTES = EmbedSettings.MAX_FILE_BYTES

        const val PHASE_SETUP_REQUIRED = "setup_required"
        const val PHASE_IDLE = "idle"
        const val PHASE_STARTING = "starting"
        const val PHASE_CONNECTING = "connecting"
        const val PHASE_CONNECTED = "connected"
        const val PHASE_RECONNECTING = "reconnecting"
        const val PHASE_FAILED = "failed"

        const val SETUP_IDENTITY = EmbeddedCarPlay.SETUP_IDENTITY
        const val SETUP_VPN = EmbeddedCarPlay.SETUP_VPN
        const val SETUP_WIRELESS_PERMISSIONS = EmbeddedCarPlay.SETUP_WIRELESS_PERMISSIONS
        const val SETUP_HOTSPOT = EmbeddedCarPlay.SETUP_HOTSPOT

        const val HOTSPOT_P2P = EmbeddedCarPlay.HOTSPOT_P2P
        const val HOTSPOT_MANUAL = EmbeddedCarPlay.HOTSPOT_MANUAL
    }
}
