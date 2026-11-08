package com.vivekkaushik.revv.ui.hmi

import java.io.File

/**
 * The reversing camera on FYT head units (the com.syu.* firmware) comes through a video decoder
 * chip (a TP2850, say), not a camera sensor. FYT's camera apps tell its driver the picture size in
 * the sys.fyt.cvbs.* properties before opening it, and ask for exactly that size: without them the
 * driver starts no stream at all, and asking for another size the driver lists gets none either
 * (docs/FYT_BLUETOOTH.md). After a restart they stay unset until something sets them.
 */
internal object FytCameraDecoder {

    /** Whether this is an FYT unit, by its camera control nodes. */
    fun present(): Boolean = File(FYT_CAMERA_CONTROL).exists()

    /** The decoder's picture size as FYT set it, or 720p when nothing has yet; null off FYT units. */
    fun pictureSize(): Pair<Int, Int>? {
        if (!present()) return null
        return setSize() ?: DEFAULT
    }

    /**
     * Sets the decoder up the way FYT's camera code does before opening it, unless something
     * already has. Returns a line for the camera log. Blocks briefly; call off the main thread.
     */
    fun prepare(): String? {
        if (!present()) return null
        setSize()?.let { (width, height) -> return "FYT decoder already set to ${width}x$height" }
        val (width, height) = DEFAULT
        val values = listOf(
            "width" to width, "height" to height,
            "trim_width" to width, "trim_height" to height,
            "scaler_trim.w" to width, "scaler_trim.h" to height,
            "scaler_trim.x" to 0, "scaler_trim.y" to 0,
        )
        for ((key, value) in values) run("/system/bin/setprop", "$PREFIX$key", value.toString())
        val now = setSize()
        return if (now != null) "FYT decoder set to ${now.first}x${now.second}" else "FYT decoder size couldn't be set: this unit refused setprop"
    }

    private fun setSize(): Pair<Int, Int>? {
        val width = run("/system/bin/getprop", "${PREFIX}width")?.toIntOrNull()
        val height = run("/system/bin/getprop", "${PREFIX}height")?.toIntOrNull()
        return if (width != null && height != null && width > 0 && height > 0) width to height else null
    }

    /** Runs a system tool: Android keeps its own property reader and writer from apps. */
    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output.ifEmpty { null }
    }.getOrNull()

    /** FYT's camera control nodes, present on every FYT unit. */
    private const val FYT_CAMERA_CONTROL = "/sys/lsecdcamctl"
    private const val PREFIX = "sys.fyt.cvbs."
    private val DEFAULT = 1280 to 720
}
