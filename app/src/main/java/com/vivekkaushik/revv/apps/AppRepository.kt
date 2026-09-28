package com.vivekkaushik.revv.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.DisplayMetrics
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Lists, draws and launches the apps installed across the user's profiles. */
class AppRepository(private val context: Context) : IconProvider {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val icons = object : LruCache<String, ImageBitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Every launchable activity, sorted by label and reloaded whenever a package changes. */
    val apps: Flow<List<LauncherApp>> = callbackFlow {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
            override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                changed(*packageNames)
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
                changed(*packageNames)

            private fun changed(vararg packageNames: String) {
                packageNames.forEach(::evictIcons)
                trySend(Unit)
            }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        trySend(Unit)
        awaitClose { launcherApps.unregisterCallback(callback) }
    }
        .conflate()
        .map { loadApps() }
        .flowOn(Dispatchers.Default)

    fun launch(app: LauncherApp, sourceBounds: Rect?, options: Bundle?): Boolean {
        val (component, user) = locate(app) ?: return false
        return try {
            launcherApps.startMainActivity(component, user, sourceBounds, options)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun openAppInfo(app: LauncherApp) {
        val (component, user) = locate(app) ?: return
        runCatching { launcherApps.startAppDetailsActivity(component, user, null, null) }
    }

    override fun cachedIcon(app: LauncherApp, sizePx: Int): ImageBitmap? = icons.get(iconKey(app, sizePx))

    override suspend fun loadIcon(app: LauncherApp, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = iconKey(app, sizePx)
        icons.get(key) ?: runCatching {
            val (component, user) = locate(app) ?: return@runCatching null
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
            launcherApps.resolveActivity(intent, user)
                ?.getBadgedIcon(iconDensity(sizePx))
                ?.toBitmap(sizePx, sizePx)
                ?.asImageBitmap()
        }.getOrNull()?.also { icons.put(key, it) }
    }

    private fun loadApps(): List<LauncherApp> {
        val collator = Collator.getInstance()
        return launcherApps.profiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            val activities = runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
            activities.map { info ->
                LauncherApp(
                    key = AppKey(info.componentName.flattenToString(), serial),
                    label = info.label.toString().trim(),
                )
            }
        }
            .filter { it.packageName != context.packageName }
            .sortedWith(compareBy(collator) { it.label })
    }

    private fun locate(app: LauncherApp): Pair<ComponentName, UserHandle>? {
        val component = ComponentName.unflattenFromString(app.key.component) ?: return null
        val user = userManager.getUserForSerialNumber(app.key.userSerial) ?: return null
        return component to user
    }

    private fun evictIcons(packageName: String) {
        icons.snapshot().keys.filter { it.startsWith("$packageName/") }.forEach(icons::remove)
    }

    private fun iconKey(app: LauncherApp, sizePx: Int) = "${app.key.serialize()}@$sizePx"

    private companion object {
        const val ICON_CACHE_BYTES = 16 * 1024 * 1024

        val DENSITY_BUCKETS = intArrayOf(
            DisplayMetrics.DENSITY_MEDIUM,
            DisplayMetrics.DENSITY_HIGH,
            DisplayMetrics.DENSITY_XHIGH,
            DisplayMetrics.DENSITY_XXHIGH,
            DisplayMetrics.DENSITY_XXXHIGH,
        )

        /** Icon resources dense enough to stay sharp at [sizePx]. Launcher icons are designed at 48dp. */
        fun iconDensity(sizePx: Int): Int {
            val wanted = sizePx * DisplayMetrics.DENSITY_DEFAULT / 48
            return DENSITY_BUCKETS.firstOrNull { it >= wanted } ?: DENSITY_BUCKETS.last()
        }
    }
}
