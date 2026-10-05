package com.vivekkaushik.revv.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** A position report, from the head unit's GPS or the demo car. */
data class Fix(
    val position: LatLon,
    /** Degrees; null when unknown, e.g. while stopped. */
    val bearing: Double?,
    val speedMps: Double?,
    val accuracyMetres: Double?,
    val timeMillis: Long,
    /** When it was measured, on the SystemClock.elapsedRealtimeNanos clock; 0 when not known. */
    val elapsedNanos: Long = 0,
)

/**
 * The head unit's own GPS through Android's LocationManager, so it works without Google Play
 * services. Network fixes fill in only while GPS is silent.
 */
class DeviceLocation(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var lastGpsFix = 0L

    val permitted: Boolean get() = permitted(context)

    val enabled: Boolean get() = manager?.isLocationEnabled == true

    /** Starts reporting fixes on the main thread; [onChange] hears when location is switched on or off. */
    @SuppressLint("MissingPermission")
    fun start(onFix: (Fix) -> Unit, onChange: () -> Unit) {
        stop()
        val manager = manager ?: return
        if (!permitted) return
        // Every method is implemented: before Android 11 they had no defaults, so a lambda would crash.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val now = SystemClock.elapsedRealtime()
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    lastGpsFix = now
                } else if (now - lastGpsFix < GPS_PREFERRED_MILLIS) {
                    return
                }
                onFix(location.toFix())
            }

            override fun onProviderEnabled(provider: String) = onChange()

            override fun onProviderDisabled(provider: String) = onChange()

            @Deprecated("Only called before Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        this.listener = listener
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in manager.allProviders) continue
            try {
                manager.requestLocationUpdates(provider, UPDATE_MILLIS, 0f, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // Approximate location only: GPS is off limits, the network provider still works.
            } catch (e: IllegalArgumentException) {
                // The provider went away.
            }
        }
        // Show where the car was parked straight away rather than waiting for a first fix.
        lastKnown()?.let(onFix)
    }

    fun stop() {
        listener?.let { manager?.removeUpdates(it) }
        listener = null
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Fix? {
        val manager = manager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    private fun Location.toFix() = Fix(
        position = LatLon(latitude, longitude),
        bearing = if (hasBearing()) bearing.toDouble() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        accuracyMetres = if (hasAccuracy()) accuracy.toDouble() else null,
        timeMillis = time,
        elapsedNanos = elapsedRealtimeNanos,
    )

    companion object {
        private const val UPDATE_MILLIS = 1_000L
        private const val GPS_PREFERRED_MILLIS = 10_000L

        fun permitted(context: Context): Boolean =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        fun enabled(context: Context): Boolean =
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }
}
