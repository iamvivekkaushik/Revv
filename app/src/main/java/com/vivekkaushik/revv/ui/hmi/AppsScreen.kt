package com.vivekkaushik.revv.ui.hmi

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.apps.IconProvider
import com.vivekkaushik.revv.apps.LauncherApp

val LocalIconProvider = staticCompositionLocalOf<IconProvider?> { null }

private class CarApp(val name: String, val icon: String, val app: HmiApp)

private val CAR_APPS = listOf(
    CarApp("Phone", HmiIcons.PHONE, HmiApp.Phone),
    CarApp("Android Auto", HmiIcons.AUTO, HmiApp.Auto),
    CarApp("Vehicle", HmiIcons.VEHICLE, HmiApp.Vehicle),
    CarApp("Maps", HmiIcons.MAPS, HmiApp.Maps),
    CarApp("Rear cam", HmiIcons.CAMERA, HmiApp.Camera),
    CarApp("FM Radio", HmiIcons.RADIO, HmiApp.Radio),
    CarApp("Settings", HmiIcons.SETTINGS, HmiApp.Settings),
)

@Composable
fun AppsScreen(apps: List<LauncherApp>, actions: HmiActions) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().border(1.dp, Hmi.Line).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Caption("CAR", Modifier.padding(bottom = 6.dp))
            CAR_APPS.forEach { carApp ->
                Pressable(
                    onClick = { if (carApp.app == HmiApp.Radio) actions.openRadio() else actions.open(carApp.app) },
                    modifier = Modifier.fillMaxWidth().height(74.dp),
                    pressedBackground = Hmi.CyanTint,
                    border = Hmi.Line,
                    pressedBorder = Hmi.Cyan,
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PathIcon(carApp.icon, 26.dp, Hmi.Cyan, strokeWidth = 1.5f)
                        HText(carApp.name, size = 17.sp, spacing = 1.sp)
                    }
                }
            }
        }
        Column(
            Modifier.weight(3f).fillMaxHeight().border(1.dp, Hmi.Line).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption("INSTALLED · ANDROID")
                Caption("${apps.size} APPS")
            }
            InstalledApps(apps, actions, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Six columns with four rows per screen, as in the design; scrolls when there are more. */
@Composable
private fun InstalledApps(apps: List<LauncherApp>, actions: HmiActions, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        // Floored to whole pixels so rounding never pushes the fourth row's border out of view.
        val rowHeight = with(LocalDensity.current) { ((maxHeight - 30.dp).toPx() / 4).toInt().toDp() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(apps, key = { it.key.serialize() }) { app ->
                AppTile(app, actions, Modifier.height(rowHeight))
            }
        }
    }
}

@Composable
private fun AppTile(app: LauncherApp, actions: HmiActions, modifier: Modifier) {
    val source = remember { LaunchSource() }
    Pressable(
        onClick = { actions.launch(app, source.bounds()) },
        onLongClick = { actions.showAppInfo(app) },
        modifier = modifier.fillMaxWidth().onPlaced { source.coordinates = it },
        pressedBackground = Color.White.copy(alpha = 0.08f),
        border = Hmi.Line,
        pressedBorder = Color.White.copy(alpha = 0.3f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(app, 52.dp)
            HText(app.label, Modifier.padding(horizontal = 8.dp), size = 14.sp, spacing = 0.5.sp, maxLines = 1)
        }
    }
}

/** Where a tile sits on screen, so the launched app can scale up out of it. */
private class LaunchSource {
    var coordinates: LayoutCoordinates? = null

    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

/** The app's real icon; a monogram, as in the design's tiles, until it loads. */
@Composable
private fun AppIcon(app: LauncherApp, size: Dp) {
    val provider = LocalIconProvider.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(provider?.cachedIcon(app, sizePx), app.key, sizePx, provider) {
        value = provider?.cachedIcon(app, sizePx) ?: provider?.loadIcon(app, sizePx)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            HText(app.label.take(1).uppercase(), size = 20.sp, color = Hmi.Muted, family = Hmi.Display)
        }
    }
}
