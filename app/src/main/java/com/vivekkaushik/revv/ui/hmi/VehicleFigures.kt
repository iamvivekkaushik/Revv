package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.obd.ObdReadings
import com.vivekkaushik.revv.obd.ObdStatus
import com.vivekkaushik.revv.vehicle.DemoData
import com.vivekkaushik.revv.vehicle.VehicleProfile
import java.util.Locale
import kotlin.math.roundToInt

/** Where the car's numbers come from right now. */
enum class DataSource {
    /** The demo drive, used only while no OBD-II adapter is set up. */
    Demo,

    /** A live OBD-II adapter. */
    Obd,

    /** The virtual GPS adapter: speed from GPS, the gear, revs and load worked out from it. */
    Gps,

    /** Nothing: the adapter isn't answering, or the demo is off. Shown as dashes, never as fake data. */
    None,
}

enum class Health { Normal, Alert, Unknown }

/** Slow-changing car figures for the fuel card, vehicle screen and status bar, ready to display. */
data class VehicleFigures(
    val coolant: String = DASH,
    val intakeAir: String = DASH,
    val battery: String = DASH,
    val outsideTemperature: String? = null,
    val outsidePlace: String = "",
    val fuelBars: Int = 0,
    val range: String = DASH,
    val fuelDetail: String = "NO FUEL DATA",
    val tripKm: String = DASH,
    val averageKmpl: String = DASH,
    val driveTime: String = DASH,
    val fuelLitres: String = DASH,
    /** Tyre pressures aren't available over OBD-II, so only the demo has them. */
    val tyres: List<DemoData.Tyre>? = null,
    val health: Health = Health.Unknown,
    val healthText: String = "NO VEHICLE DATA",
    val troubleCodes: List<String> = emptyList(),
    /** Faults not yet confirmed; shown as a heads-up only. */
    val pendingCodes: List<String> = emptyList(),
    /** Whether there is a check-engine light or fault codes that clearing could erase. */
    val clearable: Boolean = false,
) {
    companion object {
        const val DASH = "--"

        /** A healthy battery reads about 12.4 V at rest and 13.5 V or more while charging. */
        const val LOW_BATTERY_VOLTS = 12.0f

        val Empty = VehicleFigures()

        val Demo = VehicleFigures(
            coolant = DemoData.COOLANT_C,
            intakeAir = DemoData.INTAKE_AIR_C,
            battery = DemoData.BATTERY_VOLTS,
            outsideTemperature = DemoData.WEATHER,
            outsidePlace = DemoData.CITY,
            fuelBars = DemoData.FUEL_BARS,
            range = DemoData.RANGE_KM,
            fuelDetail = "${DemoData.FUEL_LITRES} L · AVG ${DemoData.AVERAGE_KMPL} KM/L",
            tripKm = DemoData.TRIP_A_KM,
            averageKmpl = DemoData.AVERAGE_KMPL,
            driveTime = DemoData.DRIVE_TIME,
            fuelLitres = DemoData.FUEL_LITRES,
            tyres = DemoData.tyres,
            health = Health.Normal,
            healthText = "ALL SYSTEMS NORMAL",
        )

        fun live(readings: ObdReadings, status: ObdStatus, profile: VehicleProfile): VehicleFigures {
            val litres = readings.fuelLevel?.let { it * profile.tankLitres / 100f }
            val average = readings.tripAverageKmPerLitre
            val averageText = average?.let(::oneDecimal) ?: DASH
            val codeCount = maxOf(status.troubleCodeCount, status.troubleCodes.size)
            val engineAlert = status.milOn || codeCount > 0
            val battery = readings.batteryVolts?.let(::oneDecimal) ?: DASH
            val batteryLow = readings.batteryVolts?.let { it < LOW_BATTERY_VOLTS } == true
            val alert = engineAlert || batteryLow
            return VehicleFigures(
                coolant = readings.coolantC?.toString() ?: DASH,
                intakeAir = readings.intakeAirC?.toString() ?: DASH,
                battery = battery,
                outsideTemperature = readings.ambientC?.let { "$it°C" },
                outsidePlace = "OUTSIDE",
                fuelBars = readings.fuelLevel?.let { (it / 10f).roundToInt() } ?: 0,
                // Until this trip has measured its own economy, assume a typical figure for the car.
                range = litres?.let { (it * (average ?: profile.typicalKmPerLitre)).roundToInt().toString() } ?: DASH,
                fuelDetail = if (litres != null) {
                    "${litres.roundToInt()} L · AVG $averageText KM/L"
                } else {
                    "NO FUEL LEVEL · AVG $averageText KM/L"
                },
                tripKm = oneDecimal(readings.tripKm),
                averageKmpl = averageText,
                driveTime = hoursAndMinutes(readings.tripEngineSeconds),
                fuelLitres = litres?.roundToInt()?.toString() ?: DASH,
                // GPS knows nothing of the car's health, so it mustn't call it normal.
                health = when {
                    status.estimated -> Health.Unknown
                    alert -> Health.Alert
                    else -> Health.Normal
                },
                healthText = when {
                    status.estimated -> "GPS ONLY · NO DIAGNOSTICS"
                    !alert -> "ALL SYSTEMS NORMAL"
                    !engineAlert -> "LOW BATTERY · $battery V"
                    codeCount == 0 -> "CHECK ENGINE"
                    codeCount == 1 -> "CHECK ENGINE · 1 CODE"
                    else -> "CHECK ENGINE · $codeCount CODES"
                },
                troubleCodes = status.troubleCodes,
                pendingCodes = status.pendingCodes,
                clearable = engineAlert || status.pendingCodes.isNotEmpty(),
            )
        }

        private fun oneDecimal(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

        private fun hoursAndMinutes(seconds: Long): String =
            "${seconds / 3600}:${(seconds % 3600 / 60).toString().padStart(2, '0')}"
    }
}

/** A value with its unit, e.g. "89 °C", or just the dash when it's unknown. */
fun withUnit(value: String, unit: String): String = if (value == VehicleFigures.DASH) value else "$value $unit"
