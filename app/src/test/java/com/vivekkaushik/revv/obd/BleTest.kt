package com.vivekkaushik.revv.obd

import com.vivekkaushik.revv.obd.GattCharacteristicInfo.Companion.PROPERTY_INDICATE
import com.vivekkaushik.revv.obd.GattCharacteristicInfo.Companion.PROPERTY_NOTIFY
import com.vivekkaushik.revv.obd.GattCharacteristicInfo.Companion.PROPERTY_WRITE
import com.vivekkaushik.revv.obd.GattCharacteristicInfo.Companion.PROPERTY_WRITE_NO_RESPONSE
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTest {

    private fun char(service: Int, uuid: Int, properties: Int) =
        GattCharacteristicInfo(BleUart.uuid16(service), BleUart.uuid16(uuid), properties)

    private val deviceInfo = listOf(char(0x180A, 0x2A29, 0x02), char(0x1800, 0x2A00, 0x02))

    @Test
    fun uuid16_expandsShortUuids() {
        assertEquals(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"), BleUart.uuid16(0xFFF0))
    }

    @Test
    fun candidates_preferTheCommonFff0Layout() {
        val found = deviceInfo + listOf(
            char(0xFFF0, 0xFFF1, PROPERTY_NOTIFY),
            char(0xFFF0, 0xFFF2, PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE),
        )
        assertEquals(BleUart.KNOWN[0], BleUart.candidates(found).first())
    }

    @Test
    fun candidates_handleOneCharacteristicBothWays() {
        val found = listOf(char(0xFFE0, 0xFFE1, PROPERTY_NOTIFY or PROPERTY_WRITE_NO_RESPONSE))
        assertEquals(listOf(BleUart.KNOWN[1]), BleUart.candidates(found))
    }

    @Test
    fun candidates_guessUnknownServicesButSkipStandardOnes() {
        val found = deviceInfo + listOf(
            char(0xAE00, 0xAE02, PROPERTY_INDICATE),
            char(0xAE00, 0xAE01, PROPERTY_WRITE),
        )
        val guess = BleUart.candidates(found).single()
        assertEquals(BleUart.uuid16(0xAE00), guess.service)
        assertEquals(BleUart.uuid16(0xAE02), guess.notify)
        assertEquals(BleUart.uuid16(0xAE01), guess.write)
    }

    @Test
    fun candidates_areEmptyWithoutAnythingToTalkTo() {
        assertTrue(BleUart.candidates(deviceInfo).isEmpty())
        assertTrue(BleUart.candidates(listOf(char(0xFFF0, 0xFFF1, PROPERTY_NOTIFY))).isEmpty())
    }

    @Test
    fun chunkedOutput_splitsCommandsToFitAWrite() {
        val chunks = mutableListOf<Int>()
        val output = ChunkedOutputStream(chunkSize = 20) { chunks += it.size }
        output.write(ByteArray(45))
        output.flush()
        assertEquals(listOf(20, 20, 5), chunks)
        output.flush()
        assertEquals(3, chunks.size)
    }

    @Test
    fun notificationsArrivingInPieces_readAsOneReply() {
        val queue = ByteQueueInputStream()
        val transport = object : StreamObdTransport(queue, ByteArrayOutputStream(), onClose = {}) {
            override fun readAvailable(buffer: ByteArray): Int = queue.poll(buffer, 10)
        }
        thread {
            listOf("41 0", "D 32\r", "\r>").forEach {
                Thread.sleep(20)
                queue.offer(it.toByteArray())
            }
        }
        assertEquals("41 0D 32\r\r", transport.receive(timeoutMillis = 2_000))
    }

    @Test
    fun byteQueue_reportsTheEndOnceClosedAndDrained() {
        val queue = ByteQueueInputStream()
        val buffer = ByteArray(8)
        assertEquals(0, queue.poll(buffer, 10))
        queue.offer(byteArrayOf(1, 2))
        queue.close()
        assertEquals(2, queue.poll(buffer, 10))
        assertEquals(-1, queue.poll(buffer, 10))
    }

    @Test
    fun restore_knowsBluetoothLeAdapters() {
        assertEquals(ObdAdapter.Kind.BluetoothLe, ObdAdapter.restore("BluetoothLe", "C0:FF:EE:00:11:22", "IOS-Vlink").kind)
    }
}
