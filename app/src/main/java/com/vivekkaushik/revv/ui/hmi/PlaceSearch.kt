package com.vivekkaushik.revv.ui.hmi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.nav.Geo
import com.vivekkaushik.revv.nav.LatLon
import com.vivekkaushik.revv.nav.Place
import com.vivekkaushik.revv.nav.PlaceSearch

/**
 * Finding somewhere to drive: typed on the HMI's own keyboard, answered as you type, with recent
 * places before anything's typed. Laid out like the Wi-Fi address editor, keys on the right.
 */
@Composable
fun PlaceSearchPanel(
    recents: List<Place>,
    search: PlaceSearch,
    near: LatLon?,
    actions: HmiActions,
    onPick: (Place) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    fun type(text: String) {
        query = text.take(MAX_QUERY)
        actions.searchPlaces(query)
    }
    Row(
        // Covers the map, which would otherwise pan under a drag across the panel.
        modifier.opaqueToTouch().background(Hmi.MapBg).blueprintGrid().padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HText("WHERE TO?", Modifier.weight(1f), size = 26.sp, family = Hmi.Display)
                GhostButton("CANCEL", onClose, Modifier.height(52.dp))
            }
            Row(Modifier.fillMaxWidth().height(72.dp).edgeLine(), verticalAlignment = Alignment.CenterVertically) {
                HText(
                    query.ifEmpty { "PLACE, ADDRESS OR AREA" },
                    Modifier.weight(1f),
                    size = 30.sp,
                    color = if (query.isEmpty()) Hmi.Faint else Hmi.Text,
                    spacing = 1.sp,
                    maxLines = 1,
                )
                if (query.isNotEmpty()) {
                    Pressable(onClick = { type("") }, Modifier.height(52.dp), border = null) {
                        Caption("CLEAR", Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
            val typed = query.isNotBlank()
            val waitingForAnswer = typed && (search.query != query || search.searching)
            when {
                !typed && recents.isEmpty() -> Hint("Search anywhere in India: a mall, a sector, a street, a city.")
                !typed -> Places("RECENT", recents, near, onPick)
                search.error != null && !waitingForAnswer -> Hint(search.error)
                search.results.isEmpty() && waitingForAnswer -> Hint(if (query.trim().length < 2) "Keep typing…" else "Searching…")
                search.results.isEmpty() -> Hint("No places found for “${query.trim()}”.")
                else -> Places("RESULTS", search.results, near, onPick)
            }
        }
        TextKeyboard(
            onKey = { type(query + it) },
            onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) type("$query ") },
            onBackspace = { type(query.dropLast(1)) },
            modifier = Modifier.width(920.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun Hint(text: String) {
    HText(text, Modifier.padding(vertical = 12.dp), size = 18.sp, color = Hmi.Muted)
}

@Composable
private fun Places(title: String, places: List<Place>, near: LatLon?, onPick: (Place) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption(title)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(places, key = { "${it.name}@${it.position.lat},${it.position.lon}" }) { place ->
                PlaceRow(place, near, onPick)
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, near: LatLon?, onPick: (Place) -> Unit) {
    Pressable(
        onClick = { onPick(place) },
        modifier = Modifier.fillMaxWidth().height(80.dp),
        pressedBackground = Hmi.CyanTint,
        border = Hmi.Line,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HText(place.name, size = 20.sp, weight = FontWeight.Medium, maxLines = 1)
                if (place.detail.isNotEmpty()) HText(place.detail, size = 14.sp, color = Hmi.Muted, spacing = 1.sp, maxLines = 1)
            }
            if (near != null) {
                val (distance, unit) = NavFormat.distance(Geo.distance(near, place.position))
                HText("$distance $unit", Modifier.padding(start = 16.dp), size = 16.sp, color = Hmi.Muted, spacing = 1.sp)
            }
        }
    }
}

private val KEYBOARD_ROWS = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL'", "ZXCVBNM,.-")

/** A full keyboard sized for a car: big keys, no autocorrect, nothing to flick through. */
@Composable
fun TextKeyboard(onKey: (String) -> Unit, onSpace: () -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KEYBOARD_ROWS.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    KeyboardKey(Modifier.weight(1f), onClick = { onKey(key.toString()) }) {
                        HText(key.toString(), size = 26.sp, family = Hmi.Display)
                    }
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardKey(Modifier.weight(7f), onClick = onSpace, repeatEveryMillis = KEY_REPEAT_MILLIS) { Caption("SPACE") }
            KeyboardKey(Modifier.weight(3f), onClick = onBackspace, repeatEveryMillis = KEY_REPEAT_MILLIS) { PathIcon(HmiIcons.BACKSPACE, 28.dp, Hmi.Text) }
        }
    }
}

/** How often a held Space or Backspace key repeats. */
private const val KEY_REPEAT_MILLIS = 70L

@Composable
private fun KeyboardKey(modifier: Modifier, onClick: () -> Unit, repeatEveryMillis: Long = 0, content: @Composable () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        pressedBackground = Hmi.CyanTint,
        border = Hmi.Line,
        repeatEveryMillis = repeatEveryMillis,
    ) {
        content()
    }
}

private const val MAX_QUERY = 60
