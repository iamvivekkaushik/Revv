package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Android Auto's Bluetooth channel on FYT head units (the com.syu.* firmware). There FYT's own
 * daemon, blink5, holds the radio: Android's RFCOMM can't reach the phone at all. blink5 accepts
 * the phone's Android Auto RFCOMM connection itself and passes its bytes to apps as hex text over a
 * pseudo-terminal, /dev/auto_serial, which is how FYT's Car Link app runs wireless Android Auto
 * (docs/FYT_BLUETOOTH.md). Only one app can read the terminal, so Car Link's wireless mode has to
 * be off for this to work.
 *
 * [onLink] hears each phone channel that opens, on the reader thread. While [busy] says an
 * Android Auto session is already running, what the phone sends on a channel Revv didn't see open
 * is passed over rather than taken up: the phone keeps talking on it through a session.
 */
class FytAutoSerial(
    private val busy: () -> Boolean = { false },
    private val onLink: (AaBluetoothLink) -> Unit,
) {
    /** Whether passing over the phone's messages during this session has been logged yet. */
    @Volatile private var passedOverLogged = false

    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private val writeLock = Any()
    @Volatile private var running = false

    /** The phone blink5 last named; it says so before the channel opens. */
    @Volatile private var phoneAddress: String? = null
    @Volatile private var link: Link? = null

    /** Opens the terminal and starts listening. False if this app may not open it. */
    fun start(): Boolean {
        if (running) return true
        try {
            input = FileInputStream(PATH)
            output = FileOutputStream(PATH)
        } catch (e: IOException) {
            AppLog.e("NativeAA/FYT: couldn't open $PATH: ${e.message}")
            closeStreams()
            return false
        } catch (e: SecurityException) {
            AppLog.e("NativeAA/FYT: not allowed to open $PATH: ${e.message}")
            closeStreams()
            return false
        }
        running = true
        AppLog.i("NativeAA/FYT: listening for Android Auto on FYT's Bluetooth ($PATH).")
        thread(name = "FytAutoSerial-Reader", isDaemon = true) { readLoop() }
        thread(name = "FytAutoSerial-Hello", isDaemon = true) {
            // As Car Link does, a moment after opening: ask which phone is linked, which also
            // reports a channel that is already open, and this unit's own Bluetooth address.
            try {
                Thread.sleep(HELLO_DELAY_MS)
                send(FytAutoSerialProtocol.QUERY_INFO)
                send(FytAutoSerialProtocol.QUERY_LOCAL_ADDRESS)
            } catch (e: InterruptedException) {
                // Stopped first.
            } catch (e: IOException) {
                AppLog.w("NativeAA/FYT: couldn't ask blink5 for the phone: ${e.message}")
            }
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        link?.end()
        link = null
        closeStreams()
    }

    private fun closeStreams() {
        try { input?.close() } catch (_: IOException) {}
        try { output?.close() } catch (_: IOException) {}
        input = null
        output = null
    }

    private fun readLoop() {
        val stream = input ?: return
        val lines = FytAutoSerialProtocol.LineSplitter()
        val buffer = ByteArray(512)
        while (running) {
            val count = try {
                stream.read(buffer)
            } catch (e: IOException) {
                if (running) AppLog.w("NativeAA/FYT: reading $PATH failed: ${e.message}")
                break
            }
            if (count < 0) break
            for (line in lines.feed(buffer, count)) handle(line)
        }
        link?.end()
        link = null
    }

    private fun handle(line: String) {
        when (val event = FytAutoSerialProtocol.parse(line)) {
            is FytAutoSerialProtocol.Event.PhoneAddress -> {
                phoneAddress = event.address
                AppLog.i("NativeAA/FYT: blink5 reports phone ${event.address}.")
            }
            is FytAutoSerialProtocol.Event.LocalAddress -> {
                localAddress = event.address
                AppLog.i("NativeAA/FYT: this head unit's Bluetooth address is ${event.address}.")
            }
            FytAutoSerialProtocol.Event.Connected -> {
                val address = phoneAddress ?: UNKNOWN_PHONE
                val current = link
                // blink5 repeats itself on AT#QI; the same phone's open channel is the same one.
                if (current != null && current.open && current.address == address) return
                current?.end()
                AppLog.i("NativeAA/FYT: the phone ($address) opened Android Auto's Bluetooth channel.")
                open(address)
            }
            is FytAutoSerialProtocol.Event.Data -> {
                val open = link?.takeIf { it.open }
                if (open == null && busy()) {
                    if (!passedOverLogged) {
                        passedOverLogged = true
                        AppLog.d("NativeAA/FYT: a session is running; passing over the phone's messages on the Bluetooth channel.")
                    }
                    return
                }
                passedOverLogged = false
                val current = open ?: run {
                    // The phone is talking on a channel that opened before this listener did (it
                    // outlives an Android Auto session, and the listener restarts after one): data
                    // flowing proves it open, and blink5 won't announce it again.
                    val address = phoneAddress ?: UNKNOWN_PHONE
                    AppLog.i("NativeAA/FYT: the phone ($address) is already talking on Android Auto's Bluetooth channel; taking it up.")
                    open(address)
                }
                current.deliver(event.bytes)
            }
            FytAutoSerialProtocol.Event.Closed -> {
                AppLog.i("NativeAA/FYT: blink5 says the phone closed Android Auto's Bluetooth channel.")
                link?.end()
                link = null
            }
            is FytAutoSerialProtocol.Event.Other -> AppLog.d("NativeAA/FYT: blink5 said \"${event.line}\".")
        }
    }

    /** A channel to the phone at [address], handed to [onLink]. */
    private fun open(address: String): Link {
        val opened = Link(address)
        link = opened
        onLink(opened)
        return opened
    }

    @Throws(IOException::class)
    private fun send(line: String) {
        synchronized(writeLock) {
            val stream = output ?: throw IOException("$PATH is closed")
            stream.write((line + FytAutoSerialProtocol.LINE_END).toByteArray(Charsets.US_ASCII))
            stream.flush()
        }
    }

    /** One opening of the channel by the phone: what it sends, queued for the handshake to read. */
    private inner class Link(override val address: String) : AaBluetoothLink {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile var open = true
            private set

        override val name: String? = null

        override val inputStream: InputStream = object : InputStream() {
            private var chunk: ByteArray? = null
            private var position = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) {
                    val current = chunk
                    if (current != null && position < current.size) {
                        val count = minOf(len, current.size - position)
                        System.arraycopy(current, position, b, off, count)
                        position += count
                        return count
                    }
                    val next = try {
                        queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("interrupted", e)
                    }
                    when {
                        next === END -> {
                            queue.offer(END)
                            return -1
                        }
                        next != null -> {
                            chunk = next
                            position = 0
                        }
                        !open && queue.isEmpty() -> return -1
                    }
                }
            }
        }

        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (!open) throw IOException("Android Auto's Bluetooth channel is closed")
                for (line in FytAutoSerialProtocol.dataLines(b, off, len)) send(line)
            }
        }

        fun deliver(bytes: ByteArray) {
            if (open && bytes.isNotEmpty()) queue.offer(bytes)
        }

        /** The phone, blink5 or this app is done with the channel: what's queued is still read. */
        fun end() {
            open = false
            queue.offer(END)
        }

        override fun close() {
            end()
            if (link === this) link = null
        }
    }

    companion object {
        const val PATH = "/dev/auto_serial"
        private const val HELLO_DELAY_MS = 1_000L
        private const val POLL_MS = 250L
        private const val UNKNOWN_PHONE = "00:00:00:00:00:00"
        private val END = ByteArray(0)

        /** This head unit's own Bluetooth address as blink5 reported it, once a listener has asked. */
        @Volatile
        var localAddress: String? = null
            private set

        /** Whether this is an FYT unit whose Android Auto channel this app can open. */
        fun available(): Boolean = try {
            val node = File(PATH)
            node.exists() && node.canRead() && node.canWrite()
        } catch (e: SecurityException) {
            false
        }
    }
}

/** blink5's text protocol on /dev/auto_serial, as FYT's Car Link speaks it. Pure, for testing. */
internal object FytAutoSerialProtocol {
    /** Car Link ends each line this way round. */
    const val LINE_END = "\n\r"
    const val QUERY_INFO = "AT#QI"
    const val QUERY_LOCAL_ADDRESS = "AT#VE"
    private const val SEND_DATA = "AT#ZA"

    /** Hex digits per data line: 50 bytes, as Car Link sends them. */
    private const val HEX_PER_LINE = 100
    private const val MAX_LINE = 4096
    private val HEX = "0123456789ABCDEF".toCharArray()

    sealed interface Event {
        /** AA: the phone opened Android Auto's RFCOMM channel. */
        object Connected : Event

        /** SS: the channel closed. */
        object Closed : Event

        /** JH: the linked phone's address. */
        data class PhoneAddress(val address: String) : Event

        /** DB: this head unit's own Bluetooth address. */
        data class LocalAddress(val address: String) : Event

        /** ZB: bytes from the phone. */
        class Data(val bytes: ByteArray) : Event

        data class Other(val line: String) : Event
    }

    fun parse(line: String): Event {
        if (line.length < 2) return Event.Other(line)
        val rest = line.substring(2).trim()
        return when (line.substring(0, 2)) {
            "AA" -> Event.Connected
            "SS" -> Event.Closed
            "JH" -> address(rest)?.let { Event.PhoneAddress(it) } ?: Event.Other(line)
            "DB" -> address(rest)?.let { Event.LocalAddress(it) } ?: Event.Other(line)
            "ZB" -> decodeHex(rest)?.let { Event.Data(it) } ?: Event.Other(line)
            else -> Event.Other(line)
        }
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

    /** [len] bytes as AT#ZA lines of at most 50 bytes each, without line ends. */
    fun dataLines(bytes: ByteArray, off: Int, len: Int): List<String> {
        val hex = StringBuilder(len * 2)
        for (i in off until off + len) {
            val value = bytes[i].toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.chunked(HEX_PER_LINE).map { SEND_DATA + it }
    }

    /**
     * Cuts what blink5 writes into its messages. It frames each as CR LF, message, CR LF, which the
     * terminal turns into LF LF ... LF LF; any run of line breaks separates two messages here.
     */
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
