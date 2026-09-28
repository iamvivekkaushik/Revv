package com.vivekkaushik.revv.obd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** An ELM327 reached over TCP on the adapter's own Wi-Fi network. */
class WifiObdTransport private constructor(socket: Socket) :
    StreamObdTransport(socket.getInputStream(), socket.getOutputStream(), socket::close) {

    // A TCP read can time out, and returns -1 once the adapter drops the connection.
    override fun readAvailable(buffer: ByteArray): Int = try {
        input.read(buffer)
    } catch (e: SocketTimeoutException) {
        0
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val READ_POLL_MILLIS = 50

        /**
         * Connects over [network] specifically. An adapter's Wi-Fi has no internet, so Android
         * would otherwise send the connection out over mobile data.
         */
        fun connect(network: Network, endpoint: WifiEndpoint): WifiObdTransport {
            val socket = network.socketFactory.createSocket()
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = READ_POLL_MILLIS
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MILLIS)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return WifiObdTransport(socket)
        }
    }
}

object WifiNetworks {

    /**
     * The joined Wi-Fi network, including one Android has flagged as having no internet. If there
     * are two, the one without internet is the likelier adapter.
     */
    fun current(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return connectivity.allNetworks
            .filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .minByOrNull { if (hasInternet(context, it)) 1 else 0 }
    }

    /** Whether Android has verified the network reaches the internet, which an adapter's never does. */
    fun hasInternet(context: Context, network: Network): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** The network's IPv4 gateway. */
    fun gateway(context: Context, network: Network): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return connectivity.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
    }
}
