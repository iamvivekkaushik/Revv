package com.vivekkaushik.revv.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

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
                    socket.connect()
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

        /** Android's hidden channel-number socket, the usual workaround for such clones. */
        private fun channelOne(device: BluetoothDevice): BluetoothSocket =
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
    }
}
