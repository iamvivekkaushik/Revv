package com.vivekkaushik.revv.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothAccess {

    private val obdNameHints = listOf(
        "obd", "elm", "v-link", "vlink", "vgate", "icar", "konnwei", "veepeak", "carista", "lelink", "scan",
    )

    /** Whether Revv may talk to Bluetooth devices; Android 12+ asks the user for this. */
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || has(context, Manifest.permission.BLUETOOTH_CONNECT)

    /** Whether Revv may scan for Bluetooth LE devices: "Nearby devices" on Android 12+, location before. */
    fun canScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            has(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** What to ask for before scanning. Connecting comes along on Android 12+, from the same group. */
    fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Before Android 12, Bluetooth LE scans find nothing while location services are switched off. */
    fun locationBlocksScanning(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val location = context.getSystemService(LocationManager::class.java) ?: return false
        return !location.isLocationEnabled
    }

    /** Paired devices, likely OBD-II adapters first; empty without permission or Bluetooth. */
    @SuppressLint("MissingPermission")
    fun pairedAdapters(context: Context): List<ObdAdapter> {
        if (!granted(context)) return emptyList()
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val adapters = try {
            bluetooth.bondedDevices.orEmpty().map { device ->
                val name = device.name ?: device.address
                // Dual-mode adapters get the serial port profile, the ELM327's native link.
                if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                    ObdAdapter.ble(device.address, name)
                } else {
                    ObdAdapter.bluetooth(device.address, name)
                }
            }
        } catch (e: SecurityException) {
            emptyList()
        }
        return adapters.sortedWith(compareByDescending<ObdAdapter> { looksLikeObd(it.name) }.thenBy { it.name.lowercase() })
    }

    /** How many devices are paired, or null while Bluetooth is off or Revv may not look. */
    @SuppressLint("MissingPermission")
    fun pairedCount(context: Context): Int? {
        if (!granted(context)) return null
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return try {
            if (bluetooth.isEnabled) bluetooth.bondedDevices.orEmpty().size else null
        } catch (e: SecurityException) {
            null
        }
    }

    fun looksLikeObd(name: String): Boolean = obdNameHints.any { name.contains(it, ignoreCase = true) }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
