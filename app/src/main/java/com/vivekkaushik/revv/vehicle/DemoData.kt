package com.vivekkaushik.revv.vehicle

/**
 * Placeholder content from the "Swift HMI v4" design: the demo drive's car figures, plus weather,
 * which Revv can't read yet.
 */
object DemoData {
    const val WEATHER = "31°C"
    const val CITY = "GURUGRAM"

    const val RANGE_KM = "412"
    const val FUEL_LITRES = "24"
    const val FUEL_BARS = 6
    const val AVERAGE_KMPL = "21.6"
    const val COOLANT_C = "89"
    const val INTAKE_AIR_C = "41"
    const val BATTERY_VOLTS = "14.2"

    const val TRIP_A_KM = "128.4"
    const val DRIVE_TIME = "3:12"

    data class Tyre(val position: String, val psi: Int, val temperature: String)

    val tyres = listOf(
        Tyre("FRONT L", 33, "31 °C"),
        Tyre("FRONT R", 33, "32 °C"),
        Tyre("REAR L", 32, "30 °C"),
        Tyre("REAR R", 32, "30 °C"),
    )
}
