package com.vivekkaushik.revv.system

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.vivekkaushik.revv.apps.LauncherApp

/**
 * Finds the real apps behind the HMI's built-in screens. Head units ship very different app sets,
 * so these fall back to matching app labels.
 */
object ExternalApps {

    private val projectionLabels = listOf("android auto", "autokit", "zlink", "tlink", "carlink", "carplay")

    /** The device's default navigation app. */
    fun navigationIntent(context: Context): Intent? {
        val pkg = context.packageManager.defaultHandlerPackage(Intent(Intent.ACTION_VIEW, "geo:0,0?q=".toUri()))
            ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)
    }

    /** The phone-projection receiver: Android Auto itself, or the head unit's own AA/CarPlay app. */
    fun findProjectionApp(apps: List<LauncherApp>): LauncherApp? =
        apps.firstOrNull { app -> projectionLabels.any { app.label.contains(it, ignoreCase = true) } }

    fun findRadioApp(apps: List<LauncherApp>): LauncherApp? =
        apps.firstOrNull { it.label.contains("radio", ignoreCase = true) }
}
