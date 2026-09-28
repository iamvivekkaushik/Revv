package com.vivekkaushik.revv.vehicle

/**
 * Placeholder content from the "Swift HMI v4" design: the demo drive's car figures, plus the paired
 * phone and weather, which Revv can't read yet.
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

    const val MODEL = "SWIFT VXi 2015"
    const val ENGINE = "K12M 1.2 PETROL"
    const val GEARBOX = "5MT"
    const val TRIP_A_KM = "128.4"
    const val DRIVE_TIME = "3:12"

    const val PHONE = "Pixel 8"
    const val PHONE_BATTERY = "74%"
    const val PHONE_NETWORK = "JIO 4G"

    data class Call(val name: String, val detail: String, val missed: Boolean)

    val recents = listOf(
        Call("Aarav Mehta", "MISSED · 12 MIN AGO", missed = true),
        Call("Priya", "OUTGOING · 09:40", missed = false),
        Call("Maa", "INCOMING · YESTERDAY", missed = false),
        Call("Rohan", "OUTGOING · YESTERDAY", missed = false),
        Call("Dr. Sethi", "OUTGOING · WED", missed = false),
    )

    val favourites = listOf("Priya", "Rohan", "Maa", "Papa", "Aarav", "Office")

    data class PairedPhone(val name: String, val detail: String, val connected: Boolean)

    val pairedPhones = listOf(
        PairedPhone("Pixel 8", "Wireless · Android Auto ready", connected = true),
        PairedPhone("Galaxy S23", "Last used 3 days ago", connected = false),
    )

    data class Tyre(val position: String, val psi: Int, val temperature: String)

    val tyres = listOf(
        Tyre("FRONT L", 33, "31 °C"),
        Tyre("FRONT R", 33, "32 °C"),
        Tyre("REAR L", 32, "30 °C"),
        Tyre("REAR R", 32, "30 °C"),
    )
}
