package com.shilapi.xcertplay.embed

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AudioChannelPreview
import com.shilapi.xcertplay.DiagnosticExportStore
import com.shilapi.xcertplay.DiagnosticReport
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.HotspotSwitch
import com.shilapi.xcertplay.network.WifiP2pGroupManager

/**
 * CarPlay for the app's own screen: the one place Revv's Auto screen and Settings › CarPlay talk
 * to. It runs [EmbeddedCarPlay] for an [EmbeddedCarPlayView] of the size the app gives it, reads
 * and changes the settings through [EmbedSettings], follows CarPlay's route guidance, and tells
 * [listener] about all of it. One per process, alive for as long as the app runs, so the session
 * keeps going while the app shows another screen. Main thread.
 */
class CarPlayHost(context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        /** The session's status, on every change and once when the host is made. */
        fun onStatus(status: EmbeddedCarPlay.Status)
        /** Every setting's value, after each change and whenever asked. */
        fun onSettings(settings: EmbedSettings.Snapshot)
        /** CarPlay's route while the iPhone guides one, null otherwise. */
        fun onGuidance(guidance: Guidance?)
        /** A line for the driver about an import, a hotspot save or a report. */
        fun onNotice(text: String, ok: Boolean)
        /** The driver tapped the car's icon in CarPlay, asking for the app's own screen. */
        fun onHostUiRequested()

        /**
         * Whether the Wi-Fi Direct group [networkName], which this stack did not make, may be
         * removed for its own: true when another part of the app made it and is done with it.
         * False leaves it alone, as another app's, and the session asks for a reset instead.
         */
        fun releasesWifiDirectGroup(networkName: String): Boolean = false
    }

    /**
     * CarPlay's route guidance as iAP2 reports it: the destination's name as Apple Maps shows it
     * (CarPlay never sends its coordinates), what is left, the arrival and the next maneuver
     * ([maneuverType] is Apple's RouteGuidanceManeuverType, [drivingSide] 1 for left-hand
     * traffic). Numbers are null when unknown.
     */
    data class Guidance(
        val destination: String,
        val routeMeters: Long?,
        val maneuverType: Int?,
        val maneuverMeters: Int,
        val road: String,
        val drivingSide: Int,
        val arrivalEpochSeconds: Long?,
        val remainingSeconds: Long?,
    ) {
        /** A route is being guided. A route the iPhone stops updating counts as ended. */
        val isRoute: Boolean get() = destination.isNotEmpty() || maneuverType != null

        internal companion object {
            fun of(snapshot: CarPlayGlance.Snapshot) = Guidance(
                destination = snapshot.destination.trim(),
                routeMeters = snapshot.routeMeters,
                maneuverType = snapshot.maneuverType,
                maneuverMeters = snapshot.distanceMeters,
                road = snapshot.road,
                drivingSide = snapshot.drivingSide,
                arrivalEpochSeconds = snapshot.arrivalEpochSeconds,
                remainingSeconds = snapshot.remainingSeconds,
            )
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val statusListener: (EmbeddedCarPlay.Status) -> Unit = { status -> listener.onStatus(status) }
    private val glanceListener: (CarPlayGlance.Snapshot) -> Unit = { snapshot -> main.post { publishGuidance(snapshot) } }
    private var lastGuidance: Guidance? = null
    // A route goes stale when the iPhone stops updating it without ending it; looking again notices.
    private val refreshGuidance = object : Runnable {
        override fun run() {
            CarPlayGlance.snapshot()
            main.postDelayed(this, GUIDANCE_REFRESH_MILLIS)
        }
    }
    private val preview by lazy {
        AudioChannelPreview { channel -> listener.onNotice(app.getString(R.string.contrib_audio_home_channel_preview_unavailable, channel), ok = false) }
    }
    private var previewing = false
    private var closed = false

    private var attached = false
    private var view = Size(0, 0)
    private var screen = Size(0, 0)
    private var rotation = Surface.ROTATION_0

    init {
        EmbeddedCarPlay.onHostUiRequested = { listener.onHostUiRequested() }
        WifiP2pGroupManager.releasesForeignGroup = { listener.releasesWifiDirectGroup(it) }
        EmbeddedCarPlay.addListener(statusListener)
        CarPlayGlance.addListener(glanceListener)
        main.postDelayed(refreshGuidance, GUIDANCE_REFRESH_MILLIS)
        publishGuidance(CarPlayGlance.snapshot(), force = true)
    }

    override fun close() {
        if (closed) return
        closed = true
        detach()
        EmbeddedCarPlay.onHostUiRequested = null
        WifiP2pGroupManager.releasesForeignGroup = null
        EmbeddedCarPlay.removeListener(statusListener)
        CarPlayGlance.removeListener(glanceListener)
        main.removeCallbacks(refreshGuidance)
        if (previewing) preview.close()
    }

    // --- The view ------------------------------------------------------------------------------

    /**
     * Shows CarPlay in a view of [view] pixels on a [screen]-pixel display, starting the session or
     * keeping a running one, and reads the settings for the listener.
     */
    fun attach(view: Size, screen: Size, rotation: Int) {
        if (!attached) {
            attached = true
            EmbeddedCarPlay.hostViews.incrementAndGet()
        }
        this.view = view
        this.screen = screen
        this.rotation = rotation
        EmbeddedCarPlay.start(app, view, screen, rotation)
        sendSettings()
    }

    /** Asks for CarPlay again in the attached view, after the session ended or failed. */
    fun retry() {
        if (!attached) return
        EmbeddedCarPlay.start(app, view, screen, rotation)
    }

    /**
     * The view changed size. A [settled] size reconnects CarPlay at it after a short pause; an
     * unsettled one (the window lost focus, say to the notification shade, which also shows the
     * system bars) only letterboxes the picture until the window settles again.
     */
    fun resize(view: Size, settled: Boolean) {
        if (!attached) return
        this.view = view
        EmbeddedCarPlay.resize(view, settled)
    }

    /** The view is gone. The session keeps running (audio continues) until [stop]. */
    fun detach() {
        if (!attached) return
        attached = false
        EmbeddedCarPlay.hostViews.decrementAndGet()
    }

    /** Ends the CarPlay session. */
    fun stop() = EmbeddedCarPlay.stop()

    fun siri() {
        EmbeddedCarPlay.requestSiri()
    }

    // --- Setup ---------------------------------------------------------------------------------

    /**
     * The runtime permissions CarPlay still lacks: Bluetooth and nearby devices for wireless
     * CarPlay (which cannot start without them), the microphone for Siri and calls, notifications
     * for the connection's notification, and precise location when the iPhone is to get the
     * head unit's position.
     */
    fun missingPermissions(): List<String> {
        val wanted = mutableListOf<String>()
        if (AirPlayPersistence.loadWirelessEnabled(app)) wanted += EmbeddedCarPlay.requiredWirelessPermissions()
        wanted += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (AirPlayPersistence.loadLocationReportingEnabled(app)) wanted += Manifest.permission.ACCESS_FINE_LOCATION
        return wanted.distinct().filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    /** Android's VPN consent dialog wired CarPlay needs once, or null when it was given already. */
    fun vpnConsentIntent(): Intent? = CarPlayVpnService.prepare(app)

    /**
     * A permission or the VPN consent was answered: looks again at what setup is missing. When
     * setup is now complete and a view shows CarPlay, the session starts.
     */
    fun setupChanged() {
        val wasSetup = EmbeddedCarPlay.status.phase == EmbeddedCarPlay.Phase.SETUP_REQUIRED
        EmbeddedCarPlay.settingsChanged(app, reconnect = false)
        if (wasSetup && EmbeddedCarPlay.status.phase == EmbeddedCarPlay.Phase.IDLE) retry()
        sendSettings()
    }

    // --- Settings ------------------------------------------------------------------------------

    /** Every setting's value now; [Listener.onSettings] gets the same after each change. */
    fun settings(): EmbedSettings.Snapshot = EmbedSettings.snapshot(app)

    fun refreshSettings() = sendSettings()

    /**
     * Chooses the link: USB ([wireless] false), or wireless over Wi-Fi Direct
     * ([EmbeddedCarPlay.HOTSPOT_P2P]) or the car hotspot ([EmbeddedCarPlay.HOTSPOT_MANUAL]). A
     * running session reconnects over it.
     */
    fun configure(wireless: Boolean, hotspotMode: String? = null) {
        EmbeddedCarPlay.configure(app, wireless, hotspotMode, ::hotspotTurned)
        sendSettings()
    }

    /** Saves one setting (EmbedSettings.SETTING_*); a running session reconnects a moment after the last change. */
    fun set(name: String, value: Any?): Boolean {
        if (!EmbedSettings.set(app, name, value)) return false
        // Choosing an audio stream plays a short tone on it, so the driver hears where it goes.
        if (name == EmbedSettings.SETTING_MEDIA_STREAM || name == EmbedSettings.SETTING_NAVIGATION_STREAM) {
            previewing = true
            preview.play(value as Int, navigation = name == EmbedSettings.SETTING_NAVIGATION_STREAM)
        }
        EmbeddedCarPlay.settingsChanged(app, reconnect = true)
        sendSettings()
        return true
    }

    /** Saves the car hotspot's details and links over the car hotspot; a notice says whether they were usable. */
    fun saveHotspot(ssid: String, passphrase: String) {
        val error = EmbedSettings.saveHotspot(app, ssid, passphrase)
        if (error != null) {
            listener.onNotice(error, ok = false)
        } else {
            EmbeddedCarPlay.configure(app, wireless = true, hotspotMode = EmbeddedCarPlay.HOTSPOT_MANUAL, onHotspot = ::hotspotTurned)
            listener.onNotice(app.getString(R.string.hotspot_details_saved), ok = true)
        }
        sendSettings()
    }

    /** The paired iPhone wireless CarPlay connects to; false for an address that isn't a Bluetooth one. */
    fun savePhone(address: String, name: String): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        EmbedSettings.savePhone(app, address, name)
        EmbeddedCarPlay.settingsChanged(app, reconnect = AirPlayPersistence.loadWirelessEnabled(app))
        sendSettings()
        return true
    }

    /** Picked files, name to bytes; identity.pk8 and certificate.p7b are found among them and installed. */
    fun importIdentity(files: Map<String, ByteArray>) {
        val (text, ok) = EmbedSettings.importIdentity(app, files)
        listener.onNotice(text, ok)
        if (ok) EmbeddedCarPlay.settingsChanged(app, reconnect = true)
        sendSettings()
    }

    /** Ends any session and removes the identity. */
    fun removeIdentity() = EmbeddedCarPlay.stop {
        val text = EmbedSettings.removeIdentity(app)
        EmbeddedCarPlay.settingsChanged(app, reconnect = false)
        listener.onNotice(text, ok = true)
        sendSettings()
    }

    /** Saves a diagnostic report to Downloads/Revv/CarPlay; a notice says where. */
    fun saveReport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listener.onNotice(app.getString(R.string.could_not_save_the_report), ok = false)
            return
        }
        val fileName = DiagnosticReport.fileName()
        Thread({
            val saved = runCatching { DiagnosticReport.saveToDownloads(app, fileName) }
                .onFailure { Log.w(TAG, "diagnostic report not saved", it) }
                .isSuccess
            main.post {
                if (saved) listener.onNotice("${app.getString(R.string.diagnostic_report_saved)} · Downloads/${DiagnosticExportStore.FOLDER}/$fileName", ok = true)
                else listener.onNotice(app.getString(R.string.could_not_save_the_report), ok = false)
            }
        }, "revv-carplay-report").start()
    }

    /** Turns the head unit's Wi-Fi hotspot on now; choosing the car hotspot link and every session over it do too. */
    fun turnOnHotspot() = HotspotSwitch.turnOn(app, ::hotspotTurned)

    /**
     * Ends the device's Wi-Fi Direct connection, whichever app made it (screen mirroring to a TV,
     * say), then connects CarPlay. Only when the driver asks, after a status with resetWifiDirect.
     */
    /** Whether [networkName] is a Wi-Fi Direct group this stack made on this head unit. */
    fun ownsWifiDirectGroup(networkName: String): Boolean = WifiP2pGroupManager.ownsGroup(app, networkName)

    fun resetWifiDirect() = EmbeddedCarPlay.resetWifiDirect(app) { cleared ->
        if (!cleared) listener.onNotice(app.getString(R.string.embed_wifi_direct_reset_failed), ok = false)
    }

    /** Says why the hotspot stayed off, and sends its state once Android has had time to bring it up. */
    private fun hotspotTurned(result: HotspotSwitch.Result) {
        EmbedSettings.hotspotNotice(app, result)?.let { listener.onNotice(it, ok = false) }
        sendSettings()
        if (result == HotspotSwitch.Result.ON) main.postDelayed({ sendSettings() }, HOTSPOT_SETTLE_MILLIS)
    }

    private fun sendSettings() {
        if (!closed) listener.onSettings(EmbedSettings.snapshot(app))
    }

    /** Main thread. Song changes also come through here; only a change to the route is passed on. */
    private fun publishGuidance(snapshot: CarPlayGlance.Snapshot, force: Boolean = false) {
        val guidance = Guidance.of(snapshot).takeIf { it.isRoute }
        if (!force && guidance == lastGuidance) return
        lastGuidance = guidance
        listener.onGuidance(guidance)
    }

    private companion object {
        const val TAG = "RevvCarPlay-Host"
        // Android reports tethering started a moment before the hotspot reads as on.
        const val HOTSPOT_SETTLE_MILLIS = 2_000L
        const val GUIDANCE_REFRESH_MILLIS = 5_000L
    }
}
