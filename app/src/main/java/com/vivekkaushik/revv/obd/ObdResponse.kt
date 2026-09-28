package com.vivekkaushik.revv.obd

/**
 * Parses ELM327 replies, configured with echo, spaces and headers off (ATE0, ATS0, ATH0).
 *
 * Replies are lines of hex such as `410D32`, possibly after `SEARCHING...` or `BUS INIT: ...OK`.
 * Longer CAN replies arrive as ISO-TP frames: a byte count line (`00A`), then `0:`, `1:`, ...
 */
object ObdResponse {

    private val byteCount = Regex("[0-9A-F]{3}")
    private val frame = Regex("[0-9A-F]:([0-9A-F]+)")
    private val hexBytes = Regex("(?:[0-9A-F]{2})+")

    private val protocols = mapOf(
        1 to "SAE J1850 PWM",
        2 to "SAE J1850 VPW",
        3 to "ISO 9141-2",
        4 to "ISO 14230-4 KWP (slow init)",
        5 to "ISO 14230-4 KWP (fast init)",
        6 to "ISO 15765-4 CAN (11 bit, 500 kbaud)",
        7 to "ISO 15765-4 CAN (29 bit, 500 kbaud)",
        8 to "ISO 15765-4 CAN (11 bit, 250 kbaud)",
        9 to "ISO 15765-4 CAN (29 bit, 250 kbaud)",
        10 to "SAE J1939 CAN",
    )

    /** Lines that carry content, upper-cased with spaces removed. */
    fun lines(raw: String): List<String> = raw.split('\r', '\n')
        .map { it.replace(" ", "").trim().uppercase() }
        .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && !it.startsWith("BUSINIT") }

    /** Each response message as one hex string, with multi-frame CAN replies stitched together. */
    fun messages(raw: String): List<String> {
        val messages = mutableListOf<String>()
        var assembling: StringBuilder? = null
        var expectedChars = 0
        fun finishAssembly() {
            assembling?.let { messages += it.toString().take(expectedChars) }
            assembling = null
        }
        for (line in lines(raw)) {
            val continuation = frame.matchEntire(line)
            when {
                byteCount.matches(line) -> {
                    finishAssembly()
                    expectedChars = line.toInt(16) * 2
                    assembling = StringBuilder()
                }
                continuation != null && assembling != null -> assembling!!.append(continuation.groupValues[1])
                hexBytes.matches(line) -> {
                    finishAssembly()
                    messages += line
                }
            }
        }
        finishAssembly()
        return messages
    }

    /** The data bytes answering [mode] and [pid], or null if the car sent none (`NO DATA`, errors). */
    fun payload(raw: String, mode: Int, pid: Int): ByteArray? {
        val prefix = hex(mode + 0x40) + hex(pid)
        val message = messages(raw).firstOrNull { it.startsWith(prefix) } ?: return null
        return bytes(message.substring(prefix.length))
    }

    /** PIDs flagged in a "supported PIDs" bitmask whose range starts after [base] (0x00, 0x20, ...). */
    fun supportedPids(bitmask: ByteArray, base: Int): Set<Int> = buildSet {
        bitmask.take(4).forEachIndexed { index, byte ->
            for (bit in 0 until 8) {
                if (byte.toInt() and (0x80 ushr bit) != 0) add(base + index * 8 + bit + 1)
            }
        }
    }

    /** Protocol number from an `ATDPN` reply such as `A6` (A means it was found automatically). */
    fun protocolNumber(raw: String): Int? = lines(raw).firstOrNull()?.lastOrNull()?.digitToIntOrNull(16)

    fun protocolName(number: Int): String = protocols[number] ?: "Protocol $number"

    fun isCan(protocolNumber: Int): Boolean = protocolNumber in 6..9

    /** Battery voltage from an `ATRV` reply such as `12.6V`. */
    fun voltage(raw: String): Float? = lines(raw).firstOrNull()?.removeSuffix("V")?.toFloatOrNull()

    /**
     * Stored trouble codes from a mode 03 reply. CAN replies start with a code count; the older
     * protocols always send three code slots per line, padded with zeros.
     */
    fun troubleCodes(raw: String, can: Boolean): List<String> {
        val codes = mutableListOf<String>()
        for (message in messages(raw)) {
            if (!message.startsWith("43")) continue
            var data = bytes(message.substring(2))
            if (can && data.isNotEmpty()) data = data.copyOfRange(1, data.size)
            for (i in 0 until data.size - 1 step 2) {
                val high = data[i].toInt() and 0xFF
                val low = data[i + 1].toInt() and 0xFF
                if (high != 0 || low != 0) codes += troubleCode(high, low)
            }
        }
        return codes.distinct()
    }

    /** Decodes two bytes into a code like P0171: two bits of system, then four hex digits. */
    fun troubleCode(high: Int, low: Int): String {
        val system = "PCBU"[high shr 6]
        val firstDigit = (high shr 4) and 0x03
        val secondDigit = (high and 0x0F).toString(16)
        return "$system$firstDigit$secondDigit${hex(low)}".uppercase()
    }

    fun hex(value: Int): String = value.toString(16).padStart(2, '0').uppercase()

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
