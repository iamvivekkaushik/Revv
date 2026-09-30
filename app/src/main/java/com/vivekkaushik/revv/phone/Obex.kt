package com.vivekkaushik.revv.phone

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/** The OBEX server answered a request with [code], e.g. 0xC3 Forbidden, rather than success. */
class ObexException(val code: Int) : IOException("Refused with OBEX response 0x%02X".format(code)) {
    /** The server's owner, or the server itself, won't allow it. */
    val refused: Boolean get() = code == UNAUTHORIZED || code == FORBIDDEN

    private companion object {
        const val UNAUTHORIZED = 0xC1
        const val FORBIDDEN = 0xC3
    }
}

/**
 * The client half of OBEX, the object exchange protocol PBAP runs on, over a byte stream: enough
 * to connect to a service, GET objects and disconnect. Calls block, one at a time.
 * [waitingAfterMillis] is how long an answer may take before the caller hears it's slow.
 */
class ObexClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val waitingAfterMillis: Long = WAITING_AFTER_MILLIS,
) {

    private var connectionId = ByteArray(0)

    /**
     * Connects to the service [target] names. [onWaiting] runs if the answer is slow in coming,
     * as when a phone first asks its owner whether to share.
     */
    fun connect(target: ByteArray, timeoutMillis: Long, onWaiting: () -> Unit = {}) {
        // OBEX 1.0, no flags, and the largest packets the server may send back.
        val fields = byteArrayOf(0x10, 0x00, 0xFF.toByte(), 0xFF.toByte())
        send(CONNECT, fields + byteHeader(TARGET, target))
        val response = receive(timeoutMillis, connectResponse = true, onWaiting)
        if (response.code != SUCCESS) throw ObexException(response.code)
        response.header(CONNECTION_ID)?.let { connectionId = byteArrayOf(CONNECTION_ID.toByte()) + it }
    }

    /** The object called [name], of MIME [type], asked for with [parameters] as its application parameters. */
    fun get(name: String, type: String, parameters: ByteArray, timeoutMillis: Long): ByteArray {
        val body = ByteArrayOutputStream()
        var headers = connectionId + textHeader(NAME, name) + byteHeader(TYPE, (type + "\u0000").toByteArray(Charsets.US_ASCII))
        if (parameters.isNotEmpty()) headers += byteHeader(APP_PARAMETERS, parameters)
        while (true) {
            send(GET_FINAL, headers)
            val response = receive(timeoutMillis)
            response.headers.forEach { (id, value) -> if (id == BODY || id == END_OF_BODY) body.write(value) }
            when (response.code) {
                // The rest follows as the client keeps asking, with nothing more to say.
                CONTINUE -> headers = ByteArray(0)
                SUCCESS -> return body.toByteArray()
                else -> throw ObexException(response.code)
            }
        }
    }

    fun disconnect(timeoutMillis: Long) {
        send(DISCONNECT, connectionId)
        receive(timeoutMillis)
    }

    private class Response(val code: Int, val headers: List<Pair<Int, ByteArray>>) {
        fun header(id: Int): ByteArray? = headers.firstOrNull { it.first == id }?.second
    }

    private fun send(opcode: Int, payload: ByteArray) {
        val length = 3 + payload.size
        output.write(byteArrayOf(opcode.toByte(), (length shr 8).toByte(), length.toByte()) + payload)
        output.flush()
    }

    private fun receive(timeoutMillis: Long, connectResponse: Boolean = false, onWaiting: () -> Unit = {}): Response {
        val start = read(3, timeoutMillis, onWaiting)
        val length = (start[1].toInt() and 0xFF shl 8) or (start[2].toInt() and 0xFF)
        if (length < 3) throw IOException("Unreadable OBEX packet")
        val rest = read(length - 3, timeoutMillis)
        // A connect response starts with the server's version, flags and packet size.
        val headersFrom = if (connectResponse && rest.size >= 4) 4 else 0
        return Response(start[0].toInt() and 0xFF, headers(rest, headersFrom))
    }

    private fun headers(data: ByteArray, from: Int): List<Pair<Int, ByteArray>> {
        val headers = mutableListOf<Pair<Int, ByteArray>>()
        var i = from
        while (i < data.size) {
            val id = data[i].toInt() and 0xFF
            // The top two bits of a header's ID say how its value is laid out.
            val size = when (id and 0xC0) {
                0x80 -> 2
                0xC0 -> 5
                else -> if (i + 2 < data.size) (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF) else 0
            }
            if (size < 2 || i + size > data.size) throw IOException("Unreadable OBEX header")
            val valueFrom = if (id and 0xC0 == 0x00 || id and 0xC0 == 0x40) i + 3 else i + 1
            headers += id to data.copyOfRange(valueFrom, i + size)
            i += size
        }
        return headers
    }

    /**
     * Exactly [count] bytes, polling because a Bluetooth socket's reads can't time out. [onWaiting]
     * runs once if nothing has arrived after [waitingAfterMillis].
     */
    private fun read(count: Int, timeoutMillis: Long, onWaiting: () -> Unit = {}): ByteArray {
        val buffer = ByteArray(count)
        val started = System.nanoTime()
        var waited = false
        var read = 0
        while (read < count) {
            if (input.available() > 0) {
                val got = input.read(buffer, read, count - read)
                if (got < 0) throw IOException("The phone closed the connection")
                read += got
                continue
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            if (elapsedMillis > timeoutMillis) throw SocketTimeoutException("The phone stopped answering")
            if (!waited && read == 0 && elapsedMillis > waitingAfterMillis) {
                waited = true
                onWaiting()
            }
            Thread.sleep(POLL_MILLIS)
        }
        return buffer
    }

    private fun textHeader(id: Int, text: String): ByteArray {
        val value = (text + "\u0000").toByteArray(Charsets.UTF_16BE)
        return lengthPrefixed(id, value)
    }

    private fun byteHeader(id: Int, value: ByteArray): ByteArray = lengthPrefixed(id, value)

    private fun lengthPrefixed(id: Int, value: ByteArray): ByteArray {
        val length = 3 + value.size
        return byteArrayOf(id.toByte(), (length shr 8).toByte(), length.toByte()) + value
    }

    private companion object {
        const val CONNECT = 0x80
        const val DISCONNECT = 0x81
        const val GET_FINAL = 0x83

        const val CONTINUE = 0x90
        const val SUCCESS = 0xA0

        const val NAME = 0x01
        const val TYPE = 0x42
        const val TARGET = 0x46
        const val BODY = 0x48
        const val END_OF_BODY = 0x49
        const val APP_PARAMETERS = 0x4C
        const val CONNECTION_ID = 0xCB

        const val WAITING_AFTER_MILLIS = 3_000L
        const val POLL_MILLIS = 5L
    }
}
