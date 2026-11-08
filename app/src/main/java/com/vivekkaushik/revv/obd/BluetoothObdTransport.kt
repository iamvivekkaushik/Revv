package com.vivekkaushik.revv.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** An ELM327 reached over Bluetooth Classic's serial port profile, the way most adapters work. */
class BluetoothObdTransport private constructor(socket: BluetoothSocket) :
    StreamObdTransport(socket.inputStream, socket.outputStream, socket::close) {

    companion object {
        private val SERIAL_PORT = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Opens the serial channel to a paired [device], trying the ways cheap clones accept one
         * in turn and telling [trace] how each went. Needs BLUETOOTH_CONNECT on Android 12+;
         * callers check it first.
         */
        @SuppressLint("MissingPermission")
        fun connect(bluetooth: BluetoothAdapter, device: BluetoothDevice, trace: (String) -> Unit = {}): BluetoothObdTransport {
            // Discovery slows connections down; stopping it needs a permission Revv may lack.
            runCatching { bluetooth.cancelDiscovery() }
            val ways = listOf<Pair<String, () -> BluetoothSocket>>(
                "encrypted serial port" to { device.createRfcommSocketToServiceRecord(SERIAL_PORT) },
                // Plenty of cheap clones only accept unencrypted connections.
                "unencrypted serial port" to { device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT) },
                // Others publish no service record Android can read, yet answer on channel 1.
                "channel 1" to { channelOne(device) },
            )
            var failure: Exception? = null
            for ((way, open) in ways) {
                val socket = try {
                    open()
                } catch (e: Exception) {
                    trace("Bluetooth, $way: unavailable (${e.javaClass.simpleName})")
                    continue
                }
                try {
                    connectWithin(socket, CONNECT_MILLIS)
                    trace("Bluetooth connected by $way")
                    return BluetoothObdTransport(socket)
                } catch (e: IOException) {
                    trace("Bluetooth, $way: ${e.message ?: "failed"}")
                    runCatching { socket.close() }
                    failure = e
                }
            }
            throw IOException("Couldn't reach ${device.address}. Is the adapter plugged in?", failure)
        }

        /**
         * Android gives up on an adapter that doesn't answer within seconds, but a vendor's Bluetooth
         * behind Android's API may never answer at all (FYT units relay to their own stack), and
         * connect() has no timeout of its own. Closing the socket is what makes it give up.
         */
        private fun connectWithin(socket: BluetoothSocket, millis: Long) {
            // Whoever settles first, the connection or the watchdog, decides how it went.
            val settled = AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    Thread.sleep(millis)
                    if (settled.compareAndSet(false, true)) runCatching { socket.close() }
                } catch (e: InterruptedException) {
                    // Connected, or failed, in time.
                }
            }.apply {
                isDaemon = true
                name = "OBD connect timeout"
                start()
            }
            val timeout = { IOException("no answer in ${millis / 1000} s") }
            try {
                socket.connect()
            } catch (e: IOException) {
                if (!settled.compareAndSet(false, true)) throw timeout().initCause(e)
                throw e
            } catch (e: RuntimeException) {
                // FYT units' patched Bluetooth answers a connect it can't make with an unchecked
                // "Bad file descriptor" out of the binder call, which used to take Revv down.
                if (!settled.compareAndSet(false, true)) throw timeout().initCause(e)
                throw IOException("this head unit's Bluetooth refused it (${e.message ?: e.javaClass.simpleName})", e)
            } finally {
                watchdog.interrupt()
            }
            if (!settled.compareAndSet(false, true)) throw timeout()
        }

        private const val CONNECT_MILLIS = 15_000L

        /** Android's hidden channel-number socket, the usual workaround for such clones. */
        private fun channelOne(device: BluetoothDevice): BluetoothSocket =
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
    }
}
