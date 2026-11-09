package com.andrerinas.openheadunit.utils

import java.io.File
import java.net.NetworkInterface

/**
 * Whether a network interface is switched on, whether or not it has a carrier yet.
 *
 * Android's `NetworkInterface.isUp()` asks for IFF_UP and IFF_RUNNING together, and some Wi-Fi
 * drivers report no carrier on an access point until a device joins it. Unisoc's sprdwl does, on
 * FYT head units: with the hotspot on air, `wlan0` holds 192.168.43.1 with operstate "down". An
 * access point found by isUp() there is only found once the phone has joined, and the phone only
 * joins once it has been handed that access point: Android Auto never started. So an interface
 * counts as up here when it is IFF_UP, read from sysfs where Java won't say it.
 */
object InterfaceUp {

    private const val IFF_UP = 0x1

    /** Whether [nif] is up, running or not. */
    fun of(nif: NetworkInterface): Boolean {
        if (runCatching { nif.isUp }.getOrDefault(false)) return true
        return adminUp(nif.name)
    }

    /** Whether `/sys/class/net/<name>/flags` says [name] is IFF_UP; false when unreadable. */
    fun adminUp(name: String): Boolean = try {
        flagsSayUp(File("/sys/class/net/$name/flags").readText())
    } catch (e: Exception) {
        false
    }

    /** Whether sysfs's flags text ("0x1003") has IFF_UP set. Pure, for testing. */
    fun flagsSayUp(text: String): Boolean {
        val hex = text.trim().removePrefix("0x").removePrefix("0X")
        val flags = hex.toIntOrNull(16) ?: return false
        return flags and IFF_UP != 0
    }
}
