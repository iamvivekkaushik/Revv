package com.vivekkaushik.revv.obd

/** Where a Wi-Fi ELM327 listens for TCP connections. */
data class WifiEndpoint(val host: String, val port: Int) {

    override fun toString() = "$host:$port"

    companion object {
        /** Nearly every Wi-Fi ELM327 clone listens here on its own network. */
        val Default = WifiEndpoint("192.168.0.10", 35000)

        private const val TELNET_PORT = 23

        /** "255.255.255.255:65535", the longest address worth typing. */
        private const val MAX_TYPED_LENGTH = 21

        /**
         * Addresses worth trying, best guesses first: the last one that worked, the usual
         * default, then the network's gateway, which is the adapter itself when it runs the network.
         */
        fun candidates(lastWorking: WifiEndpoint?, gateway: String?): List<WifiEndpoint> = buildList {
            lastWorking?.let(::add)
            add(Default)
            if (gateway != null) {
                add(WifiEndpoint(gateway, Default.port))
                add(WifiEndpoint(gateway, TELNET_PORT))
            }
            add(WifiEndpoint(Default.host, TELNET_PORT))
        }.distinct()

        /**
         * Reads an address like "192.168.0.10" or "192.168.0.10:35000"; the port defaults to
         * 35000. Only IPv4: an adapter's network has no DNS to look names up with.
         */
        fun parse(text: String): WifiEndpoint? {
            val parts = text.trim().split(':')
            if (parts.size > 2) return null
            val octets = parts[0].split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.toIntOrNull() !in 0..255 }) {
                return null
            }
            val port = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else Default.port
            if (port !in 1..65535) return null
            return WifiEndpoint(octets.joinToString(".") { it.toInt().toString() }, port)
        }

        /**
         * Adds a keypad [key] to a partly typed address, ignoring keys that can't lead to a valid
         * one: a dot where a number belongs, a fifth number, or a port before the address is whole.
         */
        fun type(text: String, key: String): String {
            if (text.length >= MAX_TYPED_LENGTH) return text
            val dots = text.count { it == '.' }
            val typingPort = ':' in text
            val afterNumber = text.isNotEmpty() && text.last().isDigit()
            return when (key) {
                "." -> if (!typingPort && dots < 3 && afterNumber) text + key else text
                ":" -> if (!typingPort && dots == 3 && afterNumber) text + key else text
                else -> text + key
            }
        }
    }
}
