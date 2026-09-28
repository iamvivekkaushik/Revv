package com.vivekkaushik.revv.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An ELM327 reached over Bluetooth LE. The adapter carries its serial link over GATT: replies
 * arrive as notifications, commands go out as characteristic writes.
 */
class BleObdTransport private constructor(private val link: GattLink, uart: BluetoothGattCharacteristic) :
    StreamObdTransport(
        link.incoming,
        ChunkedOutputStream(MAX_WRITE_BYTES) { chunk -> link.write(uart, chunk) },
        onClose = link::close,
    ) {

    override fun readAvailable(buffer: ByteArray): Int = link.incoming.poll(buffer, POLL_MILLIS)

    companion object {
        /** A write's payload on a link that hasn't negotiated a larger MTU; ELM327 commands fit easily. */
        private const val MAX_WRITE_BYTES = 20
        private const val POLL_MILLIS = 20L

        /**
         * Connects to [device], finds its serial characteristics and checks an ELM327 answers on
         * them. Needs BLUETOOTH_CONNECT on Android 12+; callers check it first.
         */
        fun connect(context: Context, device: BluetoothDevice): BleObdTransport {
            val link = GattLink.open(context, device)
            try {
                for (profile in BleUart.candidates(link.characteristics())) {
                    val notify = link.characteristic(profile.service, profile.notify) ?: continue
                    val write = link.characteristic(profile.service, profile.write) ?: continue
                    link.listen(notify)
                    val transport = BleObdTransport(link, write)
                    if (Elm327(transport).looksLikeElm327()) return transport
                }
            } catch (e: IOException) {
                link.close()
                throw e
            }
            link.close()
            throw IOException("${link.name} has no ELM327 serial service")
        }
    }
}

/**
 * The GATT connection behind a [BleObdTransport]. Android reports every GATT step through one
 * callback on binder threads; this turns them into blocking calls for the session's IO thread.
 */
@SuppressLint("MissingPermission")
internal class GattLink private constructor(private val device: BluetoothDevice) : BluetoothGattCallback() {

    private sealed interface Event {
        data object Connected : Event
        data class Disconnected(val status: Int) : Event
        data class ServicesDiscovered(val status: Int) : Event
        data class DescriptorWritten(val status: Int) : Event
        data class CharacteristicWritten(val status: Int) : Event
    }

    val incoming = ByteQueueInputStream()
    private val events = LinkedBlockingQueue<Event>()
    private var gatt: BluetoothGatt? = null

    val name: String get() = runCatching { device.name }.getOrNull() ?: device.address

    fun characteristics(): List<GattCharacteristicInfo> =
        requireGatt().services.flatMap { service ->
            service.characteristics.map { GattCharacteristicInfo(service.uuid, it.uuid, it.properties) }
        }

    fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        requireGatt().getService(service)?.getCharacteristic(characteristic)

    /** Subscribes to [characteristic]'s notifications (or indications, if that's all it offers). */
    fun listen(characteristic: BluetoothGattCharacteristic) {
        val gatt = requireGatt()
        if (!gatt.setCharacteristicNotification(characteristic, true)) throw IOException("Couldn't subscribe to the adapter")
        // Some modules notify without a configuration descriptor to write.
        val config = characteristic.getDescriptor(CLIENT_CONFIG) ?: return
        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(config, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            config.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(config)
        }
        if (!started) throw IOException("Couldn't subscribe to the adapter")
        await<Event.DescriptorWritten>(STEP_TIMEOUT_MILLIS)
    }

    /** Writes one chunk and waits until the stack has taken it; GATT allows one operation at a time. */
    fun write(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val gatt = requireGatt()
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) throw IOException("The adapter didn't accept a command")
        await<Event.CharacteristicWritten>(STEP_TIMEOUT_MILLIS)
    }

    fun close() {
        incoming.close()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            events.put(Event.Connected)
        } else {
            // Readers see the end of the stream and the session reconnects.
            incoming.close()
            events.put(Event.Disconnected(status))
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        events.put(Event.ServicesDiscovered(status))
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        events.put(Event.DescriptorWritten(status))
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        events.put(Event.CharacteristicWritten(status))
    }

    // Android 13+ passes the value directly; overriding this stops the deprecated version being called.
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        incoming.offer(value)
    }

    @Deprecated("Only called before Android 13")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        @Suppress("DEPRECATION")
        characteristic.value?.let(incoming::offer)
    }

    private fun requireGatt(): BluetoothGatt = gatt ?: throw IOException("The adapter disconnected")

    /** Waits for the next event of type [T], skipping stale ones; throws if the link drops or stalls. */
    private inline fun <reified T : Event> await(timeoutMillis: Long): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("The Bluetooth LE adapter stopped answering")
            when (val event = events.poll(remaining, TimeUnit.NANOSECONDS)) {
                is T -> return event
                is Event.Disconnected -> throw IOException("The adapter disconnected (status ${event.status})")
                else -> Unit
            }
        }
    }

    companion object {
        private val CLIENT_CONFIG: UUID = BleUart.uuid16(0x2902)
        private const val CONNECT_TIMEOUT_MILLIS = 12_000L
        private const val STEP_TIMEOUT_MILLIS = 5_000L

        fun open(context: Context, device: BluetoothDevice): GattLink {
            val link = GattLink(device)
            // Callbacks are thread-safe, so they can run straight on Bluetooth's own threads.
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                val settings = BluetoothGattConnectionSettings.Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutoConnectEnabled(false)
                    .build()
                device.connectGatt(settings, Runnable::run, link)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, link, BluetoothDevice.TRANSPORT_LE)
            } ?: throw IOException("Couldn't start a Bluetooth LE connection")
            link.gatt = gatt
            try {
                link.await<Event.Connected>(CONNECT_TIMEOUT_MILLIS)
                if (!gatt.discoverServices()) throw IOException("Couldn't read the adapter's services")
                val discovered = link.await<Event.ServicesDiscovered>(CONNECT_TIMEOUT_MILLIS)
                if (discovered.status != BluetoothGatt.GATT_SUCCESS) throw IOException("Couldn't read the adapter's services")
            } catch (e: IOException) {
                link.close()
                throw e
            }
            return link
        }
    }
}
