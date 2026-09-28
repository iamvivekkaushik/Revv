package com.vivekkaushik.revv.obd

import java.util.UUID

/** The GATT characteristics a Bluetooth LE ELM327 uses as its serial port. */
data class BleUartProfile(val service: UUID, val notify: UUID, val write: UUID)

/** A characteristic found during service discovery, reduced to what choosing a profile needs. */
data class GattCharacteristicInfo(val service: UUID, val uuid: UUID, val properties: Int) {
    val canNotify: Boolean get() = properties and (PROPERTY_NOTIFY or PROPERTY_INDICATE) != 0
    val canWrite: Boolean get() = properties and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE) != 0

    companion object {
        // Same values as BluetoothGattCharacteristic's, kept here so this stays plain Kotlin.
        const val PROPERTY_WRITE_NO_RESPONSE = 0x04
        const val PROPERTY_WRITE = 0x08
        const val PROPERTY_NOTIFY = 0x10
        const val PROPERTY_INDICATE = 0x20
    }
}

object BleUart {

    /** Layouts used by real adapters, most common first. */
    val KNOWN = listOf(
        // Most clones and the Vgate iCar Pro BLE: notify on FFF1, write to FFF2.
        BleUartProfile(uuid16(0xFFF0), uuid16(0xFFF1), uuid16(0xFFF2)),
        // HM-10 style modules: one characteristic both ways.
        BleUartProfile(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)),
        // Vgate and Veepeak BLE+ adapters.
        BleUartProfile(
            UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
            UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
            UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
        ),
        // Microchip's transparent UART, used by several branded adapters.
        BleUartProfile(
            UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455"),
            UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616"),
            UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3"),
        ),
    )

    /** Generic Access, Generic Attribute, Device Information and Battery never carry the serial link. */
    private val standardServices = setOf(uuid16(0x1800), uuid16(0x1801), uuid16(0x180A), uuid16(0x180F))

    /**
     * Serial layouts worth trying on a device, best first: known ones it offers, then any other
     * service with something to listen to and something to write to.
     */
    fun candidates(characteristics: List<GattCharacteristicInfo>): List<BleUartProfile> {
        val known = KNOWN.filter { profile ->
            characteristics.any { it.service == profile.service && it.uuid == profile.notify && it.canNotify } &&
                characteristics.any { it.service == profile.service && it.uuid == profile.write && it.canWrite }
        }
        val knownServices = known.map { it.service }.toSet()
        val guessed = characteristics
            .filter { it.service !in standardServices && it.service !in knownServices }
            .groupBy { it.service }
            .mapNotNull { (service, found) ->
                val notify = found.firstOrNull { it.canNotify } ?: return@mapNotNull null
                // A characteristic that does both is the classic serial layout; prefer it.
                val write = found.firstOrNull { it.canWrite && it.uuid == notify.uuid } ?: found.firstOrNull { it.canWrite }
                write?.let { BleUartProfile(service, notify.uuid, it.uuid) }
            }
        return known + guessed
    }

    /** Expands a 16-bit Bluetooth SIG short UUID. */
    fun uuid16(short: Int): UUID =
        UUID.fromString("0000${short.toString(16).padStart(4, '0')}-0000-1000-8000-00805F9B34FB")

    /** Service UUIDs an adapter might advertise, used to flag likely adapters in scan results. */
    val advertisedServices: Set<UUID> = KNOWN.map { it.service }.toSet()
}
