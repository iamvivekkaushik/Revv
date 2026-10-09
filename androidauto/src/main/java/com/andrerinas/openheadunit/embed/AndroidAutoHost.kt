package com.andrerinas.openheadunit.embed

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapNavigationHelper
import com.andrerinas.openheadunit.connection.WifiDirectManager
import com.andrerinas.openheadunit.contract.NavigationUpdateIntent
import com.andrerinas.openheadunit.utils.DiagnosticExportStore
import com.andrerinas.openheadunit.utils.DiagnosticReport

/**
 * Android Auto for the app's own screen: the one place Revv's Auto screen and Settings › Android
 * Auto talk to. It runs [EmbeddedAndroidAuto] for an [EmbeddedAndroidAutoView] of the size the
 * app gives it, reads and changes the settings through [EmbedSettings], follows the phone's
 * turn-by-turn directions, and tells [listener] about all of it. One per process, alive for as
 * long as the app runs, so the session keeps going while the app shows another screen. Main thread.
 */
class AndroidAutoHost(context: Context, hostIntent: (Context) -> Intent, private val listener: Listener) : AutoCloseable {
    interface Listener {
        /** The session's status, on every change and once when the host is made. */
        fun onStatus(status: EmbeddedAndroidAuto.Status)
        /** Every setting's value, after each change and whenever asked. */
        fun onSettings(settings: EmbedSettings.Snapshot)
        /** The phone's directions while one of its apps guides a route, null otherwise. */
        fun onGuidance(guidance: Guidance?)
        /** A line for the driver about a save or a report. */
        fun onNotice(text: String, ok: Boolean)
        /** The phone's picture is ready and no view shows it: the app should show the session's view. */
        fun onProjectionWanted()

        /** The driver tapped Exit in Android Auto on the phone: the session ended, and the app's own screen is wanted. */
        fun onPhoneExited() {}

        /**
         * Whether the Wi-Fi Direct group [networkName], which this stack did not make, may be
         * removed for its own: true when another part of the app made it and is done with it.
         * False leaves it alone, as another app's, and the driver is told to disconnect it.
         */
        fun releasesWifiDirectGroup(networkName: String): Boolean = false

        /** Whether the chosen phone connecting over Bluetooth may start the session by itself. */
        fun allowsBluetoothAutoStart(): Boolean = true
    }

    /**
     * The phone's directions as Android Auto sends them to an instrument cluster: the next
     * manoeuvre ([event] is the protocol's NextTurnDetail.NextEvent number, [side] its Side, 1
     * left, 2 right, 3 neither), how far it is, and what is left of the route. Numbers are null
     * when the phone did not say.
     */
    data class Guidance(
        val event: Int,
        val side: Int,
        val roundaboutExit: Int?,
        val distanceMeters: Int?,
        val road: String,
        val action: String,
        val remainingMeters: Int?,
        val remainingSeconds: Long?,
        /** The arrival time as the phone's app wrote it, else empty. */
        val arrival: String,
    )

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val statusListener: (EmbeddedAndroidAuto.Status) -> Unit = { status -> listener.onStatus(status) }
    private var lastGuidance: Guidance? = null
    private var closed = false
    private var attached = false
    private var view = Size(0, 0)
    private var screen = Size(0, 0)
    private var metrics: DisplayMetrics? = null
    private var lastMissingPermissions: List<String> = missingPermissions()
    // Stepping through a setting's options reconnects once, after the last step.
    private val applySettings = Runnable { EmbeddedAndroidAuto.reconnectForSettings() }

    init {
        App.init(app, hostIntent)
        EmbeddedAndroidAuto.onProjectionWanted = { listener.onProjectionWanted() }
        EmbeddedAndroidAuto.onPhoneExited = { listener.onPhoneExited() }
        App.releasesWifiDirectGroup = { listener.releasesWifiDirectGroup(it) }
        App.allowsBluetoothAutoStart = { listener.allowsBluetoothAutoStart() }
        // A phone plugged in starts the service before any view asks; its handshake is heard here.
        EmbeddedAndroidAuto.bind(app)
        EmbeddedAndroidAuto.addListener(statusListener)
        AapNavigationHelper.onNavigationUpdate = { update, active -> main.post { publishGuidance(update, active) } }
    }

    override fun close() {
        if (closed) return
        closed = true
        detach()
        EmbeddedAndroidAuto.onProjectionWanted = null
        EmbeddedAndroidAuto.onPhoneExited = null
        App.releasesWifiDirectGroup = null
        App.allowsBluetoothAutoStart = null
        EmbeddedAndroidAuto.removeListener(statusListener)
        AapNavigationHelper.onNavigationUpdate = null
        main.removeCallbacks(applySettings)
    }

    // --- The view ------------------------------------------------------------------------------

    /**
     * Shows Android Auto in a view of [view] pixels on a [screen]-pixel display, starting the
     * session or keeping a running one, and reads the settings for the listener.
     */
    fun attach(view: Size, screen: Size, metrics: DisplayMetrics) {
        if (!attached) {
            Log.i(TAG, "view attached at ${view.width}x${view.height}")
            attached = true
            EmbeddedAndroidAuto.hostViews.incrementAndGet()
        }
        this.view = view
        this.screen = screen
        this.metrics = metrics
        EmbeddedAndroidAuto.start(app, view, screen, metrics)
        sendSettings()
    }

    /** Asks for a phone again in the attached view, after the session ended or failed, or wakes the phone it waits for. */
    fun retry() {
        if (!attached) return
        EmbeddedAndroidAuto.retry()
    }

    /**
     * Listens for the phone before any view asks: the service arms the wireless link and wakes
     * the chosen phone, as a car's own head unit would as it starts. Only worth calling with a
     * wireless link and a phone chosen; the handshake then reaches [Listener.onProjectionWanted].
     */
    fun arm() = EmbeddedAndroidAuto.arm(app)

    /** The view changed size: the phone gets new margins, or reconnects when it needs another resolution. */
    fun resize(view: Size) {
        if (!attached) return
        this.view = view
        metrics?.let { EmbeddedAndroidAuto.resize(view, it) }
    }

    /** The view is gone. The session keeps running (audio continues) until [stop]. */
    fun detach() {
        if (!attached) return
        Log.i(TAG, "view detached")
        attached = false
        EmbeddedAndroidAuto.hostViews.decrementAndGet()
    }

    /** Ends the session: Android Auto is off until the view asks again. */
    fun stop() = EmbeddedAndroidAuto.stop()

    /** Whether [networkName] is a Wi-Fi Direct group this stack made on this head unit, now or in an earlier run. */
    fun ownsWifiDirectGroup(networkName: String): Boolean =
        WifiDirectManager.everOwnedName(app.getSharedPreferences(WifiDirectManager.OWNERSHIP_PREFS, Context.MODE_PRIVATE), networkName)

    fun assistant() {
        EmbeddedAndroidAuto.assistant()
    }

    // --- Setup ---------------------------------------------------------------------------------

    /**
     * The runtime permissions Android Auto still lacks: Bluetooth and nearby devices for the
     * wireless link (which cannot start without them), the microphone for the assistant and
     * calls, notifications for the connection's notification, and precise location when the
     * phone is to get the head unit's position.
     */
    fun missingPermissions(): List<String> {
        val settings = App.settings(app)
        val wanted = mutableListOf<String>()
        if (settings.wifiConnectionMode == 3) wanted += EmbedSettings.wirelessPermissions()
        wanted += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (settings.useGpsForNavigation) wanted += Manifest.permission.ACCESS_FINE_LOCATION
        return wanted.distinct().filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    /**
     * A permission may have been answered: the wireless link is armed again with what it now has,
     * but only when the permissions did change. Re-arming tears the Wi-Fi Direct group down and
     * hosts a new one, which would cut short a phone's handshake, and the app asks on every
     * return to its settings page.
     */
    fun setupChanged() {
        val missing = missingPermissions()
        if (missing != lastMissingPermissions) {
            Log.i(TAG, "permissions changed; re-arming the link")
            lastMissingPermissions = missing
            EmbeddedAndroidAuto.linkChanged()
        }
        sendSettings()
    }

    // --- Settings ------------------------------------------------------------------------------

    /** Every setting's value now; [Listener.onSettings] gets the same after each change. */
    fun settings(): EmbedSettings.Snapshot = EmbedSettings.snapshot(app)

    fun refreshSettings() = sendSettings()

    /** Chooses the wireless link (EmbedSettings.WIRELESS_*); USB stays on. The service re-arms at once. */
    fun configure(wireless: Int) {
        if (!EmbedSettings.set(app, EmbedSettings.SETTING_WIRELESS, wireless)) return
        EmbeddedAndroidAuto.linkChanged()
        sendSettings()
    }

    /** Saves one setting (EmbedSettings.SETTING_*); a running session reconnects a moment after the last change. */
    fun set(name: String, value: Any?): Boolean {
        if (!EmbedSettings.set(app, name, value)) return false
        main.removeCallbacks(applySettings)
        main.postDelayed(applySettings, SETTINGS_DEBOUNCE_MILLIS)
        sendSettings()
        return true
    }

    /** Saves the car hotspot's details and links over the car hotspot; a notice says whether they were usable. */
    fun saveHotspot(ssid: String, passphrase: String) {
        val error = EmbedSettings.saveHotspot(app, ssid, passphrase)
        if (error != null) {
            listener.onNotice(error, ok = false)
        } else {
            EmbeddedAndroidAuto.linkChanged()
            listener.onNotice("Details saved · Check that the car hotspot is on before connecting.", ok = true)
        }
        sendSettings()
    }

    /** The paired phone the wireless link wakes; false for an address that isn't a Bluetooth one. */
    fun savePhone(address: String, name: String): Boolean {
        if (!EmbedSettings.savePhone(app, address, name)) return false
        EmbeddedAndroidAuto.linkChanged()
        sendSettings()
        return true
    }

    /** Wakes the chosen phone over Bluetooth now, for a wireless link that is slow to come up. */
    fun wakePhone() {
        val address = App.settings(app).autoStartBluetoothDeviceMacs.firstOrNull() ?: return
        EmbeddedAndroidAuto.wakePhone(address)
    }

    /** Saves a diagnostic report to Downloads/Revv/Android Auto; a notice says where. */
    fun saveReport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listener.onNotice("Could not save the report", ok = false)
            return
        }
        val fileName = "android-auto-report-${System.currentTimeMillis()}.txt"
        Thread({
            val saved = runCatching {
                DiagnosticExportStore.saveToDownloads(app.contentResolver, fileName, DiagnosticReport.build(app))
            }.onFailure { Log.w(TAG, "diagnostic report not saved", it) }.isSuccess
            main.post {
                if (saved) listener.onNotice("Diagnostic report saved · Downloads/${DiagnosticExportStore.FOLDER}/$fileName", ok = true)
                else listener.onNotice("Could not save the report", ok = false)
            }
        }, "revv-androidauto-report").start()
    }

    private fun sendSettings() {
        if (!closed) listener.onSettings(EmbedSettings.snapshot(app))
    }

    /** Main thread. Only a change is passed on; the phone repeats its directions every second. */
    private fun publishGuidance(update: NavigationUpdateIntent, active: Boolean) {
        val guidance = if (!active) null else Guidance(
            event = update.getIntExtra(NavigationUpdateIntent.EXTRA_NEXT_EVENT_TYPE, 0),
            side = update.getIntExtra(NavigationUpdateIntent.EXTRA_TURN_SIDE, NavigationUpdateIntent.TURN_SIDE_UNSPECIFIED),
            roundaboutExit = update.getIntExtra(NavigationUpdateIntent.EXTRA_TURN_NUMBER, -1).takeIf { it > 0 },
            distanceMeters = update.getIntExtra(NavigationUpdateIntent.EXTRA_DISTANCE_METERS, -1).takeIf { it >= 0 },
            road = update.getStringExtra(NavigationUpdateIntent.EXTRA_ROAD).orEmpty().takeIf { it != "—" }.orEmpty(),
            action = update.getStringExtra(NavigationUpdateIntent.EXTRA_ACTION_TEXT).orEmpty(),
            remainingMeters = update.getIntExtra(NavigationUpdateIntent.EXTRA_TOTAL_DISTANCE_METERS, -1).takeIf { it >= 0 },
            remainingSeconds = update.getLongExtra(NavigationUpdateIntent.EXTRA_TOTAL_TIME_SECONDS, -1L).takeIf { it >= 0 },
            arrival = update.getStringExtra(NavigationUpdateIntent.EXTRA_ESTIMATED_ARRIVAL).orEmpty(),
        )
        if (guidance == lastGuidance) return
        lastGuidance = guidance
        listener.onGuidance(guidance)
    }

    private companion object {
        const val TAG = "RevvAndroidAuto-Host"
        const val SETTINGS_DEBOUNCE_MILLIS = 1_500L
    }
}
