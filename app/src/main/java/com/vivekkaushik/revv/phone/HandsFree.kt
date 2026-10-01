package com.vivekkaushik.revv.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.UUID

/** Calling on the phone failed while [stage]: reaching it, setting up the link, or dialling. */
class HandsFreeException(val stage: Stage, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Stage { Reaching, Linking, Dialling }
}

/** The call indicators of the phone's hands-free profile: 0 means none. [setup] is 1 incoming, 2 dialling, 3 ringing at the far end. */
data class CallIndicators(val call: Int = 0, val setup: Int = 0, val held: Int = 0) {
    val live: Boolean get() = call > 0 || setup > 0 || held > 0
}

/**
 * The hands-free end of HFP over a byte stream to a phone's audio gateway: the AT commands a car
 * kit sends to set up the link, then to dial. Calls block, one at a time.
 */
class HandsFreeLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val timeoutMillis: Long = REPLY_TIMEOUT_MILLIS,
) {

    private val pending = StringBuilder()

    /** The phone's indicator names in the order it lists them, and what each last reported. */
    private var indicatorNames = emptyList<String>()
    private val indicatorValues = mutableMapOf<String, Int>()

    /** Whether a call is under way, being set up, or on hold, as far as the phone has said. */
    val call: CallIndicators
        get() = CallIndicators(
            call = indicatorValues["call"] ?: 0,
            setup = indicatorValues["callsetup"] ?: 0,
            held = indicatorValues["callheld"] ?: 0,
        )

    /**
     * Sets up the service level connection: features (none, so neither side expects codec or
     * three-way calling talks), the phone's indicators, and event reporting.
     */
    fun establish() {
        command("AT+BRSF=0")
        command("AT+CIND=?").firstOrNull { it.startsWith("+CIND:") }?.let { definition ->
            indicatorNames = INDICATOR.findAll(definition).map { it.groupValues[1].lowercase() }.toList()
        }
        command("AT+CIND?").firstOrNull { it.startsWith("+CIND:") }?.let { current ->
            current.removePrefix("+CIND:").split(',').map { it.trim().toIntOrNull() }.forEachIndexed { index, value ->
                val name = indicatorNames.getOrNull(index)
                if (name != null && value != null) indicatorValues[name] = value
            }
        }
        command("AT+CMER=3,0,0,1")
    }

    /** Hangs up, or turns down a call that is still ringing. */
    fun hangUp() {
        command("AT+CHUP")
    }

    /** Takes in whatever the phone has said since, such as call events; waits briefly when it has said nothing. */
    fun listen() {
        readAvailable()
        while (true) handleEvent(nextLine() ?: break)
    }

    private fun handleEvent(line: String) {
        if (!line.startsWith("+CIEV:")) return
        val (index, value) = line.removePrefix("+CIEV:").split(',').map { it.trim().toIntOrNull() }.let { it.getOrNull(0) to it.getOrNull(1) }
        val name = index?.let { indicatorNames.getOrNull(it - 1) } ?: return
        if (value != null) indicatorValues[name] = value
    }

    /** Has the phone dial [number]: digits, and any of + * #. */
    fun dial(number: String) {
        command("ATD$number;")
    }

    /** Sends [text], then returns the phone's reply lines up to its OK. Unsolicited events in between are ignored. */
    private fun command(text: String): List<String> {
        output.write("$text\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val lines = mutableListOf<String>()
        val started = System.nanoTime()
        while (true) {
            val line = nextLine()
            if (line == null) {
                if ((System.nanoTime() - started) / 1_000_000 > timeoutMillis) throw SocketTimeoutException("The phone didn't answer $text")
                readAvailable()
                continue
            }
            when {
                line == "OK" -> return lines
                line == "ERROR" || line.startsWith("+CME ERROR") -> throw IOException("The phone said $line to $text")
                else -> {
                    // Events can arrive between a command and its reply; keep the call's state current.
                    handleEvent(line)
                    lines += line
                }
            }
        }
    }

    /** Adds whatever has arrived to what's pending, polling because a Bluetooth socket's reads can't time out. */
    private fun readAvailable() {
        val available = input.available()
        if (available <= 0) {
            Thread.sleep(POLL_MILLIS)
            return
        }
        val chunk = ByteArray(available)
        val count = input.read(chunk)
        if (count < 0) throw IOException("The phone closed the connection")
        for (i in 0 until count) pending.append((chunk[i].toInt() and 0xFF).toChar())
    }

    /** The next complete, non-empty line the phone sent, if one has arrived. */
    private fun nextLine(): String? {
        while (true) {
            val end = pending.indexOfFirst { it == '\r' || it == '\n' }
            if (end < 0) return null
            val line = pending.substring(0, end).trim()
            pending.delete(0, end + 1)
            if (line.isNotEmpty()) return line
        }
    }

    companion object {
        /** The phone's hands-free audio gateway, as its Bluetooth service record names it. */
        val AUDIO_GATEWAY: UUID = UUID.fromString("0000111F-0000-1000-8000-00805F9B34FB")

        private val INDICATOR = Regex("""\("([A-Za-z]+)",""")

        /** How long to wait for the phone to report the call at all before leaving it be. */
        private const val CALL_START_MILLIS = 10_000L

        /** Follows the call until the phone says it is over, hanging up when asked. */
        private fun follow(link: HandsFreeLink, onCall: (CallIndicators) -> Unit, hangUpRequested: () -> Boolean) {
            val started = System.nanoTime()
            var last: CallIndicators? = null
            var seenCall = false
            var hungUp = false
            try {
                while (true) {
                    link.listen()
                    val now = link.call
                    if (now != last) {
                        last = now
                        onCall(now)
                    }
                    if (now.live) seenCall = true
                    if (seenCall && !now.live) return
                    if (!seenCall && (System.nanoTime() - started) / 1_000_000 > CALL_START_MILLIS) return
                    if (!hungUp && now.live && hangUpRequested()) {
                        hungUp = true
                        link.hangUp()
                    }
                }
            } catch (e: IOException) {
                // The phone dropped the link; whatever the call is doing, Revv can no longer follow it.
            }
        }

        private const val REPLY_TIMEOUT_MILLIS = 5_000L
        private const val POLL_MILLIS = 5L

        /**
         * Has [device] call [number], as a car kit would, then lets go at once so the phone keeps
         * the call's audio. Takes a second or two. Needs BLUETOOTH_CONNECT on Android 12+.
         */
        @SuppressLint("MissingPermission")
        fun dial(
            bluetooth: BluetoothAdapter,
            device: BluetoothDevice,
            number: String,
            /** Called with the call's state as the phone reports it; when given, the link stays up until the call ends. */
            onCall: ((CallIndicators) -> Unit)? = null,
            /** Whether the driver has asked to hang up. */
            hangUpRequested: () -> Boolean = { false },
        ) {
            runCatching { bluetooth.cancelDiscovery() }
            val socket = try {
                device.createRfcommSocketToServiceRecord(AUDIO_GATEWAY).also { it.connect() }
            } catch (e: IOException) {
                throw HandsFreeException(HandsFreeException.Stage.Reaching, e.message ?: "unreachable", e)
            }
            try {
                val link = HandsFreeLink(socket.inputStream, socket.outputStream)
                try {
                    link.establish()
                } catch (e: IOException) {
                    throw HandsFreeException(HandsFreeException.Stage.Linking, e.message ?: "no link", e)
                }
                try {
                    link.dial(number)
                } catch (e: IOException) {
                    throw HandsFreeException(HandsFreeException.Stage.Dialling, e.message ?: "didn't dial", e)
                }
                if (onCall != null) follow(link, onCall, hangUpRequested)
            } finally {
                runCatching { socket.close() }
            }
        }
    }
}
