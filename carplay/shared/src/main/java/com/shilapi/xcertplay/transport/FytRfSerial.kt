package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * Wireless CarPlay's Bluetooth leg on FYT head units (the com.syu.* firmware). There FYT's own
 * daemon, blink5, holds the radio, so Android's RFCOMM can't reach the iPhone. blink5 opens the
 * iAP2 RFCOMM channel to the iPhone itself and passes its bytes to apps as hex text over a
 * pseudo-terminal, /dev/rf_serial: FYT's Car Link runs wireless CarPlay that way
 * (docs/FYT_BLUETOOTH.md). Only one app can read the terminal, so Car Link has to be stopped.
 *
 * [trace] hears what blink5 says and does, for the connection log.
 */
class FytRfSerial private constructor(
    private val input: FileInputStream,
    private val output: FileOutputStream,
    private val trace: (String) -> Unit,
) : Closeable {

    private val lock = Object()
    private val writeLock = Object()
    private var closed = false

    /** The linked phone blink5 last named, AA:BB:CC:DD:EE:FF. */
    private var phoneAddress: String? = null
    private var localAddress: String? = null
    private var channel: Channel? = null

    private val reader = Thread(::readLoop, "xcertplay-fyt-rf-serial-reader").apply { isDaemon = true }

    init {
        reader.start()
        // As Car Link does: ask for the linked phone and this unit's own Bluetooth address.
        try {
            send(FytRfSerialProtocol.QUERY_INFO)
            send(FytRfSerialProtocol.QUERY_LOCAL_ADDRESS)
        } catch (io: IOException) {
            trace("FYT: couldn't ask blink5 for the phone: ${io.message}")
        }
    }

    /** This head unit's Bluetooth address as blink5 has it, the one the iPhone is paired with. */
    fun awaitLocalAddress(timeoutMillis: Long): String? = awaitValue(timeoutMillis) { localAddress }

    /** The phone FYT's Bluetooth has linked, if blink5 names one in time. */
    fun awaitPhoneAddress(timeoutMillis: Long): String? = awaitValue(timeoutMillis) { phoneAddress }

    /**
     * Has blink5 open the iAP2 channel to the iPhone at [address] and returns it, or throws if the
     * channel doesn't come up within [timeoutMillis].
     */
    fun connect(address: String, timeoutMillis: Long): BlockingDuplexByteStream {
        val opened = Channel()
        synchronized(lock) {
            if (closed) throw IOException("$PATH is closed")
            channel?.end()
            channel = opened
        }
        trace("FYT: asking blink5 for the iAP2 channel to $address")
        send(FytRfSerialProtocol.connect(address))
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(lock) {
            while (!opened.up && !opened.ended && !closed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
            }
            if (opened.up && !opened.ended) return opened
        }
        runCatching { send(FytRfSerialProtocol.DISCONNECT_CHANNEL) }
        throw IOException(
            if (opened.ended) "blink5 couldn't open the iAP2 channel to $address"
            else "blink5 didn't open the iAP2 channel to $address within ${timeoutMillis / 1000} s",
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            channel?.end()
            channel = null
            lock.notifyAll()
        }
        runCatching { send(FytRfSerialProtocol.DISCONNECT_CHANNEL) }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun <T> awaitValue(timeoutMillis: Long, value: () -> T?): T? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(lock) {
            while (true) {
                value()?.let { return it }
                val remaining = deadline - System.nanoTime()
                if (closed || remaining <= 0) return null
                lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
            }
        }
    }

    private fun send(line: String) {
        synchronized(writeLock) {
            output.write((line + FytRfSerialProtocol.LINE_END).toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    private fun readLoop() {
        val lines = FytRfSerialProtocol.LineSplitter()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                for (line in lines.feed(buffer, count)) handle(line)
            }
        } catch (io: IOException) {
            if (!isClosed()) trace("FYT: reading $PATH failed: ${io.message}")
        } finally {
            synchronized(lock) {
                channel?.end()
                lock.notifyAll()
            }
        }
    }

    private fun handle(line: String) {
        val event = FytRfSerialProtocol.parse(line)
        synchronized(lock) {
            when (event) {
                is FytRfSerialProtocol.Event.Data -> channel?.deliver(event.bytes)
                FytRfSerialProtocol.Event.ChannelUp -> channel?.let { it.up = true }
                FytRfSerialProtocol.Event.ChannelDown -> channel?.end()
                is FytRfSerialProtocol.Event.PhoneAddress -> phoneAddress = event.address
                is FytRfSerialProtocol.Event.LocalAddress -> localAddress = event.address
                else -> Unit
            }
            lock.notifyAll()
        }
        when (event) {
            is FytRfSerialProtocol.Event.Data -> Unit
            FytRfSerialProtocol.Event.ChannelUp -> trace("FYT: blink5 opened the iAP2 channel")
            FytRfSerialProtocol.Event.ChannelDown -> trace("FYT: blink5 closed the iAP2 channel")
            is FytRfSerialProtocol.Event.PhoneAddress -> trace("FYT: blink5 reports phone ${event.address}")
            is FytRfSerialProtocol.Event.LocalAddress -> trace("FYT: this head unit's Bluetooth address is ${event.address}")
            is FytRfSerialProtocol.Event.Other -> trace("FYT: blink5 said \"${event.line.take(80)}\"")
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    /** One opening of the iAP2 channel. Its state is guarded by the serial's lock. */
    private inner class Channel : BlockingDuplexByteStream {
        var up = false
        var ended = false
            private set
        private val pending = ArrayDeque<ByteArray>()

        fun deliver(bytes: ByteArray) {
            if (!ended && bytes.isNotEmpty()) pending.addLast(bytes)
        }

        fun end() {
            ended = true
        }

        override fun send(data: ByteArray) {
            synchronized(lock) {
                if (closed || ended) throw IOException("FYT's iAP2 channel is closed")
            }
            for (line in FytRfSerialProtocol.dataLines(data)) this@FytRfSerial.send(line)
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            require(maxBytes > 0) { "maxBytes must be positive" }
            require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            synchronized(lock) {
                while (true) {
                    pending.pollFirst()?.let { chunk ->
                        if (chunk.size <= maxBytes) return chunk
                        pending.addFirst(chunk.copyOfRange(maxBytes, chunk.size))
                        return chunk.copyOf(maxBytes)
                    }
                    if (ended || closed) return EMPTY
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return null
                    try {
                        lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }

        /** Closes this channel to the iPhone; the terminal stays open for the next one. */
        override fun close() {
            val wasOpen = synchronized(lock) {
                val open = !ended
                ended = true
                if (channel === this) channel = null
                lock.notifyAll()
                open
            }
            if (wasOpen) runCatching { this@FytRfSerial.send(FytRfSerialProtocol.DISCONNECT_CHANNEL) }
        }
    }

    companion object {
        const val PATH = "/dev/rf_serial"
        private val EMPTY = ByteArray(0)

        /** Whether this is an FYT unit whose CarPlay channel this app can open. */
        fun available(): Boolean = try {
            val node = File(PATH)
            node.exists() && node.canRead() && node.canWrite()
        } catch (_: SecurityException) {
            false
        }

        fun open(trace: (String) -> Unit): FytRfSerial {
            val input = FileInputStream(PATH)
            val output = try {
                FileOutputStream(PATH)
            } catch (io: IOException) {
                runCatching { input.close() }
                throw io
            }
            return FytRfSerial(input, output, trace)
        }
    }
}

/** blink5's text protocol on /dev/rf_serial, as Car Link's libcps speaks it. Pure, for testing. */
internal object FytRfSerialProtocol {
    const val LINE_END = "\r\n"
    const val QUERY_INFO = "AT#QI"
    const val QUERY_LOCAL_ADDRESS = "AT#VE"
    const val DISCONNECT_CHANNEL = "AT#SH"
    private const val CONNECT = "AT#SP"
    private const val SEND_DATA = "AT#SG"

    /** The iAP2 service blink5 is asked to connect to, as Car Link writes it. */
    const val IAP2_UUID = "00000000DECAFADEDECADEAFDECACAFE"

    /** Bytes per data line, as Car Link sends them. */
    private const val BYTES_PER_LINE = 100
    private const val MAX_LINE = 16_384
    private val HEX = "0123456789ABCDEF".toCharArray()

    sealed interface Event {
        /** SV: the iAP2 channel is up. */
        object ChannelUp : Event

        /** SS: the iAP2 channel is down. */
        object ChannelDown : Event

        /** JH: the linked phone's address. */
        data class PhoneAddress(val address: String) : Event

        /** DB: this head unit's own Bluetooth address. */
        data class LocalAddress(val address: String) : Event

        /** SI: iAP2 bytes from the iPhone. */
        class Data(val bytes: ByteArray) : Event

        data class Other(val line: String) : Event
    }

    fun parse(line: String): Event {
        if (line.length < 2) return Event.Other(line)
        val rest = line.substring(2).trim()
        return when (line.substring(0, 2)) {
            "SV" -> Event.ChannelUp
            "SS" -> Event.ChannelDown
            "JH" -> address(rest)?.let { Event.PhoneAddress(it) } ?: Event.Other(line)
            "DB" -> address(rest)?.let { Event.LocalAddress(it) } ?: Event.Other(line)
            "SI" -> decodeHex(rest)?.let { Event.Data(it) } ?: Event.Other(line)
            else -> Event.Other(line)
        }
    }

    /** AT#SP with the iPhone's address as twelve hex digits, then iAP2's service UUID. */
    fun connect(address: String): String {
        val digits = address.filter { Character.digit(it, 16) >= 0 }.uppercase()
        require(digits.length == 12) { "Not a Bluetooth address: $address" }
        return CONNECT + digits + IAP2_UUID
    }

    /** "001122AABBCC" as "00:11:22:AA:BB:CC", or null if it isn't twelve hex digits. */
    fun address(text: String): String? {
        if (text.length != 12 || text.any { Character.digit(it, 16) < 0 }) return null
        return text.uppercase().chunked(2).joinToString(":")
    }

    fun decodeHex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val bytes = ByteArray(text.length / 2)
        for (i in bytes.indices) {
            val high = Character.digit(text[2 * i], 16)
            val low = Character.digit(text[2 * i + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    /** [data] as AT#SG lines of at most 100 bytes each, without line ends. */
    fun dataLines(data: ByteArray): List<String> {
        val lines = ArrayList<String>((data.size + BYTES_PER_LINE - 1) / BYTES_PER_LINE)
        var offset = 0
        while (offset < data.size) {
            val count = min(BYTES_PER_LINE, data.size - offset)
            val line = StringBuilder(SEND_DATA.length + count * 2).append(SEND_DATA)
            for (i in offset until offset + count) {
                val value = data[i].toInt() and 0xFF
                line.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            lines += line.toString()
            offset += count
        }
        return lines
    }

    /** Cuts what blink5 writes into its messages: any run of CR or LF separates two. */
    class LineSplitter {
        private val pending = StringBuilder()

        fun feed(bytes: ByteArray, count: Int): List<String> {
            val lines = mutableListOf<String>()
            for (i in 0 until count) {
                val c = (bytes[i].toInt() and 0xFF).toChar()
                if (c == '\n' || c == '\r') {
                    if (pending.isNotEmpty()) {
                        lines += pending.toString()
                        pending.setLength(0)
                    }
                } else if (pending.length < MAX_LINE) {
                    pending.append(c)
                }
            }
            return lines
        }
    }
}
