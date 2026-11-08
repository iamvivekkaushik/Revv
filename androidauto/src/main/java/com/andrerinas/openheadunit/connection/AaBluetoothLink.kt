package com.andrerinas.openheadunit.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream

/**
 * One phone's Android Auto Bluetooth channel, the one the WiFi credentials go over: an RFCOMM
 * socket on Android's own Bluetooth, or a vendor stack's stand-in for one ([FytAutoSerial]).
 */
interface AaBluetoothLink {
    /** The phone's Bluetooth address, AA:BB:CC:DD:EE:FF. */
    val address: String

    /** The phone's Bluetooth name, when the channel knows it. */
    val name: String?
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

/** The channel as an RFCOMM socket the phone opened to Android's Bluetooth. */
class RfcommAaLink(private val socket: BluetoothSocket) : AaBluetoothLink {
    override val address: String get() = socket.remoteDevice.address

    @get:SuppressLint("MissingPermission")
    override val name: String? get() = try { socket.remoteDevice.name } catch (e: SecurityException) { null }
    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream
    override fun close() = socket.close()
}
