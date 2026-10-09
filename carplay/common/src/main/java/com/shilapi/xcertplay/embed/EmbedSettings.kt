package com.shilapi.xcertplay.embed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayBootstrap
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.messageResource
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.HotspotSwitch
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * The CarPlay settings the app's own settings screen (Revv's Settings › CarPlay) reads and
 * changes. Values live in [AirPlayPersistence] and [DiPlayPreferences] as before, so DiPlay's
 * full-screen CarPlay screen uses them too.
 */
object EmbedSettings {
    private const val TAG = "RevvCarPlay-Settings"
    private val RESOLUTIONS = listOf(10, 8, 6)
    /** Picked identity files are a few hundred bytes; anything bigger is not one. */
    const val MAX_FILE_BYTES = 16 * 1024

    // Settings [set] takes, with the value type and range each one allows.
    /** int, the CarPlay picture's assumed width in mm: 250 large, 300 medium, 350 small icons and text. */
    const val SETTING_CARPLAY_SIZE = "carPlaySize"
    /** int, tenths of the view's resolution the iPhone draws at: 10, 8 or 6. */
    const val SETTING_RESOLUTION = "resolution"
    /** int, 30 or 60. */
    const val SETTING_FRAME_RATE = "frameRate"
    /** boolean, HEVC video instead of H.264. */
    const val SETTING_HEVC = "hevc"
    /** boolean, CarPlay's controls on the right. */
    const val SETTING_RIGHT_HAND_DRIVE = "rightHandDrive"
    /** boolean, CarPlay media takes Android audio focus. */
    const val SETTING_AUDIO_FOCUS = "audioFocus"
    /** int 0–20: 0 routes media automatically, 1–20 pick a legacy Android stream. A preview tone plays on change. */
    const val SETTING_MEDIA_STREAM = "mediaStream"
    /** int 0–20, as SETTING_MEDIA_STREAM for navigation prompts. */
    const val SETTING_NAVIGATION_STREAM = "navigationStream"
    /** int, ms of music buffered: 300, 500 or 1000. */
    const val SETTING_MUSIC_BUFFER = "musicBuffer"
    /** boolean, usage/content-type audio routing; only when [Snapshot.advancedAudioAvailable]. */
    const val SETTING_ADVANCED_AUDIO = "advancedAudio"
    /** boolean, the head unit's GPS goes to the iPhone; needs the precise location permission. */
    const val SETTING_LOCATION_REPORTING = "locationReporting"

    /** Every setting's current value. */
    data class Snapshot(
        val identityInstalled: Boolean,
        val wireless: Boolean,
        /** [EmbeddedCarPlay.HOTSPOT_P2P] or [EmbeddedCarPlay.HOTSPOT_MANUAL]. */
        val hotspotMode: String,
        /** The saved car hotspot name, empty when none. */
        val hotspotSsid: String,
        /** The saved car hotspot details are complete and valid. */
        val hotspotReady: Boolean,
        /** The head unit's hotspot is on; null when the firmware hides its state. */
        val hotspotOn: Boolean?,
        /** The app may turn the hotspot on: Android 11+ and "Modify system settings" granted. */
        val hotspotSwitchAllowed: Boolean,
        val phoneAddress: String?,
        val phoneName: String,
        val carPlaySize: Int,
        val resolution: Int,
        val frameRate: Int,
        val hevc: Boolean,
        val rightHandDrive: Boolean,
        val audioFocus: Boolean,
        val mediaStream: Int,
        val navigationStream: Int,
        val musicBufferMillis: Int,
        val advancedAudioAvailable: Boolean,
        val advancedAudio: Boolean,
        val locationReporting: Boolean,
        /** The precise location permission location reporting needs is granted. */
        val locationPermitted: Boolean,
    )

    fun snapshot(context: Context): Snapshot {
        val ssid = AirPlayPersistence.loadManualHotspotSsid(context)
        return Snapshot(
            identityInstalled = DiPlayBootstrap.hasIdentity(context),
            wireless = AirPlayPersistence.loadWirelessEnabled(context),
            hotspotMode = if (AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL) EmbeddedCarPlay.HOTSPOT_MANUAL else EmbeddedCarPlay.HOTSPOT_P2P,
            hotspotSsid = ssid,
            hotspotReady = ManualHotspotValidation.error(ssid, AirPlayPersistence.loadManualHotspotPassphrase(context)) == null,
            hotspotOn = CarHotspotStatus.isEnabled(context),
            hotspotSwitchAllowed = HotspotSwitch.allowed(context),
            phoneAddress = DiPlayPreferences.phoneAddress(context),
            phoneName = DiPlayPreferences.phoneName(context),
            carPlaySize = CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(context)).widthMillimeters,
            resolution = AirPlayPersistence.loadDisplayScaleTenths(context),
            frameRate = if (AirPlayPersistence.loadFps(context) == 60) 60 else 30,
            hevc = AirPlayPersistence.loadHevcEnabled(context),
            rightHandDrive = AirPlayPersistence.loadRightHandDrive(context),
            audioFocus = AirPlayPersistence.loadAudioFocusEnabled(context),
            mediaStream = AirPlayPersistence.loadMediaAudioChannel(context),
            navigationStream = AirPlayPersistence.loadNavigationAudioChannel(context),
            musicBufferMillis = AirPlayPersistence.loadMediaBufferMillis(context),
            advancedAudioAvailable = advancedAudioAvailable(context),
            advancedAudio = AirPlayPersistence.loadAdvancedAudioChannelMapping(context),
            locationReporting = AirPlayPersistence.loadLocationReportingEnabled(context),
            locationPermitted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    /** Saves one setting (SETTING_*); false when the name or value is not one CarPlay takes. */
    fun set(context: Context, name: String, value: Any?): Boolean {
        val number = value as? Int
        val on = value as? Boolean
        when (name) {
            SETTING_CARPLAY_SIZE -> AirPlayPersistence.saveWidthPhysicalMm(context, CarPlaySize.fromWidthMillimeters(number ?: return false).widthMillimeters)
            SETTING_RESOLUTION -> {
                if (number == null || number !in RESOLUTIONS) return false
                AirPlayPersistence.saveDisplayScaleTenths(context, number)
            }
            SETTING_FRAME_RATE -> AirPlayPersistence.saveFps(context, if ((number ?: return false) == 60) 60 else 30)
            SETTING_HEVC -> AirPlayPersistence.saveHevcEnabled(context, on ?: return false)
            SETTING_RIGHT_HAND_DRIVE -> AirPlayPersistence.saveRightHandDrive(context, on ?: return false)
            SETTING_AUDIO_FOCUS -> AirPlayPersistence.saveAudioFocusEnabled(context, on ?: return false)
            SETTING_MEDIA_STREAM -> {
                if (number == null || number !in AirPlayPersistence.AUDIO_CHANNELS) return false
                AirPlayPersistence.saveMediaAudioChannel(context, number)
            }
            SETTING_NAVIGATION_STREAM -> {
                if (number == null || number !in AirPlayPersistence.AUDIO_CHANNELS) return false
                AirPlayPersistence.saveNavigationAudioChannel(context, number)
            }
            SETTING_MUSIC_BUFFER -> {
                if (number == null || number !in MediaAudioBuffer.presets) return false
                AirPlayPersistence.saveMediaBufferMillis(context, number)
            }
            SETTING_ADVANCED_AUDIO -> {
                if (!advancedAudioAvailable(context)) return false
                AirPlayPersistence.saveAdvancedAudioChannelMapping(context, on ?: return false)
            }
            SETTING_LOCATION_REPORTING -> AirPlayPersistence.saveLocationReportingEnabled(context, on ?: return false)
            else -> return false
        }
        Log.i(TAG, "$name = $value")
        return true
    }

    /** Saves the car hotspot's details; the error to show when they are not usable, else null. */
    fun saveHotspot(context: Context, ssid: String, passphrase: String): String? {
        val name = ssid.trim()
        ManualHotspotValidation.error(name, passphrase)?.let { return context.getString(it.messageResource()) }
        AirPlayPersistence.saveManualHotspotSsid(context, name)
        AirPlayPersistence.saveManualHotspotPassphrase(context, passphrase)
        AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotValidation.securityFor(passphrase))
        AirPlayPersistence.saveManualHotspotBand(context, ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(context, 0)
        Log.i(TAG, "car hotspot details saved")
        return null
    }

    fun savePhone(context: Context, address: String, name: String) {
        DiPlayPreferences.savePhone(context, address, name.ifBlank { "iPhone" })
    }

    /**
     * Installs the identity among [files] (name to bytes): identity.pk8 and certificate.p7b by name,
     * else the smaller file as the key. The message to show, and whether it worked.
     */
    fun importIdentity(context: Context, files: Map<String, ByteArray>): Pair<String, Boolean> {
        val picked = files.entries.filter { it.value.size <= MAX_FILE_BYTES }.map { it.key to it.value }
        var key = picked.firstOrNull { it.first.endsWith(".pk8", true) || it.first.endsWith(".key", true) }
        var cert = picked.firstOrNull { it.first.endsWith(".p7b", true) || it.first.endsWith(".cer", true) || it.first.endsWith(".der", true) }
        if ((key == null || cert == null) && picked.size >= 2) {
            val bySize = picked.sortedBy { it.second.size }
            if (key == null) key = bySize.first()
            if (cert == null) cert = bySize.last()
        }
        if (key == null || cert == null || key === cert) return context.getString(R.string.identity_pick_both) to false
        val chosen = mapOf("identity.pk8" to key.second, "certificate.p7b" to cert.second)
        val failure = runCatching { DiPlayBootstrap.import(context) { name -> chosen.getValue(name).inputStream() } }.exceptionOrNull()
        if (failure != null) {
            Log.e(TAG, "Identity import failed", failure)
            return context.getString(R.string.identity_import_failed) to false
        }
        Log.i(TAG, "identity imported")
        return context.getString(R.string.identity_imported) to true
    }

    /** Removes the installed identity; the line to tell the driver. CarPlay cannot start until another is imported. */
    fun removeIdentity(context: Context): String {
        DiPlayBootstrap.remove(context)
        Log.i(TAG, "identity removed")
        return context.getString(R.string.identity_missing)
    }

    /** What to tell the driver about turning the hotspot on, or null when it is on. */
    fun hotspotNotice(context: Context, result: HotspotSwitch.Result): String? = when (result) {
        HotspotSwitch.Result.ON, HotspotSwitch.Result.ALREADY_ON -> null
        HotspotSwitch.Result.NO_ACCESS -> context.getString(R.string.hotspot_switch_no_access)
        HotspotSwitch.Result.UNSUPPORTED -> context.getString(R.string.hotspot_switch_unsupported)
        HotspotSwitch.Result.FAILED -> context.getString(R.string.hotspot_switch_failed)
    }

    private fun advancedAudioAvailable(context: Context) =
        context.resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
}
