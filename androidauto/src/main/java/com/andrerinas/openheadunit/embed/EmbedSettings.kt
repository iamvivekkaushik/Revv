package com.andrerinas.openheadunit.embed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.PlaybackFocusPolicy
import com.andrerinas.openheadunit.utils.Settings

/**
 * The Android Auto settings the app's own settings screen (Revv's Settings › Android Auto) reads
 * and changes. Values live in the stack's [Settings] as before, under the names it reads them by.
 */
object EmbedSettings {
    private const val TAG = "RevvAndroidAuto-Settings"

    /** int: [WIRELESS_OFF], [WIRELESS_WIFI_DIRECT] or [WIRELESS_CAR_HOTSPOT]. USB is always on. */
    const val SETTING_WIRELESS = "wireless"
    /** int: [Settings.Resolution] id, 0 for the size that fits the view. */
    const val SETTING_RESOLUTION = "resolution"
    /** int, 30 or 60. */
    const val SETTING_FRAME_RATE = "frameRate"
    /** int, percent of the display's density the phone lays its interface out for: bigger is bigger. */
    const val SETTING_SIZE = "size"
    /** int: [CODEC_AUTO], [CODEC_H264] or [CODEC_H265]. */
    const val SETTING_CODEC = "codec"
    /** boolean, the phone's controls on the right. */
    const val SETTING_RIGHT_HAND_DRIVE = "rightHandDrive"
    /** boolean, music stays on the car's Bluetooth instead of coming over Android Auto. */
    const val SETTING_MUSIC_VIA_BLUETOOTH = "musicViaBluetooth"
    /** int: [PlaybackFocusPolicy.Mode] value, whether Android Auto's audio takes Android's audio focus. */
    const val SETTING_FOCUS_MODE = "focusMode"
    /** boolean, the head unit's GPS goes to the phone; needs the precise location permission. */
    const val SETTING_GPS_TO_PHONE = "gpsToPhone"
    /** int: [NIGHT_AUTO] by the sun, [NIGHT_DAY], [NIGHT_NIGHT] or [NIGHT_SENSOR] by the light sensor. */
    const val SETTING_NIGHT_MODE = "nightMode"
    /** boolean, the microphone's echo canceller for the assistant and calls. */
    const val SETTING_ECHO_CANCEL = "echoCancel"
    /** boolean, the microphone's noise suppressor. */
    const val SETTING_NOISE_SUPPRESSION = "noiseSuppression"

    const val WIRELESS_OFF = 0
    const val WIRELESS_WIFI_DIRECT = 1
    const val WIRELESS_CAR_HOTSPOT = 2

    const val CODEC_AUTO = 0
    const val CODEC_H264 = 1
    const val CODEC_H265 = 2

    const val NIGHT_AUTO = 0
    const val NIGHT_DAY = 1
    const val NIGHT_NIGHT = 2
    const val NIGHT_SENSOR = 3

    val RESOLUTIONS = listOf(0, 1, 2, 3)
    val FRAME_RATES = listOf(30, 60)
    val SIZES = listOf(80, 100, 120)

    /** Every setting's current value. */
    data class Snapshot(
        val wireless: Int,
        /** The car hotspot's saved name, empty when none. */
        val hotspotSsid: String,
        /** The car hotspot's details are saved and usable. */
        val hotspotReady: Boolean,
        /** The paired phone the wireless link wakes, by its Bluetooth address; null for any paired phone. */
        val phoneAddress: String?,
        val phoneName: String,
        val resolution: Int,
        val frameRate: Int,
        val sizePercent: Int,
        val codec: Int,
        val rightHandDrive: Boolean,
        val musicViaBluetooth: Boolean,
        val focusMode: Int,
        val gpsToPhone: Boolean,
        val nightMode: Int,
        val echoCancel: Boolean,
        val noiseSuppression: Boolean,
        /** The precise location permission GPS to the phone needs is granted. */
        val locationPermitted: Boolean,
    )

    fun snapshot(context: Context): Snapshot {
        val settings = App.settings(context)
        val address = settings.autoStartBluetoothDeviceMacs.firstOrNull()
        return Snapshot(
            wireless = wirelessOf(settings),
            hotspotSsid = settings.hotspotSsid,
            hotspotReady = hotspotError(settings.hotspotSsid, settings.hotspotPassword) == null,
            phoneAddress = address,
            phoneName = settings.autoStartBluetoothDeviceName.ifBlank { address.orEmpty() },
            resolution = settings.resolutionId.takeIf { it in RESOLUTIONS } ?: 0,
            frameRate = if (settings.fpsLimit == 30) 30 else 60,
            sizePercent = settings.uiSizePercent,
            codec = when (settings.videoCodec) {
                "H.264" -> CODEC_H264
                "H.265" -> CODEC_H265
                else -> CODEC_AUTO
            },
            rightHandDrive = settings.rightHandDrive,
            musicViaBluetooth = settings.musicViaBluetooth,
            focusMode = settings.playbackFocusMode.value,
            gpsToPhone = settings.useGpsForNavigation,
            nightMode = when (settings.nightMode) {
                Settings.NightMode.DAY -> NIGHT_DAY
                Settings.NightMode.NIGHT -> NIGHT_NIGHT
                Settings.NightMode.LIGHT_SENSOR -> NIGHT_SENSOR
                else -> NIGHT_AUTO
            },
            echoCancel = settings.micEchoCanceler,
            noiseSuppression = settings.micNoiseSuppressor,
            locationPermitted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun wirelessOf(settings: Settings): Int = when {
        settings.wifiConnectionMode != 3 -> WIRELESS_OFF
        settings.nativeApTransport == 1 -> WIRELESS_CAR_HOTSPOT
        else -> WIRELESS_WIFI_DIRECT
    }

    /** Saves one setting (SETTING_*); false when the name or value is not one Android Auto takes. */
    fun set(context: Context, name: String, value: Any?): Boolean {
        val settings = App.settings(context)
        val number = value as? Int
        val on = value as? Boolean
        when (name) {
            SETTING_WIRELESS -> when (number) {
                WIRELESS_OFF -> settings.wifiConnectionMode = 0
                WIRELESS_WIFI_DIRECT -> {
                    settings.wifiConnectionMode = 3
                    settings.nativeApTransport = 0
                }
                WIRELESS_CAR_HOTSPOT -> {
                    settings.wifiConnectionMode = 3
                    settings.nativeApTransport = 1
                }
                else -> return false
            }
            SETTING_RESOLUTION -> {
                if (number == null || number !in RESOLUTIONS) return false
                settings.resolutionId = number
            }
            SETTING_FRAME_RATE -> settings.fpsLimit = if ((number ?: return false) == 30) 30 else 60
            SETTING_SIZE -> settings.uiSizePercent = number ?: return false
            SETTING_CODEC -> settings.videoCodec = when (number) {
                CODEC_H264 -> "H.264"
                CODEC_H265 -> "H.265"
                CODEC_AUTO -> "Auto"
                else -> return false
            }
            SETTING_RIGHT_HAND_DRIVE -> settings.rightHandDrive = on ?: return false
            SETTING_MUSIC_VIA_BLUETOOTH -> settings.musicViaBluetooth = on ?: return false
            SETTING_FOCUS_MODE -> settings.playbackFocusMode = PlaybackFocusPolicy.Mode.fromInt(number ?: return false)
            SETTING_GPS_TO_PHONE -> settings.useGpsForNavigation = on ?: return false
            SETTING_NIGHT_MODE -> settings.nightMode = when (number) {
                NIGHT_DAY -> Settings.NightMode.DAY
                NIGHT_NIGHT -> Settings.NightMode.NIGHT
                NIGHT_SENSOR -> Settings.NightMode.LIGHT_SENSOR
                NIGHT_AUTO -> Settings.NightMode.AUTO
                else -> return false
            }
            SETTING_ECHO_CANCEL -> settings.micEchoCanceler = on ?: return false
            SETTING_NOISE_SUPPRESSION -> settings.micNoiseSuppressor = on ?: return false
            else -> return false
        }
        Log.i(TAG, "$name = $value")
        return true
    }

    /** The paired phone the wireless link wakes; false for an address that is not a Bluetooth one. */
    fun savePhone(context: Context, address: String, name: String): Boolean {
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) return false
        val settings = App.settings(context)
        settings.autoStartBluetoothDeviceMacs = setOf(address)
        settings.autoStartBluetoothDeviceName = name.ifBlank { address }
        Settings.syncAutoStartBtMacsToDeviceStorage(context, setOf(address))
        Log.i(TAG, "phone saved")
        return true
    }

    /** Forgets the chosen phone: the wireless link wakes any paired phone. */
    fun forgetPhone(context: Context) {
        val settings = App.settings(context)
        settings.autoStartBluetoothDeviceMacs = emptySet()
        settings.autoStartBluetoothDeviceName = ""
        Settings.syncAutoStartBtMacsToDeviceStorage(context, emptySet())
    }

    /** Saves the car hotspot's details and links over it; the error to show when they are not usable, else null. */
    fun saveHotspot(context: Context, ssid: String, password: String): String? {
        val name = ssid.trim()
        hotspotError(name, password)?.let { return it }
        val settings = App.settings(context)
        settings.hotspotSsid = name
        settings.hotspotPassword = password
        settings.wifiConnectionMode = 3
        settings.nativeApTransport = 1
        Log.i(TAG, "car hotspot details saved")
        return null
    }

    /** What is wrong with these hotspot details, or null when they are usable. */
    fun hotspotError(name: String, password: String): String? = when {
        name.isBlank() -> "Enter the car hotspot's name."
        name.trim().encodeToByteArray().size > 32 -> "The name can be 32 bytes at most."
        password.isNotEmpty() && password.length !in 8..63 -> "The password needs 8 to 63 characters, or none for an open hotspot."
        else -> null
    }

    /**
     * The runtime permissions the wireless link needs: Bluetooth to wake the phone and nearby
     * devices (precise location before Android 13) for the Wi-Fi Direct group it joins.
     */
    fun wirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
