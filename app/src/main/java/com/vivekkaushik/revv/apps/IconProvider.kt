package com.vivekkaushik.revv.apps

import androidx.compose.ui.graphics.ImageBitmap

interface IconProvider {
    /** The icon if it is already in memory, so lists can draw it on the first frame. */
    fun cachedIcon(app: LauncherApp, sizePx: Int): ImageBitmap?

    suspend fun loadIcon(app: LauncherApp, sizePx: Int): ImageBitmap?
}
