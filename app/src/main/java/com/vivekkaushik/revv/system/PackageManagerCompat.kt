package com.vivekkaushik.revv.system

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * The package that would handle [intent]: the user's default if one is set, otherwise the first
 * app that can. Null when nothing can.
 */
fun PackageManager.defaultHandlerPackage(intent: Intent): String? {
    val preferred = resolveActivityCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    // With several candidates and no default, the system chooser (package "android") comes back.
    if (preferred != null && preferred != "android") return preferred
    return queryIntentActivitiesCompat(intent, 0).firstOrNull()?.activityInfo?.packageName
}

fun PackageManager.resolveActivityCompat(intent: Intent, flags: Int): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, flags)
    }

fun PackageManager.queryIntentActivitiesCompat(intent: Intent, flags: Int): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, flags)
    }

fun PackageManager.applicationLabelOrNull(packageName: String): String? = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }
    getApplicationLabel(info).toString()
} catch (e: PackageManager.NameNotFoundException) {
    null
}
