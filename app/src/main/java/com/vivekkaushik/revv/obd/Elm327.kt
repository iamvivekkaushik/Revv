package com.vivekkaushik.revv.obd

/**
 * Speaks the ELM327 command set over a [transport]. Calls block and must come from one thread
 * at a time: the adapter handles a single command until it prints its prompt.
 */
class Elm327(private val transport: ObdTransport) {

    /** The bus protocol found by [connectToEcu], e.g. "ISO 15765-4 CAN (11 bit, 500 kbaud)". */
    var protocolName: String? = null
        private set

    private var canBus = false

    /**
     * Resets whatever is listening and checks it answers like an ELM327 ("ELM327 v1.5", or an
     * STN chip's compatible banner). An open TCP port alone could just as well be a router.
     */
    fun looksLikeElm327(): Boolean {
        val banner = command("ATZ", RESET_TIMEOUT_MILLIS) ?: return false
        return ObdResponse.lines(banner).any { line -> ELM_BANNERS.any { line.contains(it) } }
    }

    /** Resets the adapter and configures compact, header-free replies with automatic protocol search. */
    fun initialize() {
        // Clones answer a reset inconsistently; all that matters is that the prompt comes back.
        command("ATZ", RESET_TIMEOUT_MILLIS)
        for (setting in SETUP) command(setting)
    }

    /**
     * Wakes the car's ECU, letting the adapter search for the bus protocol. Returns the mode 01
     * PIDs the car supports, or null when nothing answers (usually because the ignition is off).
     */
    fun connectToEcu(): Set<Int>? {
        var bitmask = query(0x01, ObdPid.SUPPORTED_01_20, SEARCH_TIMEOUT_MILLIS) ?: return null
        val supported = mutableSetOf<Int>()
        var base = 0
        while (true) {
            supported += ObdResponse.supportedPids(bitmask, base)
            // The last PID of each range says whether the next range can be asked for.
            val next = base + 0x20
            if (next !in supported || next > 0x40) break
            bitmask = query(0x01, next) ?: break
            base = next
        }
        val protocol = command("ATDPN")?.let(ObdResponse::protocolNumber)
        canBus = protocol != null && ObdResponse.isCan(protocol)
        protocolName = protocol?.let(ObdResponse::protocolName)
        return supported
    }

    /** The data bytes for mode 01 [pid], or null if the car didn't answer. */
    fun readPid(pid: Int): ByteArray? = query(0x01, pid)

    /** Voltage at the OBD port, measured by the adapter itself; works with the ignition off. */
    fun readVoltage(): Float? = command("ATRV")?.let(ObdResponse::voltage)

    fun readTroubleCodes(): List<String> =
        command("03", SEARCH_TIMEOUT_MILLIS)?.let { ObdResponse.troubleCodes(it, canBus) }.orEmpty()

    private fun query(mode: Int, pid: Int, timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS): ByteArray? {
        val reply = command(ObdResponse.hex(mode) + ObdResponse.hex(pid), timeoutMillis) ?: return null
        return ObdResponse.payload(reply, mode, pid)
    }

    private fun command(text: String, timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS): String? {
        transport.send(text)
        val reply = transport.receive(timeoutMillis)
        // A late reply would be mistaken for the answer to the next command; wait out its prompt.
        if (reply == null) transport.receive(RESYNC_TIMEOUT_MILLIS)
        return reply
    }

    private companion object {
        /** Echo off, linefeeds off, spaces off, headers off, adaptive timing, automatic protocol. */
        val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")

        /** Words in the reset banners of ELM327s, their clones and STN chips. */
        val ELM_BANNERS = listOf("ELM", "OBD", "STN")

        const val RESET_TIMEOUT_MILLIS = 3_000L
        const val COMMAND_TIMEOUT_MILLIS = 2_000L
        const val SEARCH_TIMEOUT_MILLIS = 20_000L
        const val RESYNC_TIMEOUT_MILLIS = 1_000L
    }
}
