package com.vivekkaushik.revv.obd

/** Plain-English meanings for the trouble codes drivers most often meet. */
object TroubleCodes {

    /** The meaning of [code], or its system when it isn't in the table, e.g. "Manufacturer-specific powertrain code". */
    fun describe(code: String): String {
        val normal = code.trim().uppercase()
        return known[normal] ?: generic(normal)
    }

    private fun generic(code: String): String {
        val system = when (code.firstOrNull()) {
            'P' -> "powertrain"
            'C' -> "chassis"
            'B' -> "body"
            'U' -> "network"
            else -> return "Unknown code"
        }
        val specific = code.getOrNull(1) == '1' || code.getOrNull(1) == '3'
        return if (specific) "Manufacturer-specific $system code" else "Generic $system code"
    }

    private val known = mapOf(
        "P0011" to "Intake cam timing over-advanced",
        "P0016" to "Crank and cam position mismatch",
        "P0100" to "Air flow sensor circuit fault",
        "P0101" to "Air flow sensor out of range",
        "P0102" to "Air flow sensor low input",
        "P0105" to "Manifold pressure sensor circuit fault",
        "P0106" to "Manifold pressure sensor out of range",
        "P0110" to "Intake air temperature sensor fault",
        "P0115" to "Coolant temperature sensor fault",
        "P0116" to "Coolant temperature out of range",
        "P0117" to "Coolant temperature sensor low input",
        "P0118" to "Coolant temperature sensor high input",
        "P0120" to "Throttle position sensor fault",
        "P0121" to "Throttle position out of range",
        "P0125" to "Engine slow to reach operating temperature",
        "P0128" to "Thermostat stuck open",
        "P0130" to "Oxygen sensor 1 circuit fault",
        "P0133" to "Oxygen sensor 1 slow response",
        "P0135" to "Oxygen sensor 1 heater fault",
        "P0141" to "Oxygen sensor 2 heater fault",
        "P0171" to "Fuel mixture too lean",
        "P0172" to "Fuel mixture too rich",
        "P0174" to "Fuel mixture too lean, bank 2",
        "P0201" to "Cylinder 1 injector circuit fault",
        "P0202" to "Cylinder 2 injector circuit fault",
        "P0203" to "Cylinder 3 injector circuit fault",
        "P0204" to "Cylinder 4 injector circuit fault",
        "P0217" to "Engine overheating",
        "P0230" to "Fuel pump circuit fault",
        "P0300" to "Random misfire detected",
        "P0301" to "Cylinder 1 misfire",
        "P0302" to "Cylinder 2 misfire",
        "P0303" to "Cylinder 3 misfire",
        "P0304" to "Cylinder 4 misfire",
        "P0325" to "Knock sensor circuit fault",
        "P0335" to "Crankshaft position sensor fault",
        "P0340" to "Camshaft position sensor fault",
        "P0351" to "Ignition coil A circuit fault",
        "P0352" to "Ignition coil B circuit fault",
        "P0353" to "Ignition coil C circuit fault",
        "P0354" to "Ignition coil D circuit fault",
        "P0401" to "Exhaust gas recirculation flow low",
        "P0420" to "Catalytic converter efficiency low",
        "P0440" to "Fuel vapour system fault",
        "P0442" to "Fuel vapour leak (small)",
        "P0455" to "Fuel vapour leak (large). Check the fuel cap",
        "P0456" to "Fuel vapour leak (very small)",
        "P0500" to "Vehicle speed sensor fault",
        "P0505" to "Idle control system fault",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0600" to "Control module communication fault",
        "P0605" to "Control module memory fault",
        "P0700" to "Transmission control system fault",
        "U0100" to "Lost communication with the engine computer",
    )
}
