package com.vivekkaushik.revv.obd

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bytes handed over from another thread, such as Bluetooth LE notifications, read as a stream. */
class ByteQueueInputStream : InputStream() {

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val bytes = ArrayDeque<Byte>()
    private var closed = false

    fun offer(chunk: ByteArray) = lock.withLock {
        chunk.forEach(bytes::addLast)
        arrived.signalAll()
    }

    override fun close() = lock.withLock {
        closed = true
        arrived.signalAll()
    }

    override fun available(): Int = lock.withLock { bytes.size }

    override fun read(): Int = lock.withLock {
        while (bytes.isEmpty() && !closed) arrived.await()
        bytes.removeFirstOrNull()?.toInt()?.and(0xFF) ?: -1
    }

    /**
     * Moves up to [target].size bytes into [target], waiting at most [timeoutMillis] for the first.
     * Returns the count, 0 if nothing came, or -1 once closed with nothing left.
     */
    fun poll(target: ByteArray, timeoutMillis: Long): Int = lock.withLock {
        if (bytes.isEmpty() && !closed) arrived.await(timeoutMillis, TimeUnit.MILLISECONDS)
        if (bytes.isEmpty()) return if (closed) -1 else 0
        var count = 0
        while (count < target.size && bytes.isNotEmpty()) target[count++] = bytes.removeFirst()
        count
    }
}

/**
 * Collects a command and, on [flush], hands it to [writeChunk] in pieces of at most [chunkSize]
 * bytes: a Bluetooth LE write only carries 20 bytes on a link that hasn't negotiated more.
 */
class ChunkedOutputStream(private val chunkSize: Int, private val writeChunk: (ByteArray) -> Unit) : OutputStream() {

    private val pending = ByteArrayOutputStream()

    override fun write(b: Int) = pending.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = pending.write(b, off, len)

    override fun flush() {
        val bytes = pending.toByteArray()
        pending.reset()
        var start = 0
        while (start < bytes.size) {
            val end = minOf(bytes.size, start + chunkSize)
            writeChunk(bytes.copyOfRange(start, end))
            start = end
        }
    }
}
