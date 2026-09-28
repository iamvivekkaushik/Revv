package com.vivekkaushik.revv.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Looks for Bluetooth LE adapters, which usually aren't paired in Android's settings. Callers check
 * the scan permission first (see [BluetoothAccess.canScan]); without it scans simply don't start.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val _found = MutableStateFlow<List<ObdAdapter>>(emptyList())

    /** Named devices seen during the last scan, likely adapters first. */
    val found: StateFlow<List<ObdAdapter>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null

    /** Scans for a while, publishing devices as they appear. Call on the main thread. */
    fun start() {
        stop()
        val scanner = scanner(context) ?: return
        val devices = LinkedHashMap<String, ObdAdapter>()
        val likely = mutableSetOf<String>()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: return
                val address = result.device.address
                if (BluetoothAccess.looksLikeObd(name) ||
                    record?.serviceUuids.orEmpty().any { it.uuid in BleUart.advertisedServices }
                ) {
                    likely += address
                }
                if (devices.put(address, ObdAdapter.ble(address, name)) == null) {
                    _found.value = devices.values.sortedByDescending { it.address in likely }
                }
            }

            override fun onScanFailed(errorCode: Int) = stop()
        }
        _found.value = emptyList()
        try {
            scanner.startScan(null, lowLatency(), scanCallback)
        } catch (e: SecurityException) {
            return
        }
        callback = scanCallback
        _scanning.value = true
        handler.postDelayed(::stop, SCAN_MILLIS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val active = callback ?: return
        callback = null
        runCatching { scanner(context)?.stopScan(active) }
        _scanning.value = false
    }

    companion object {
        private const val SCAN_MILLIS = 12_000L
        private const val FIND_MILLIS = 6_000L

        /**
         * Blocks while scanning for [address]. A scanned device carries its address type, which a
         * connection by address alone can't know for adapters using random addresses.
         */
        fun find(context: Context, address: String): BluetoothDevice? {
            val scanner = scanner(context) ?: return null
            val seen = LinkedBlockingQueue<BluetoothDevice>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    seen.offer(result.device)
                }
            }
            return try {
                val filter = ScanFilter.Builder().setDeviceAddress(address).build()
                scanner.startScan(listOf(filter), lowLatency(), callback)
                seen.poll(FIND_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: SecurityException) {
                null
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
        }

        private fun scanner(context: Context): BluetoothLeScanner? {
            if (!BluetoothAccess.canScan(context)) return null
            val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            if (!bluetooth.isEnabled) return null
            return bluetooth.bluetoothLeScanner
        }

        private fun lowLatency(): ScanSettings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    }
}
