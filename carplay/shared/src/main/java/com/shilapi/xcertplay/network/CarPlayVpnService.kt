package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB CarPlay's link: the Android VPN tunnel and NCM IPv6 bridge to the iPhone, and the
 * [AirPlayListener] on it. VPN consent is requested with [prepare] before binding.
 *
 * Google Play allows VpnService only in VPN apps, so Revv's Play build leaves this service out of
 * its manifest and has no USB CarPlay ([available]); wireless CarPlay listens without it.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val airPlay = AirPlayListener()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            // The tunnel carries one thing, this app's link to the iPhone on fe80::/64. Android
            // puts every app under a VPN unless told otherwise, and blocks any address family the
            // tunnel has no address for as leak protection, which here meant all IPv4, i.e. the
            // whole head unit's internet, for as long as an iPhone was plugged in. So: only this
            // app is under the tunnel (other apps never see it), and IPv4 may bypass it, so the
            // app's own map tiles and routes still go out the normal network.
            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .allowFamily(OsConstants.AF_INET)
                .addAllowedApplication(packageName)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            airPlay.open(address, config, identity, pairings, mfi, listener, media) {
                // The listener stopped by itself and closed; the tunnel and bridge go with it.
                synchronized(this) {
                    if (generation == attachGeneration) releaseLocked()
                }
                stopSelf()
            }
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the AirPlay listener, the tunnel and the NCM bridge. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && airPlay.isOpen()

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = airPlay.boundPort()

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
                stopSelf()
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        airPlay.close()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        /** What Android's VPN notice calls the tunnel: say what it is for. */
        private const val SESSION_NAME = "Revv · USB CarPlay link"
        private const val TUN_MTU = 1500

        @Volatile private var declared: Boolean? = null

        /** Whether the app's manifest declares this service, i.e. whether USB CarPlay can run. */
        @Suppress("DEPRECATION") // getServiceInfo's flags overload needs API 33.
        fun available(context: Context): Boolean = declared ?: try {
            context.packageManager.getServiceInfo(ComponentName(context, CarPlayVpnService::class.java), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }.also { declared = it }

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
