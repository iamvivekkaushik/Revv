package com.vivekkaushik.revv.apps

/** A launchable activity, possibly in a work profile. */
data class LauncherApp(val key: AppKey, val label: String) {
    val packageName: String get() = key.packageName
}

/**
 * Persistable identity of a [LauncherApp]: a flattened component name plus the owning user's
 * serial number, which (unlike a UserHandle) stays the same across reboots.
 */
data class AppKey(val component: String, val userSerial: Long) {

    val packageName: String get() = component.substringBefore('/')

    fun serialize(): String = "$component#$userSerial"

    companion object {
        fun parse(value: String): AppKey? {
            val component = value.substringBeforeLast('#', missingDelimiterValue = "")
            val serial = value.substringAfterLast('#', missingDelimiterValue = "").toLongOrNull()
            if (component.isEmpty() || serial == null) return null
            return AppKey(component, serial)
        }
    }
}
