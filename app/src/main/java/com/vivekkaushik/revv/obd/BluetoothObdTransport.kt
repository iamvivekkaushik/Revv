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
         * Opens the serial channel to a paired [device]. Needs BLUETOOTH_CONNECT on Android 12+;
         * callers check it first.
         */
        @SuppressLint("MissingPermission")
        fun connect(bluetooth: BluetoothAdapter, device: BluetoothDevice): BluetoothObdTransport {
            // Discovery slows connections down; stopping it needs a permission Revv may lack.
            runCatching { bluetooth.cancelDiscovery() }
            val secure = device.createRfcommSocketToServiceRecord(SERIAL_PORT)
            try {
                secure.connect()
                return BluetoothObdTransport(secure)
            } catch (e: IOException) {
                secure.close()
            }
            // Plenty of cheap clones only accept unencrypted connections.
            val insecure = device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT)
            try {
                insecure.connect()
            } catch (e: IOException) {
                insecure.close()
                throw IOException("Couldn't reach ${device.address}. Is the adapter plugged in?", e)
            }
            return BluetoothObdTransport(insecure)
        }
    }
}
