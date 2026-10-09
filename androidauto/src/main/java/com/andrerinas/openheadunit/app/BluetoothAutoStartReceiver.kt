package com.andrerinas.openheadunit.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The phone chosen for wireless Android Auto connected over Bluetooth (the driver got in): the
 * session is started, or woken, so the phone joins the head unit's Wi-Fi without anyone tapping.
 * Only with a wireless link chosen, and only when the host allows it ([App.allowsBluetoothAutoStart]).
 * Android's own Bluetooth broadcast lets the service start from here. After DiAuto's AutoStartReceiver.
 */
class BluetoothAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val settings = App.settings(context)
        if (settings.wifiConnectionMode != 3) return
        val address = device.address ?: return
        if (address !in settings.autoStartBluetoothDeviceMacs) return
        if (App.allowsBluetoothAutoStart?.invoke() == false) {
            AppLog.i("BluetoothAutoStart: the chosen phone connected, but the host is not showing Android Auto")
            return
        }
        if (App.provide(context).commManager.isConnected) return
        // The head unit's own wake raises this very connection: one wake at a time.
        if (!App.mayWakePhone()) return
        AppLog.i("BluetoothAutoStart: the chosen phone connected over Bluetooth; waking it for wireless Android Auto")
        val wake = Intent(context, AapService::class.java)
            .setAction(AapService.ACTION_NATIVE_AA_POKE)
            .putExtra(AapService.EXTRA_MAC, address)
        runCatching { ContextCompat.startForegroundService(context, wake) }
            .onFailure { AppLog.w("BluetoothAutoStart: could not start the service: ${it.message}") }
    }
}
