package com.vivekkaushik.revv.androidauto

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Size
import android.widget.Toast
import com.andrerinas.openheadunit.embed.AndroidAutoHost
import com.andrerinas.openheadunit.embed.EmbedSettings
import com.andrerinas.openheadunit.embed.EmbeddedAndroidAuto
import com.vivekkaushik.revv.MainActivity
import com.vivekkaushik.revv.nav.ProjectionGuidance
import com.vivekkaushik.revv.nav.Turn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android Auto, built into Revv from DiAuto's stack (androidauto/). The Auto screen gives it a
 * view and the phone draws and takes touch there while the session itself runs in
 * [AndroidAutoHost] for as long as Revv does, so leaving the Auto screen keeps the music going.
 * Its settings (wireless link, phone, display, audio, location) are read and changed here too, for
 * Settings › Android Auto. The one-time permission prompts are Revv's own: see
 * [missingPermissions] and [setupChanged]. Main thread.
 */
class AndroidAuto(context: Context) : AutoCloseable {
    /** The session as the engine reports it. [phase] is one of the PHASE_* values. */
    data class State(
        val phase: String,
        val detail: String,
        /** A wireless link is armed besides USB. */
        val wireless: Boolean,
        val videoActive: Boolean,
        /** The phone ended the session itself; CONNECT asks for it again. */
        val phoneExited: Boolean,
    )

    /**
     * The phone's directions while one of its apps guides a route: the next turn, how far it is,
     * and what is left. Android Auto names no destination, so Revv's map can show the turns but
     * not route there itself.
     */
    data class Guidance(
        /** The protocol's next-turn event and side; [turn] is the same as one of Revv's turns. */
        val event: Int,
        val side: Int,
        val roundaboutExit: Int?,
        val maneuverMeters: Int?,
        val road: String,
        val action: String,
        val routeMeters: Int?,
        val remainingSeconds: Long?,
    ) {
        /** The next manoeuvre as one of Revv's turns; null when the phone has none or it has no arrow. */
        val turn: Turn?
            get() {
                val left = side == SIDE_LEFT
                return when (event) {
                    EVENT_SLIGHT_TURN -> if (left) Turn.SlightLeft else Turn.SlightRight
                    EVENT_TURN -> if (left) Turn.Left else Turn.Right
                    EVENT_SHARP_TURN -> if (left) Turn.SharpLeft else Turn.SharpRight
                    EVENT_U_TURN -> if (left) Turn.UTurnLeft else Turn.UTurnRight
                    EVENT_ON_RAMP -> if (left) Turn.RampLeft else Turn.RampRight
                    EVENT_OFF_RAMP -> if (left) Turn.ExitLeft else Turn.ExitRight
                    EVENT_FORK, EVENT_MERGE -> if (left) Turn.KeepLeft else Turn.KeepRight
                    EVENT_ROUNDABOUT_ENTER, EVENT_ROUNDABOUT_ENTER_AND_EXIT -> Turn.Roundabout
                    EVENT_ROUNDABOUT_EXIT -> Turn.LeaveRoundabout
                    EVENT_STRAIGHT, EVENT_DEPART, EVENT_NAME_CHANGE -> Turn.Straight
                    EVENT_FERRY_BOAT, EVENT_FERRY_TRAIN -> Turn.Ferry
                    EVENT_DESTINATION -> Turn.Arrive
                    else -> null
                }
            }

        /** As Revv's map shows any projection's route. */
        fun toProjection() = ProjectionGuidance(
            source = "ANDROID AUTO",
            destination = "",
            turn = turn,
            roundaboutExit = roundaboutExit,
            maneuverMeters = maneuverMeters ?: 0,
            road = road,
            routeMeters = routeMeters?.toLong(),
            remainingSeconds = remainingSeconds,
            arrivalEpochSeconds = null,
        )

        private companion object {
            // NextTurnDetail.NextEvent and Side, as the Android Auto protocol numbers them.
            const val SIDE_LEFT = 1
            const val EVENT_DEPART = 1
            const val EVENT_NAME_CHANGE = 2
            const val EVENT_SLIGHT_TURN = 3
            const val EVENT_TURN = 4
            const val EVENT_SHARP_TURN = 5
            const val EVENT_U_TURN = 6
            const val EVENT_ON_RAMP = 7
            const val EVENT_OFF_RAMP = 8
            const val EVENT_FORK = 9
            const val EVENT_MERGE = 10
            const val EVENT_ROUNDABOUT_ENTER = 11
            const val EVENT_ROUNDABOUT_EXIT = 12
            const val EVENT_ROUNDABOUT_ENTER_AND_EXIT = 13
            const val EVENT_STRAIGHT = 14
            const val EVENT_FERRY_BOAT = 16
            const val EVENT_FERRY_TRAIN = 17
            const val EVENT_DESTINATION = 18
        }
    }

    private val app = context.applicationContext

    private val _state = MutableStateFlow(stateOf(EmbeddedAndroidAuto.status))
    val state: StateFlow<State> = _state

    private val _settings = MutableStateFlow(EmbedSettings.snapshot(app))
    /** Android Auto's settings as last read. */
    val settings: StateFlow<EmbedSettings.Snapshot> = _settings

    private val _guidance = MutableStateFlow<Guidance?>(null)
    /** The phone's directions while it guides a route; null otherwise. */
    val guidance: StateFlow<Guidance?> = _guidance

    private val _projectionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The phone's picture is ready while no view shows it: Revv should open the Auto screen. */
    val projectionRequests: SharedFlow<Unit> = _projectionRequests

    private val _exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The driver tapped Exit in Android Auto on the phone, asking for Revv's own screen. */
    val exitRequests: SharedFlow<Unit> = _exitRequests

    private val host = AndroidAutoHost(app, ::openAutoIntent, object : AndroidAutoHost.Listener {
        override fun onStatus(status: EmbeddedAndroidAuto.Status) { _state.value = stateOf(status) }
        override fun onSettings(settings: EmbedSettings.Snapshot) { _settings.value = settings }
        override fun onGuidance(guidance: AndroidAutoHost.Guidance?) { _guidance.value = guidance?.let(::guidanceOf) }
        override fun onNotice(text: String, ok: Boolean) { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        override fun onProjectionWanted() { _projectionRequests.tryEmit(Unit) }
        override fun onPhoneExited() { _exitRequests.tryEmit(Unit) }
        override fun releasesWifiDirectGroup(networkName: String) = this@AndroidAuto.releasesWifiDirectGroup?.invoke(networkName) == true
        override fun allowsBluetoothAutoStart() = this@AndroidAuto.allowsBluetoothAutoStart?.invoke() != false
    })

    /** Whether the chosen phone connecting over Bluetooth may start the session: Revv says yes while it shows Android Auto. Any thread. */
    @Volatile
    var allowsBluetoothAutoStart: (() -> Boolean)? = null

    /**
     * Whether a Wi-Fi Direct group Android Auto did not make may go for its own: Revv answers
     * yes for a group its CarPlay stack left behind while that session is off. Main thread.
     */
    @Volatile
    var releasesWifiDirectGroup: ((networkName: String) -> Boolean)? = null
    private var viewSize: Size? = null

    override fun close() = host.close()

    /** Shows Android Auto in a view [width]x[height] px on a [screenWidth]x[screenHeight] px display. */
    fun attach(width: Int, height: Int, screenWidth: Int, screenHeight: Int, metrics: DisplayMetrics) {
        viewSize = Size(width, height)
        host.attach(Size(width, height), Size(screenWidth, screenHeight), metrics)
    }

    /** Asks for a phone again in the same view: starts the session, or wakes the phone it waits for over Bluetooth. */
    fun retry() = host.retry()

    /**
     * Listens for the phone before the Auto screen opens: with a wireless link and a phone chosen,
     * the session arms itself as Revv starts and wakes the phone, and the Auto screen opens when
     * it connects.
     */
    fun listenForPhone() {
        val settings = _settings.value
        if (settings.wireless == WIRELESS_OFF || settings.phoneAddress == null) return
        host.arm()
    }

    fun resize(width: Int, height: Int) {
        viewSize = Size(width, height)
        host.resize(Size(width, height))
    }

    /** The view is gone. The session keeps running until [stop]. */
    fun detach() {
        viewSize = null
        host.detach()
    }

    fun stop() = host.stop()

    /** No session is running or wanted: off or failed. */
    val isOff: Boolean
        get() = state.value.phase.let { it == PHASE_IDLE || it == PHASE_FAILED }

    /** Whether [networkName] is the Wi-Fi Direct group Android Auto last made on this head unit. */
    fun ownsWifiDirectGroup(networkName: String): Boolean = host.ownsWifiDirectGroup(networkName)

    /** Opens the phone's assistant, or closes it when it is listening. */
    fun assistant() = host.assistant()

    /** Reads the settings again, e.g. after a permission changed in Android's settings. */
    fun refreshSettings() = host.refreshSettings()

    /** A permission was answered: the wireless link is armed again with what it now has. */
    fun setupChanged() = host.setupChanged()

    /** The runtime permissions Android Auto still lacks for the wireless link, the assistant, its notification and the phone's GPS. */
    fun missingPermissions(): List<String> = host.missingPermissions()

    /** Changes one setting (SETTING_*); a running session reconnects a moment after the last change. */
    fun setSetting(name: String, value: Int) { host.set(name, value) }

    fun setSetting(name: String, value: Boolean) { host.set(name, value) }

    /** Chooses the wireless link (WIRELESS_*); USB stays on. */
    fun configure(wireless: Int) = host.configure(wireless)

    /** Saves the car hotspot's details; the wireless link then goes over the car hotspot. */
    fun saveHotspot(ssid: String, passphrase: String) = host.saveHotspot(ssid, passphrase)

    /** The paired phone the wireless link wakes. */
    fun choosePhone(address: String, name: String) { host.savePhone(address, name) }

    /** Saves a diagnostic report to the head unit's Downloads folder. */
    fun saveReport() = host.saveReport()

    private fun stateOf(status: EmbeddedAndroidAuto.Status) = State(
        phase = when (status.phase) {
            EmbeddedAndroidAuto.Phase.IDLE -> PHASE_IDLE
            EmbeddedAndroidAuto.Phase.STARTING -> PHASE_STARTING
            EmbeddedAndroidAuto.Phase.WAITING -> PHASE_WAITING
            EmbeddedAndroidAuto.Phase.CONNECTING -> PHASE_CONNECTING
            EmbeddedAndroidAuto.Phase.CONNECTED -> PHASE_CONNECTED
            EmbeddedAndroidAuto.Phase.RECONNECTING -> PHASE_RECONNECTING
            EmbeddedAndroidAuto.Phase.FAILED -> PHASE_FAILED
        },
        detail = status.detail,
        wireless = status.wireless,
        videoActive = status.videoActive,
        phoneExited = status.phoneExited,
    )

    private fun guidanceOf(guidance: AndroidAutoHost.Guidance) = Guidance(
        event = guidance.event,
        side = guidance.side,
        roundaboutExit = guidance.roundaboutExit,
        maneuverMeters = guidance.distanceMeters,
        road = guidance.road,
        action = guidance.action,
        routeMeters = guidance.remainingMeters,
        remainingSeconds = guidance.remainingSeconds,
    )

    companion object {
        // Settings Revv may change (EmbedSettings).
        const val SETTING_RESOLUTION = EmbedSettings.SETTING_RESOLUTION
        const val SETTING_FRAME_RATE = EmbedSettings.SETTING_FRAME_RATE
        const val SETTING_SIZE = EmbedSettings.SETTING_SIZE
        const val SETTING_CODEC = EmbedSettings.SETTING_CODEC
        const val SETTING_RIGHT_HAND_DRIVE = EmbedSettings.SETTING_RIGHT_HAND_DRIVE
        const val SETTING_MUSIC_VIA_BLUETOOTH = EmbedSettings.SETTING_MUSIC_VIA_BLUETOOTH
        const val SETTING_FOCUS_MODE = EmbedSettings.SETTING_FOCUS_MODE
        const val SETTING_GPS_TO_PHONE = EmbedSettings.SETTING_GPS_TO_PHONE
        const val SETTING_NIGHT_MODE = EmbedSettings.SETTING_NIGHT_MODE
        const val SETTING_ECHO_CANCEL = EmbedSettings.SETTING_ECHO_CANCEL
        const val SETTING_NOISE_SUPPRESSION = EmbedSettings.SETTING_NOISE_SUPPRESSION

        const val WIRELESS_OFF = EmbedSettings.WIRELESS_OFF
        const val WIRELESS_WIFI_DIRECT = EmbedSettings.WIRELESS_WIFI_DIRECT
        const val WIRELESS_CAR_HOTSPOT = EmbedSettings.WIRELESS_CAR_HOTSPOT

        /** Sizes of the phone's icons and text, in percent of the display's density. */
        val SIZES = EmbedSettings.SIZES
        val RESOLUTIONS = EmbedSettings.RESOLUTIONS
        val FRAME_RATES = EmbedSettings.FRAME_RATES

        const val PHASE_IDLE = "idle"
        const val PHASE_STARTING = "starting"
        const val PHASE_WAITING = "waiting"
        const val PHASE_CONNECTING = "connecting"
        const val PHASE_CONNECTED = "connected"
        const val PHASE_RECONNECTING = "reconnecting"
        const val PHASE_FAILED = "failed"

        /** What is wrong with these hotspot details, or null when they are usable. */
        fun hotspotError(name: String, password: String): String? = EmbedSettings.hotspotError(name, password)

        /** Marks an intent to MainActivity as asking for the Auto screen with Android Auto up. */
        const val EXTRA_OPEN_AUTO = "com.vivekkaushik.revv.extra.OPEN_ANDROID_AUTO"

        /** The intent the session's notification opens: Revv with its Auto screen up. */
        fun openAutoIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(EXTRA_OPEN_AUTO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
