package com.vivekkaushik.revv.obd

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An ELM327 at the other end of a byte stream: a Bluetooth serial port or a Wi-Fi TCP socket. */
open class StreamObdTransport(
    protected val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
) : ObdTransport {

    private val pending = StringBuilder()

    override fun send(command: String) {
        output.write("$command\r".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    override fun receive(timeoutMillis: Long): String? {
        takeReply()?.let { return it }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val chunk = ByteArray(256)
        while (System.nanoTime() < deadline) {
            val count = readAvailable(chunk)
            if (count < 0) throw IOException("The adapter closed the connection")
            for (i in 0 until count) pending.append((chunk[i].toInt() and 0xFF).toChar())
            takeReply()?.let { return it }
        }
        return null
    }

    /** Removes and returns everything before the first prompt, if one has arrived. Later bytes wait. */
    private fun takeReply(): String? {
        val prompt = pending.indexOf(">")
        if (prompt < 0) return null
        val reply = pending.substring(0, prompt)
        pending.delete(0, prompt + 1)
        return reply
    }

    override fun discard(): String {
        val dropped = StringBuilder(pending)
        pending.clear()
        val chunk = ByteArray(256)
        while (input.available() > 0) {
            val count = readAvailable(chunk)
            if (count <= 0) break
            for (i in 0 until count) dropped.append((chunk[i].toInt() and 0xFF).toChar())
        }
        return dropped.toString()
    }

    override fun close() = onClose()

    /**
     * Reads whatever has arrived into [buffer]: a byte count, 0 if nothing has yet, or -1 once the
     * other end has closed. Polls, because a blocking read can't time out on a Bluetooth socket.
     */
    protected open fun readAvailable(buffer: ByteArray): Int {
        if (input.available() <= 0) {
            Thread.sleep(POLL_MILLIS)
            return 0
        }
        return input.read(buffer)
    }

    private companion object {
        const val POLL_MILLIS = 5L
    }
}
