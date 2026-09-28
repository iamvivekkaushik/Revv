package com.vivekkaushik.revv.system

import android.content.Context
import androidx.core.content.edit

/** Remembers one-time events for this install, such as the first-launch home prompt. */
class FirstRun(context: Context) {

    private val prefs = context.getSharedPreferences("first_run", Context.MODE_PRIVATE)

    /** True the first time it's called for [event] on this install, false ever after. */
    fun claim(event: String): Boolean {
        if (prefs.getBoolean(event, false)) return false
        prefs.edit { putBoolean(event, true) }
        return true
    }
}
