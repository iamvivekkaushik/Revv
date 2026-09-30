package com.vivekkaushik.revv.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One vCard from the phone: a contact, or with [call] set, a call from its history. */
data class PbapEntry(val name: String?, val numbers: List<String>, val call: CallType?, val timeMillis: Long?)

/** What a phone shared: its call history, newest first, and its favourites if it keeps any for PBAP. */
class PhoneBookData(val calls: List<PbapEntry>, val favourites: List<PbapEntry>?)

/** Reading the phone failed while [stage]: reaching it, being let in, or reading. */
class PbapException(val stage: Stage, cause: IOException) : IOException(cause.message, cause) {
    enum class Stage { Reaching, Asking, Reading }
}

/**
 * A PBAP session with a phone's phonebook server over [obex]. PBAP is the Bluetooth profile car
 * kits use to read a phone's contacts and call history; the phone asks its owner the first time.
 * Calls block.
 */
class PbapSession(private val obex: ObexClient, private val zone: ZoneId = ZoneId.systemDefault()) {

    /** [onWaiting] runs if the phone takes a while to answer, usually because it's asking its owner. */
    fun connect(onWaiting: () -> Unit) = obex.connect(TARGET, APPROVAL_TIMEOUT_MILLIS, onWaiting)

    /** Calls in and out, newest first. */
    fun callHistory(): List<PbapEntry> = pull(CALL_HISTORY, MAX_CALLS, CALL_PROPERTIES).filter { it.call != null }

    /** The phone's favourite contacts, or null when it keeps none for PBAP to read. */
    fun favourites(): List<PbapEntry>? = try {
        pull(FAVOURITES, MAX_FAVOURITES, CONTACT_PROPERTIES)
    } catch (e: ObexException) {
        // Only PBAP 1.2 phones have a favourites folder.
        null
    }

    fun disconnect() = obex.disconnect(REPLY_TIMEOUT_MILLIS)

    private fun pull(path: String, maxCount: Int, properties: Long): List<PbapEntry> {
        val parameters = parameter(FORMAT, byteArrayOf(VCARD_30)) +
            parameter(MAX_LIST_COUNT, bigEndian(maxCount.toLong(), 2)) +
            parameter(PROPERTY_SELECTOR, bigEndian(properties, 8))
        val body = obex.get(path, PHONEBOOK_TYPE, parameters, REPLY_TIMEOUT_MILLIS)
        return VCard.parse(String(body, Charsets.UTF_8)).map { entry(it, zone) }
    }

    companion object {
        /** The phonebook server's Bluetooth service class. */
        val SERVICE: UUID = UUID.fromString("0000112F-0000-1000-8000-00805F9B34FB")

        /**
         * Reads [device]'s call history and favourites over Bluetooth. Takes seconds, and longer while
         * the phone asks its owner; [onWaiting] and [onReading] tell how it's going. Needs
         * BLUETOOTH_CONNECT on Android 12+; callers check it first.
         */
        @SuppressLint("MissingPermission")
        fun read(bluetooth: BluetoothAdapter, device: BluetoothDevice, onWaiting: () -> Unit, onReading: () -> Unit): PhoneBookData {
            // Discovery slows connections down.
            runCatching { bluetooth.cancelDiscovery() }
            val socket = stage(PbapException.Stage.Reaching) { device.createRfcommSocketToServiceRecord(SERVICE) }
            try {
                stage(PbapException.Stage.Reaching) { socket.connect() }
                val session = PbapSession(ObexClient(socket.inputStream, socket.outputStream))
                stage(PbapException.Stage.Asking) { session.connect(onWaiting) }
                onReading()
                val data = stage(PbapException.Stage.Reading) { PhoneBookData(session.callHistory(), session.favourites()) }
                runCatching { session.disconnect() }
                return data
            } finally {
                runCatching { socket.close() }
            }
        }

        private inline fun <T> stage(stage: PbapException.Stage, block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw PbapException(stage, e)
        }

        /** The contact or call in [card], its preferred number first. */
        fun entry(card: VCard, zone: ZoneId): PbapEntry {
            val name = card.first("FN")?.value?.let(VCard::unescape)?.trim()?.takeIf(String::isNotEmpty)
                ?: card.first("N")?.value?.let(::nameFromParts)
            val numbers = card.all("TEL")
                .sortedBy { tel ->
                    val kinds = tel.parameters.joinToString(",").uppercase()
                    when {
                        "PREF" in kinds -> 0
                        "CELL" in kinds -> 1
                        else -> 2
                    }
                }
                .map { it.value.trim() }
                .filter(String::isNotEmpty)
            val stamp = card.first("X-IRMC-CALL-DATETIME")
            return PbapEntry(name, numbers, stamp?.let(::callType), stamp?.value?.let { time(it, zone) })
        }

        /** "Sharma;Priya;;;" reads "Priya Sharma". */
        private fun nameFromParts(value: String): String? {
            val parts = VCard.components(value).map(String::trim)
            val (family, given, additional) = List(3) { parts.getOrNull(it).orEmpty() }
            return listOf(given, additional, family).filter(String::isNotEmpty).joinToString(" ").takeIf(String::isNotEmpty)
        }

        /** MISSED, RECEIVED or DIALED: a bare parameter in vCard 2.1, a TYPE in 3.0. */
        private fun callType(stamp: VCard.Property): CallType? {
            val words = stamp.parameters.flatMap { it.substringAfter('=').split(',') }.map { it.trim().uppercase() }
            return when {
                "MISSED" in words -> CallType.Missed
                "RECEIVED" in words -> CallType.Incoming
                "DIALED" in words -> CallType.Outgoing
                else -> null
            }
        }

        /** "20260930T164800" is the phone's local time; with "Z" or "+0530" on the end, the zone is given. */
        private fun time(value: String, zone: ZoneId): Long? {
            val match = STAMP.matchEntire(value.trim()) ?: return null
            val local = runCatching { LocalDateTime.parse(match.groupValues[1] + match.groupValues[2], STAMP_DIGITS) }.getOrNull()
                ?: return null
            val offset = match.groupValues[3]
            val at = if (offset.isEmpty()) zone else runCatching { ZoneOffset.of(offset) }.getOrNull() ?: return null
            return local.atZone(at).toInstant().toEpochMilli()
        }

        private fun parameter(tag: Int, value: ByteArray) = byteArrayOf(tag.toByte(), value.size.toByte()) + value

        private fun bigEndian(value: Long, size: Int) = ByteArray(size) { i -> (value shr (8 * (size - 1 - i))).toByte() }

        /** PBAP's OBEX target, 796135f0-f0c5-11d8-0966-0800200c9a66. */
        private val TARGET = intArrayOf(0x79, 0x61, 0x35, 0xF0, 0xF0, 0xC5, 0x11, 0xD8, 0x09, 0x66, 0x08, 0x00, 0x20, 0x0C, 0x9A, 0x66)
            .map { it.toByte() }
            .toByteArray()

        private const val PHONEBOOK_TYPE = "x-bt/phonebook"
        private const val CALL_HISTORY = "telecom/cch.vcf"
        private const val FAVOURITES = "telecom/fav.vcf"

        private const val MAX_LIST_COUNT = 0x04
        private const val PROPERTY_SELECTOR = 0x06
        private const val FORMAT = 0x07
        private const val VCARD_30: Byte = 0x01

        /** VERSION, FN, N and TEL. */
        private const val CONTACT_PROPERTIES = 0x87L

        /** The same, plus X-IRMC-CALL-DATETIME: when each call was, and how it went. */
        private const val CALL_PROPERTIES = CONTACT_PROPERTIES or (1L shl 28)

        private const val MAX_CALLS = 500
        private const val MAX_FAVOURITES = 50

        /** Android phones give their owner 30 seconds to answer. */
        private const val APPROVAL_TIMEOUT_MILLIS = 45_000L
        private const val REPLY_TIMEOUT_MILLIS = 15_000L

        private val STAMP = Regex("""(\d{8})T(\d{6})(Z|[+-]\d{4})?""")
        private val STAMP_DIGITS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
