package com.vivekkaushik.revv.obd

import kotlin.math.roundToInt

/** Mode 01 PIDs Revv reads, and their SAE J1979 formulas. Each decoder returns null for a short reply. */
object ObdPid {
    const val SUPPORTED_01_20 = 0x00
    const val MONITOR_STATUS = 0x01
    const val ENGINE_LOAD = 0x04
    const val COOLANT_TEMP = 0x05
    const val INTAKE_PRESSURE = 0x0B
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val INTAKE_AIR_TEMP = 0x0F
    const val MAF = 0x10
    const val THROTTLE = 0x11
    const val FUEL_LEVEL = 0x2F
    const val AMBIENT_TEMP = 0x46

    /** Accelerator pedal position D: the pedal itself on drive-by-wire cars, ahead of the throttle. */
    const val ACCELERATOR_PEDAL = 0x49

    fun speed(data: ByteArray): Int? = data.byteAt(0)

    fun rpm(data: ByteArray): Int? {
        val a = data.byteAt(0) ?: return null
        val b = data.byteAt(1) ?: return null
        return (a * 256 + b) / 4
    }

    fun temperature(data: ByteArray): Int? = data.byteAt(0)?.minus(40)

    fun percent(data: ByteArray): Int? = data.byteAt(0)?.let { (it * 100f / 255).roundToInt() }

    fun pressure(data: ByteArray): Int? = data.byteAt(0)

    /** Mass air flow in grams per second. */
    fun maf(data: ByteArray): Float? {
        val a = data.byteAt(0) ?: return null
        val b = data.byteAt(1) ?: return null
        return (a * 256 + b) / 100f
    }

    /** Whether the check-engine light is on, from PID 01. */
    fun milOn(data: ByteArray): Boolean? = data.byteAt(0)?.let { it and 0x80 != 0 }

    /** How many trouble codes are stored, from PID 01. */
    fun troubleCodeCount(data: ByteArray): Int? = data.byteAt(0)?.let { it and 0x7F }

    private fun ByteArray.byteAt(index: Int): Int? = getOrNull(index)?.toInt()?.and(0xFF)
}
