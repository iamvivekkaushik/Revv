package com.shilapi.xcertplay.network

import android.content.Context
import android.net.TetheringManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Turns the head unit's own Wi-Fi hotspot on, for the "Car hotspot" link. Android 16 makes the
 * switch (TetheringManager.startTethering) a public API; on Android 11 to 15 it is a system API,
 * reached by plain reflection, which works on firmware that leaves hidden APIs open and fails
 * cleanly ([Result.UNSUPPORTED]) where Android hides it, in which case the driver turns the
 * hotspot on in the head unit's settings. Nothing here bypasses Android's hidden-API checks: the
 * library that did (AndroidHiddenApiBypass) relies on runtime internals that newer ART updates
 * change, and Play flags apps that ship it. Either way the app needs "Modify system settings" and
 * the carrier must ask for no tethering check. The hotspot's name and password stay whatever the
 * head unit's settings say.
 */
object HotspotSwitch {
    enum class Result {
        ON,
        ALREADY_ON,
        /** "Modify system settings" is not granted to this app. */
        NO_ACCESS,
        /** Android older than 11, or a firmware that hides the switch. */
        UNSUPPORTED,
        /** The system refused, e.g. a tethering check the carrier requires. */
        FAILED,
    }

    private const val TAG = "RevvCarPlay-Hotspot"
    private const val TETHERING_WIFI = 0
    private const val TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION = 14
    private val main = Handler(Looper.getMainLooper())

    /** Whether this app may switch the hotspot: Android 11+ and "Modify system settings" granted. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Settings.System.canWrite(context)

    /** Turns the hotspot on unless it already is. [done] gets the outcome on the main thread. */
    fun turnOn(context: Context, done: (Result) -> Unit = {}) {
        val app = context.applicationContext
        when {
            CarHotspotStatus.isEnabled(app) == true -> done(Result.ALREADY_ON)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> done(Result.UNSUPPORTED)
            !Settings.System.canWrite(app) -> done(Result.NO_ACCESS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> runCatching { start(app) { result -> main.post { done(result) } } }.onFailure {
                Log.w(TAG, "the hotspot switch refused", it)
                done(Result.FAILED)
            }
            else -> runCatching { startByReflection(app) { result -> main.post { done(result) } } }.onFailure {
                Log.w(TAG, "the hotspot switch is not reachable on this firmware", it)
                done(Result.UNSUPPORTED)
            }
        }
    }

    /** Which result a tethering error means, once "Modify system settings" is known to be granted. */
    private fun resultOf(context: Context, error: Int?): Result {
        Log.w(TAG, "hotspot refused, tethering error $error")
        // A permission refusal here means the carrier requires a tethering check, which only
        // system apps may skip; asking the driver for the setting again would not help.
        return if (error == TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION && !Settings.System.canWrite(context)) Result.NO_ACCESS else Result.FAILED
    }

    /** Android 16 and newer: the public API. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun start(context: Context, reply: (Result) -> Unit) {
        val manager = context.getSystemService(TetheringManager::class.java) ?: error("No tethering service")
        val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
        manager.startTethering(request, Executor { it.run() }, object : TetheringManager.StartTetheringCallback {
            override fun onTetheringStarted() {
                Log.i(TAG, "hotspot turned on")
                reply(Result.ON)
            }

            override fun onTetheringFailed(error: Int) {
                reply(resultOf(context, error))
            }
        })
    }

    /** Android 11 to 15: the same call, a system API there, by reflection; throws where Android hides it. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun startByReflection(context: Context, reply: (Result) -> Unit) {
        val manager = context.getSystemService("tethering") ?: error("No tethering service")
        val managerClass = Class.forName("android.net.TetheringManager")
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val builder = builderClass.getConstructor(Int::class.java).newInstance(TETHERING_WIFI)
        builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.java).invoke(builder, false)
        val request = builderClass.getMethod("build").invoke(builder)
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> {
                    Log.i(TAG, "hotspot turned on")
                    reply(Result.ON)
                    null
                }
                "onTetheringFailed" -> {
                    reply(resultOf(context, args?.firstOrNull() as? Int))
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "HotspotSwitch callback"
                else -> null
            }
        }
        managerClass.getMethod("startTethering", requestClass, Executor::class.java, callbackClass)
            .invoke(manager, request, Executor { it.run() }, callback)
    }
}
