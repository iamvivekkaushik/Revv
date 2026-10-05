package com.vivekkaushik.revv.obd

/**
 * An ELM327 adapter Revv can connect to. [address] is a MAC address for Bluetooth adapters, and
 * for Wi-Fi either "auto" or an IP address and port the user typed in.
 */
data class ObdAdapter(val kind: Kind, val address: String, val name: String) {

    /** [Bluetooth] is Classic (serial port profile); [BluetoothLe] talks over GATT. */
    enum class Kind { Bluetooth, BluetoothLe, WiFi, Simulated }

    /** The typed-in address of a Wi-Fi adapter; null when Revv finds it by itself. */
    val wifiEndpoint: WifiEndpoint?
        get() = if (kind == Kind.WiFi) WifiEndpoint.parse(address) else null

    companion object {
        /** A Wi-Fi adapter, found on whichever Wi-Fi network the head unit has joined. */
        val WiFi = ObdAdapter(Kind.WiFi, "auto", "Wi-Fi ELM327")

        val Simulated = ObdAdapter(Kind.Simulated, "simulated", "Simulated ELM327 (debug build)")

        fun bluetooth(address: String, name: String) = ObdAdapter(Kind.Bluetooth, address, name)

        fun ble(address: String, name: String) = ObdAdapter(Kind.BluetoothLe, address, name)

        /** A Wi-Fi adapter at a set address, for clones that don't use the usual one. */
        fun wifiAt(endpoint: WifiEndpoint) = ObdAdapter(Kind.WiFi, endpoint.toString(), WiFi.name)

        /** Rebuilds a saved adapter; ones saved before Wi-Fi support have no kind. */
        fun restore(kind: String?, address: String, name: String): ObdAdapter {
            val restoredKind = Kind.entries.firstOrNull { it.name == kind }
                ?: if (address == Simulated.address) Kind.Simulated else Kind.Bluetooth
            return ObdAdapter(restoredKind, address, name)
        }
    }
}

enum class ObdLink {
    /** No adapter chosen. */
    Off,
    NeedsPermission,
    BluetoothOff,

    /** A Wi-Fi adapter is chosen but the head unit hasn't joined any Wi-Fi network. */
    NoWifi,
    Connecting,

    /** The adapter answers but the car doesn't, usually because the ignition is off. */
    NoEcu,
    Live,

    /** The link dropped or failed; Revv tries again shortly. */
    Retrying,
}

/** Slow-changing connection facts, safe to show directly in the UI. */
data class ObdStatus(
    val link: ObdLink = ObdLink.Off,
    val adapterName: String? = null,
    val protocol: String? = null,
    /** Where a Wi-Fi adapter was found, e.g. "192.168.0.10:35000". */
    val endpoint: String? = null,
    val problem: String? = null,
    /** Measured by the adapter, so known even when the car doesn't answer. */
    val batteryVolts: Float? = null,
    val milOn: Boolean = false,
    val troubleCodeCount: Int = 0,
    val troubleCodes: List<String> = emptyList(),
    /** Faults seen once but not yet confirmed; they do not light the check-engine light. */
    val pendingCodes: List<String> = emptyList(),
)

/** The latest live values; null fields weren't reported. Updated several times a second. */
data class ObdReadings(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val intakeAirC: Int? = null,
    val ambientC: Int? = null,
    val engineLoad: Int? = null,
    val throttle: Int? = null,
    /**
     * How far the accelerator is down, percent: the pedal where the car reports it, else the
     * throttle. Read every cycle with [rpm], before anything else.
     */
    val pedal: Int? = null,
    /** When [rpm] and [pedal] were read, on the SystemClock.elapsedRealtimeNanos clock. */
    val engineAtNanos: Long = 0,
    /** How full the cylinders are, from manifold pressure or air flow: 1 is wide open. Read every cycle, unlike [engineLoad]. */
    val airFill: Float? = null,
    val fuelLevel: Int? = null,
    val batteryVolts: Float? = null,
    /** Instantaneous economy; null while stationary or when fuel flow can't be worked out. */
    val kmPerLitre: Float? = null,
    /** Estimated forward gear, 0 for neutral (standing still), or null while the car's gears aren't known. */
    val gear: Int? = null,
    val tripKm: Float = 0f,
    val tripFuelLitres: Float = 0f,
    val tripAverageKmPerLitre: Float? = null,
    val tripEngineSeconds: Long = 0,
)
