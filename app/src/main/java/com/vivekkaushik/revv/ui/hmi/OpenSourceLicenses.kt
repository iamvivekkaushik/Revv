package com.vivekkaushik.revv.ui.hmi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One piece of open source (or open data) Revv uses, and the file in assets/licenses holding its terms. */
private class Notice(val name: String, val used: String, val license: String, val file: String)

private val NOTICES = listOf(
    Notice("AndroidX and Jetpack Compose", "Android app libraries, screens and layout", "Apache License 2.0", "Apache-2.0.txt"),
    Notice("Kotlin and kotlinx.coroutines", "The language and its standard library", "Apache License 2.0", "Apache-2.0.txt"),
    Notice("MapLibre Native for Android", "Draws the map", "BSD 2-Clause", "BSD-2-Clause-MapLibre.txt"),
    Notice("OkHttp and Okio", "Network requests for map tiles, routes and search", "Apache License 2.0", "Apache-2.0.txt"),
    Notice("Gson, Timber, Guava ListenableFuture, JSpecify, JetBrains Annotations", "Support libraries pulled in by the above", "Apache License 2.0", "Apache-2.0.txt"),
    Notice("JetBrains Mono", "Text font", "SIL Open Font License 1.1", "OFL-JetBrainsMono.txt"),
    Notice("Michroma", "Display font", "SIL Open Font License 1.1", "OFL-Michroma.txt"),
    Notice("Map data and services", "OpenStreetMap, OpenFreeMap, OpenMapTiles, Valhalla and Photon", "ODbL, CC-BY 4.0, MIT, Apache 2.0", "Map-data.txt"),
)

/** Settings › System › Open source licenses: what Revv is built from, and each one's full terms on tap. */
@Composable
fun OpenSourceLicenses(onDone: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = open != null) { open = null }
    val notice = open?.let(NOTICES::getOrNull)
    if (notice != null) {
        LicenseText(notice, onBack = { open = null })
        return
    }
    Column(Modifier.fillMaxSize()) {
        HText("OPEN SOURCE LICENSES", size = 26.sp, family = Hmi.Display)
        HText(
            "Revv is built with the free software and data below. Tap one to read its license.",
            Modifier.padding(top = 6.dp, bottom = 16.dp),
            size = 15.sp,
            color = Hmi.Muted,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(NOTICES.size) { index ->
                val item = NOTICES[index]
                Pressable(
                    onClick = { open = index },
                    modifier = Modifier.fillMaxWidth(),
                    pressedBackground = Hmi.CyanTint,
                    border = Hmi.Line,
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HText(item.name, size = 19.sp, weight = FontWeight.Medium)
                            HText(item.used, size = 14.sp, color = Hmi.Muted)
                        }
                        Caption(item.license.uppercase(), color = Hmi.Cyan)
                    }
                }
            }
        }
        Row(Modifier.padding(top = 16.dp)) {
            GhostButton("BACK", onDone, Modifier.height(56.dp))
        }
    }
}

@Composable
private fun LicenseText(notice: Notice, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember(notice.file) {
        runCatching { context.assets.open("licenses/${notice.file}").bufferedReader().use { it.readText() } }
            .getOrDefault("The license text could not be read.")
    }
    // Paragraphs rather than one block, so a long license scrolls smoothly.
    val paragraphs = remember(text) { text.trim().split(Regex("\n\\s*\n")) }
    Column(Modifier.fillMaxSize()) {
        HText(notice.name, size = 24.sp, family = Hmi.Display)
        Caption(notice.license.uppercase(), Modifier.padding(top = 8.dp, bottom = 16.dp), color = Hmi.Cyan)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(paragraphs) { paragraph -> HText(paragraph, size = 14.sp, color = Hmi.Muted, lineHeight = 21.sp) }
        }
        Row(Modifier.padding(top = 16.dp)) {
            GhostButton("BACK", onBack, Modifier.height(56.dp))
        }
    }
}
