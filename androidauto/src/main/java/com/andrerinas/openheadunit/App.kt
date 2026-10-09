package com.andrerinas.openheadunit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapNavigation
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * The stack's singletons, as DiAuto's Application subclass held them. Revv is not that
 * Application, so the host sets the stack up once with [init]; everything in the stack keeps
 * asking [provide] for the component, as it always has.
 */
object App {
    /** The session's own notification. */
    const val defaultChannel = "android_auto_service"
    val appStartTime = SystemClock.elapsedRealtime()

    @Volatile
    private var component: AppComponent? = null
    private var receiverRegistered = false

    /**
     * How the stack reaches the host app's own screen, from the session's notifications: the
     * intent that opens the host with its Android Auto screen up. Set by the host in [init].
     */
    @Volatile
    var hostIntent: ((Context) -> Intent)? = null

    /** The phone asked for its picture to be shown (video focus); the host should show the session's view. */
    @Volatile
    var onProjectionRequested: (() -> Unit)? = null

    /** The transport handshake finished; the host attaches its surface and starts reading. */
    @Volatile
    var onHandshakeComplete: (() -> Unit)? = null

    /**
     * The driver tapped Exit in Android Auto: the phone asked for the head unit's own screen
     * (native video focus) and keeps the session up. The host shows its own screen; its view
     * coming back asks the phone for its picture again.
     */
    @Volatile
    var onNativeUiRequested: (() -> Unit)? = null

    /**
     * Whether the saved phone connecting over Bluetooth may start the session by itself (the
     * host shows Android Auto, say). Null or true lets it; false leaves it to the host's screens.
     */
    @Volatile
    var allowsBluetoothAutoStart: (() -> Boolean)? = null

    private var lastPhoneWakeMs = 0L

    /**
     * Whether to wake the chosen phone over Bluetooth now. One wake per [PHONE_WAKE_GAP_MS] from
     * all who ask (the host's screens, the Bluetooth receiver): a wake raises the very Bluetooth
     * connection the receiver listens for, and a second poke in the middle of the first's
     * handshake would disturb it.
     */
    @Synchronized
    fun mayWakePhone(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPhoneWakeMs < PHONE_WAKE_GAP_MS) return false
        lastPhoneWakeMs = now
        return true
    }

    private const val PHONE_WAKE_GAP_MS = 20_000L

    /**
     * Whether a Wi-Fi Direct group this stack did not make may be removed for its own: true when
     * another part of the host app made it and is done with it (Revv's CarPlay stack, say). Null
     * or false leaves the group alone, as another app's.
     */
    @Volatile
    var releasesWifiDirectGroup: ((networkName: String) -> Boolean)? = null

    /** Prepares the stack: its logging, notification channels and the in-process broadcasts. Idempotent. */
    fun init(context: Context, hostIntent: (Context) -> Intent) {
        val app = context.applicationContext
        this.hostIntent = hostIntent
        provide(app)
        if (receiverRegistered) return
        receiverRegistered = true
        if (ConscryptInitializer.isNeededForTls12()) ConscryptInitializer.initialize()
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(defaultChannel, app.getString(R.string.aa_service_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(BackgroundNotification.mediaChannel, app.getString(R.string.aa_media_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
        AapNavigation.createNotificationChannel(app)
        ContextCompat.registerReceiver(app, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** The stack's singletons, made on first use. */
    fun provide(context: Context): AppComponent {
        component?.let { return it }
        synchronized(this) {
            component?.let { return it }
            val app = context.applicationContext
            val made = AppComponent(app)
            AppLog.init(made.settings, app)
            component = made
            return made
        }
    }

    /** A pending intent to the host's Android Auto screen, for the session's notifications. */
    fun hostPendingIntent(context: Context, requestCode: Int): PendingIntent? {
        val intent = hostIntent?.invoke(context) ?: return null
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The settings the stack reads; a shorthand for [provide]. */
    fun settings(context: Context): Settings = provide(context).settings
}
