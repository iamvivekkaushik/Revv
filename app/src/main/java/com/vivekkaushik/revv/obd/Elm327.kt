package com.vivekkaushik.revv.obd

/**
 * Speaks the ELM327 command set over a [transport]. Calls block and must come from one thread
 * at a time: the adapter handles a single command until it prints its prompt. [trace] hears every
 * command with the reply it got (null if none came), for the adapter log.
 */
class Elm327(
    private val transport: ObdTransport,
    private val trace: (command: String, reply: String?) -> Unit = { _, _ -> },
) {

    /** The bus protocol found by [connectToEcu], e.g. "ISO 15765-4 CAN (11 bit, 500 kbaud)". */
    var protocolName: String? = null
        private set

    /** That protocol's ELM327 number, 1 to 9, worth trying first next time. */
    var protocolNumber: Int? = null
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

    /**
     * Resets the adapter and configures compact, header-free replies. The protocol search starts
     * with [preferredProtocol], the one that worked last time, and goes on to the rest if it fails.
     */
    fun initialize(preferredProtocol: Int? = null) {
        // Clones answer a reset inconsistently; all that matters is that the prompt comes back.
        command("ATZ", RESET_TIMEOUT_MILLIS)
        // Some clones print their prompt before they're ready for the next command.
        Thread.sleep(RESET_SETTLE_MILLIS)
        for (setting in SETUP) command(setting)
        command(if (preferredProtocol != null) "ATSPA${protocolDigit(preferredProtocol)}" else "ATSP0")
    }

    /**
     * Wakes the car's ECU, letting the adapter search for the bus protocol; with [probe], tries
     * each protocol in turn if that search fails. Returns the mode 01 PIDs the car supports, or
     * null when nothing answers (usually because the ignition is off).
     */
    fun connectToEcu(probe: Boolean = false): Set<Int>? {
        var bitmask = query(0x01, ObdPid.SUPPORTED_01_20, SEARCH_TIMEOUT_MILLIS)
            ?: (if (probe) probeProtocols() else null)
            ?: return null
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
        protocolNumber = protocol
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

    /**
     * Asks for the supported PIDs on each protocol in turn. Cheap clones often fail their own
     * automatic search on K-line cars (ISO 9141-2 and KWP2000, common on older Indian and Japanese
     * cars) yet talk fine once told which protocol to use. Leaves the adapter on the protocol that
     * answered, or searching automatically again if none did.
     */
    private fun probeProtocols(): ByteArray? {
        for (protocol in PROBE_ORDER) {
            command("ATSP${protocolDigit(protocol)}")
            // A K-line bus has to be woken first, which takes seconds on the slow inits.
            val timeout = if (protocol in K_LINE) K_LINE_TIMEOUT_MILLIS else PROBE_TIMEOUT_MILLIS
            query(0x01, ObdPid.SUPPORTED_01_20, timeout)?.let { return it }
        }
        command("ATSP0")
        return null
    }

    private fun query(mode: Int, pid: Int, timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS): ByteArray? {
        val reply = command(ObdResponse.hex(mode) + ObdResponse.hex(pid), timeoutMillis) ?: return null
        return ObdResponse.payload(reply, mode, pid)
    }

    private fun command(text: String, timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS): String? {
        transport.discard().takeIf(String::isNotBlank)?.let { trace(LEFTOVER, it) }
        transport.send(text)
        var reply = transport.receive(timeoutMillis)
        // An answer that can't be this command's is an earlier one arriving late: keep reading.
        var late = 0
        while (reply != null && !answers(text, reply) && late++ < MAX_LATE_REPLIES) {
            trace(LEFTOVER, reply)
            reply = transport.receive(timeoutMillis)
        }
        trace(text, reply)
        // A late reply would be mistaken for the answer to the next command; wait out its prompt.
        if (reply == null) transport.receive(RESYNC_TIMEOUT_MILLIS)
        return reply
    }

    /**
     * Whether [reply] can be the answer to [command]. A setting never gets a bus result such as
     * "UNABLE TO CONNECT", and a request to the car never gets a bare "OK" or a voltage.
     */
    private fun answers(command: String, reply: String): Boolean {
        if (command.startsWith("AT")) {
            val compact = reply.replace(" ", "").uppercase()
            return BUS_RESULTS.none { compact.contains(it) }
        }
        val lines = ObdResponse.lines(reply)
        return lines.isEmpty() || lines.any { line -> line != "OK" && !line.startsWith("ELM") && !VOLTAGE.matches(line) }
    }

    private fun protocolDigit(protocol: Int) = protocol.toString(16).uppercase()

    private companion object {
        /** Echo off, linefeeds off, spaces off, headers off, adaptive timing. */
        val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")

        /** Words in the reset banners of ELM327s, their clones and STN chips. */
        val ELM_BANNERS = listOf("ELM", "OBD", "STN")

        /** What the adapter says after talking to the car, spaces removed. */
        val BUS_RESULTS = listOf(
            "UNABLETOCONNECT", "BUSINIT", "NODATA", "STOPPED", "SEARCHING", "CANERROR", "BUSERROR", "BUSBUSY", "FBERROR", "DATAERROR",
        )
        val VOLTAGE = Regex("""\d{1,2}\.\d{1,2}V""")

        /** How the adapter log shows replies nothing asked for. */
        const val LEFTOVER = "(leftover)"
        const val MAX_LATE_REPLIES = 2

        /**
         * CAN first, as most cars since about 2008 use it and it fails in a fraction of a second;
         * then K-line, whose slow wake-ups take seconds; J1850 (US cars) last.
         */
        val PROBE_ORDER = listOf(6, 8, 7, 9, 5, 4, 3, 2, 1)
        val K_LINE = setOf(3, 4, 5)

        const val RESET_TIMEOUT_MILLIS = 3_000L
        const val RESET_SETTLE_MILLIS = 300L
        const val COMMAND_TIMEOUT_MILLIS = 2_000L
        const val SEARCH_TIMEOUT_MILLIS = 20_000L
        const val PROBE_TIMEOUT_MILLIS = 5_000L
        const val K_LINE_TIMEOUT_MILLIS = 12_000L
        const val RESYNC_TIMEOUT_MILLIS = 1_000L
    }
}
