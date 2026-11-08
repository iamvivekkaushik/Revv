package com.vivekkaushik.revv.fyt

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reverse gear on FYT head units, as FYT's service reports it to its own reverse view (the main
 * module's U_BACKCAR update, non-zero while reversing), and the switch that keeps that view from
 * opening so Revv's Rear Cam can show instead.
 *
 * FYT skips its own reverse view while persist.syu.backui3rd is true, which its Factory settings set
 * for a third-party 360° camera app. Revv only sets it while it is on screen, and clears it when it
 * leaves, so FYT's own view still shows the camera whenever Revv isn't there to.
 */
class FytReverse(context: Context, private val onRaw: (Int) -> Unit = {}) {

    private val _reversing = MutableStateFlow(false)

    /** Whether the car is in reverse, while [start]ed. */
    val reversing: StateFlow<Boolean> = _reversing.asStateFlow()

    private val module = FytModule(
        context,
        FytModule.MODULE_MAIN,
        intArrayOf(U_BACKCAR),
        onUpdate = { code, ints, _ ->
            if (code == U_BACKCAR) ints?.firstOrNull()?.let {
                onRaw(it)
                _reversing.value = it != 0
            }
        },
        onLost = { _reversing.value = false },
    )

    fun start(): Boolean = module.bind()

    fun stop() {
        module.unbind()
        _reversing.value = false
    }

    companion object {
        private const val TAG = "FytReverse"

        /** FinalMain.U_BACKCAR. */
        private const val U_BACKCAR = 12

        /** What FYT's reverse view checks before showing. */
        private const val THIRD_PARTY_VIEW = "persist.syu.backui3rd"

        /** Whether this is an FYT unit with its reverse camera handling. */
        fun present(context: Context): Boolean = FytModule.present(context) && File("/sys/lsecdcamctl").exists()

        /**
         * Keeps FYT's own reverse view from opening ([on]) or lets it open again. Returns whether
         * the property now says so: FYT units with SELinux enforcing refuse apps this, and then
         * FYT's view keeps showing. Blocks briefly; call off the main thread.
         */
        fun holdBackOwnView(on: Boolean, log: (String) -> Unit = {}): Boolean {
            val wanted = if (on) "true" else "false"
            val said = run("/system/bin/setprop", THIRD_PARTY_VIEW, wanted)
            val now = run("/system/bin/getprop", THIRD_PARTY_VIEW)
            val done = now == wanted || (!on && now.isNullOrEmpty())
            val line = "FYT reverse view ${if (on) "off" else "on"}: setprop $THIRD_PARTY_VIEW $wanted" +
                (said?.takeIf(String::isNotEmpty)?.let { " said \"$it\"" } ?: "") + ", now \"$now\"" + if (done) "" else " — refused"
            if (!done) Log.w(TAG, line)
            log(line)
            return done
        }

        private fun run(vararg command: String): String? = runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            output
        }.getOrNull()
    }
}
