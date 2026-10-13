package com.shilapi.xcertplay.network

import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The AirPlay TCP listener the iPhone connects to, and the sessions it accepts: on the wireless
 * link's own address, or inside [CarPlayVpnService]'s tunnel for USB CarPlay.
 */
class AirPlayListener {
    private data class Attachment(
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val onStopped: () -> Unit,
    )

    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: Attachment? = null
    private var serverSocket: ServerSocket? = null
    private var generation = 0

    /**
     * Listens on [address] until [close], replacing whatever listened before: on [config]'s port,
     * or another one when that is taken ([boundPort] says which). Should it stop by itself, it
     * closes, runs [onStopped] and tells [listener], on a thread of its own.
     */
    @Synchronized
    fun open(
        address: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        onStopped: () -> Unit = {},
    ) {
        if (active.get()) {
            Log.i(TAG, "replacing the AirPlay listener")
            closeLocked()
        }
        val server = AirPlayPortSelector.bind(address, config.port) { busy, bound ->
            Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
        }
        attachment = Attachment(config.copy(port = server.localPort), identity, pairings, mfi, listener, media, onStopped)
        serverSocket = server
        active.set(true)
        val opened = generation
        Thread(
            { acceptLoop(opened, server) },
            "airplay-accept",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun isOpen(): Boolean = active.get() && attachment != null

    /** Port the listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    /** Stops listening and closes the sessions it accepted. */
    @Synchronized
    fun close() {
        closeLocked()
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) stop(generation, error)
        }
    }

    /** Accepting failed while open: closes, unless reopened meanwhile, and tells the owner and the listener. */
    private fun stop(
        generation: Int,
        error: IOException,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val stopped = synchronized(this) {
                    if (generation != this.generation) return@Thread
                    attachment.also { closeLocked() }
                } ?: return@Thread
                stopped.onStopped()
                stopped.listener.onTransportError(message)
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessions() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    /** Caller must hold this listener's monitor. */
    private fun closeLocked() {
        generation += 1
        active.set(false)
        attachment = null
        serverSocket?.close()
        serverSocket = null
        closeSessions()
    }

    private companion object {
        const val TAG = "xcertplay-airplay"
    }
}
