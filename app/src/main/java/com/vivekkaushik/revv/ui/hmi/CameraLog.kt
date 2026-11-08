package com.vivekkaushik.revv.ui.hmi

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What the rear camera did, for working out why a head unit's camera won't show. Debug builds
 * write it next to the OBD adapter log in Revv's external folder, where it can be read on head
 * units with no adb (over a terminal app's SSH, say); every build also sends it to logcat.
 */
internal object CameraLog {
    private const val TAG = "RevvCamera"
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "camera-log").apply { isDaemon = true } }
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    fun add(context: Context, line: String) {
        Log.i(TAG, line)
        val app = context.applicationContext
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val now = Date()
        writer.execute {
            runCatching {
                val dir = app.getExternalFilesDir("logs") ?: return@runCatching
                File(dir, "camera-${day.format(now)}.log").appendText("${time.format(now)}  $line\n")
            }
        }
    }

    fun error(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "in use by another app"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "disabled by policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service error"
        else -> "error $code"
    }

    fun reason(e: CameraAccessException): String = when (e.reason) {
        CameraAccessException.CAMERA_IN_USE -> "in use by another app"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraAccessException.CAMERA_DISABLED -> "disabled by policy"
        CameraAccessException.CAMERA_DISCONNECTED -> "disconnected"
        CameraAccessException.CAMERA_ERROR -> "camera error"
        else -> "reason ${e.reason}"
    } + (e.message?.let { ": $it" } ?: "")
}

/** How many times a Rear Cam camera has started showing a picture, for watchdogs outside the screen. */
internal object RearCameraFeed {
    @Volatile var pictures = 0
        private set

    fun onPicture() {
        pictures++
    }
}
