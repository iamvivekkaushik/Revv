package com.vivekkaushik.revv.phone

import android.content.Context
import com.vivekkaushik.revv.fyt.FytModule
import kotlinx.coroutines.delay

/**
 * The phone link on FYT head units (the com.syu.* firmware). There, FYT's own daemon runs Bluetooth
 * and Android's stack has no phones paired at all. Apps reach the phone through the Bluetooth module
 * of FYT's system service com.syu.ms, the way FYT's Bluetooth app does (docs/FYT_BLUETOOTH.md).
 *
 * [onUpdate] hears the service's updates on a binder thread. Call the rest on the main thread.
 */
class FytBluetooth(context: Context, private val onUpdate: (FytUpdate) -> Unit) {

    private val module = FytModule(
        context,
        FytModule.MODULE_BT,
        FytUpdate.FOLLOWED,
        onUpdate = { code, ints, strings -> onUpdate(FytUpdate(code, ints, strings)) },
        onLost = { onUpdate(FytUpdate.LOST) },
    )

    /** Whether the service is bound and has handed over its Bluetooth module. */
    val connected: Boolean get() = module.connected

    /** Binds com.syu.ms. False if this isn't an FYT unit or it wouldn't let Revv in. */
    fun bind(): Boolean = module.bind()

    fun unbind() = module.unbind()

    /** Has the phone dial [number]. False if the service isn't there to ask. */
    fun dial(number: String): Boolean = module.command(C_DIAL, strings = arrayOf(number))

    /** Ends the call, or turns down a ringing one: the head unit's red key. */
    fun hangUp(): Boolean = module.command(C_KEY, ints = intArrayOf(KEY_HANG))

    /** Has FYT read the phone's contacts and call history again, into Android's own providers. */
    fun downloadPhoneBook(): Boolean = module.command(C_DOWNLOAD_BOOK) && module.command(C_DOWNLOAD_CALLS)

    companion object {
        /**
         * Switches FYT's Bluetooth off and on again, with FYT's own power command (its Bluetooth
         * app has the switch but hides it on some units). On units relaying Android's Bluetooth to
         * FYT's stack, FYT's service turns Android's off and on with it, which brings that relay
         * back the way a restart of the head unit does. The phone drops and reconnects. False if
         * FYT's service wasn't reachable.
         */
        suspend fun restart(context: Context, log: (String) -> Unit = {}): Boolean {
            val module = FytModule(context, FytModule.MODULE_BT, intArrayOf(), onUpdate = { _, _, _ -> })
            if (!module.bind()) {
                log("Bluetooth restart: FYT's service wouldn't bind")
                return false
            }
            try {
                var waited = 0L
                while (!module.connected && waited < CONNECT_WAIT_MILLIS) {
                    delay(100)
                    waited += 100
                }
                if (!module.connected) {
                    log("Bluetooth restart: FYT's service didn't hand over its Bluetooth module in ${CONNECT_WAIT_MILLIS / 1000} s")
                    return false
                }
                log("Bluetooth restart: connected to FYT's Bluetooth module after $waited ms; switching it off")
                if (!module.command(C_BT_POWER, ints = intArrayOf(0))) {
                    log("Bluetooth restart: the off command didn't go through")
                    return false
                }
                delay(POWER_OFF_MILLIS)
                val on = module.command(C_BT_POWER, ints = intArrayOf(1))
                log(if (on) "Bluetooth restart: switched back on" else "Bluetooth restart: the on command didn't go through")
                return on
            } finally {
                // Let the last command leave before letting go of the service.
                delay(500)
                module.unbind()
            }
        }

        private const val CONNECT_WAIT_MILLIS = 5_000L
        private const val POWER_OFF_MILLIS = 4_000L

        /** FinalBt.C_BT_POWER_ON: [1] on, [0] off. */
        private const val C_BT_POWER = 29

        // FinalBt commands.
        private const val C_KEY = 6
        private const val C_DIAL = 7
        private const val C_DOWNLOAD_BOOK = 26
        private const val C_DOWNLOAD_CALLS = 35
        private const val KEY_HANG = 1

        /** Whether this head unit has FYT's service, and with it FYT's Bluetooth. */
        fun present(context: Context): Boolean = FytModule.present(context)
    }
}
