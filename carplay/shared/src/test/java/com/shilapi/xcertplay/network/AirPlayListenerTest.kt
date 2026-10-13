package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AirPlayListenerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val identity = AirPlayIdentity.generate()

    @Test fun listensOnTheConfiguredPortUntilClosed() {
        val listener = AirPlayListener()
        val port = freePort()
        open(listener, port)
        assertTrue(listener.isOpen())
        assertEquals(port, listener.boundPort())
        Socket(loopback, port).close()

        listener.close()
        assertFalse(listener.isOpen())
        assertNull(listener.boundPort())
        assertRefused(port)
    }

    @Test fun openingAgainReplacesTheEarlierListener() {
        val listener = AirPlayListener()
        val first = freePort()
        open(listener, first)
        val second = freePort()
        open(listener, second)
        try {
            assertEquals(second, listener.boundPort())
            assertRefused(first)
            Socket(loopback, second).close()
        } finally {
            listener.close()
        }
    }

    @Test fun closingEndsTheSessionsItAccepted() {
        val listener = AirPlayListener()
        val port = freePort()
        open(listener, port)
        Socket(loopback, port).use { client ->
            client.soTimeout = 5_000
            // Accepted once the session is being served; then closing the listener hangs up on it.
            Thread.sleep(300)
            listener.close()
            val read = try {
                client.getInputStream().read()
            } catch (_: IOException) {
                -1
            }
            assertEquals(-1, read)
        }
    }

    private fun open(listener: AirPlayListener, port: Int) {
        listener.open(
            address = loopback,
            config = AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                port = port,
            ),
            identity = identity,
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
        )
    }

    private fun assertRefused(port: Int) {
        try {
            Socket(loopback, port).close()
            fail("port $port still accepts connections")
        } catch (_: IOException) {
        }
    }

    private fun freePort(): Int = ServerSocket(0, 0, loopback).use { it.localPort }
}
