package com.vivekkaushik.revv.ui.hmi

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.phone.Call
import com.vivekkaushik.revv.phone.CallType
import com.vivekkaushik.revv.phone.Favourite
import com.vivekkaushik.revv.phone.PhoneState
import com.vivekkaushik.revv.phone.PhoneSync
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val DIAL_KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

@Composable
fun PhoneScreen(phone: PhoneState, now: LocalDateTime, timeFormat: DateTimeFormatter, actions: HmiActions) {
    var number by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp)) {
            Caption("RECENTS", Modifier.padding(bottom = 16.dp))
            if (phone.recents.isNotEmpty()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(phone.recents) { call ->
                        CallRow(call, PhoneFormat.detail(call, now, timeFormat)) {
                            if (call.number.isNotBlank()) actions.call(call.number)
                        }
                    }
                }
            } else {
                RecentsNote(phone, actions)
            }
        }
        Column(
            Modifier.width(560.dp).fillMaxHeight().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(72.dp).edgeLine(), verticalAlignment = Alignment.CenterVertically) {
                HText(
                    number.ifEmpty { "ENTER NUMBER" },
                    Modifier.weight(1f),
                    size = 34.sp,
                    color = if (number.isEmpty()) Hmi.Faint else Hmi.Text,
                    family = Hmi.Display,
                    spacing = 2.sp,
                    maxLines = 1,
                )
                Pressable(
                    onClick = { number = number.dropLast(1) },
                    modifier = Modifier.size(52.dp),
                    pressedBackground = Hmi.PressedWhite,
                    border = null,
                    repeatEveryMillis = 70,
                ) {
                    PathIcon(HmiIcons.BACKSPACE, 28.dp, Hmi.Muted, strokeWidth = 1.8f)
                }
            }
            KeyPad(DIAL_KEYS, onKey = { digit -> number = (number + digit).take(MAX_DIGITS) }, Modifier.weight(1f).fillMaxWidth())
            SolidButton("CALL", onClick = { actions.call(number) }, Modifier.fillMaxWidth().height(68.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            LinkCard(phone, timeFormat, actions)
            Favourites(phone, actions, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun CallRow(call: Call, detail: String, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft),
        pressedBackground = Color.White.copy(alpha = 0.06f),
        border = null,
    ) {
        Row(
            Modifier.padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.weight(1f)) {
                HText(call.label, size = 22.sp, weight = FontWeight.Medium, maxLines = 1)
                HText(
                    detail,
                    Modifier.padding(top = 4.dp),
                    size = 14.sp,
                    color = if (call.type == CallType.Missed) Hmi.Red else Hmi.Muted,
                    spacing = 1.sp,
                    maxLines = 1,
                )
            }
            // A withheld number can't be called back.
            if (call.number.isNotBlank()) PathIcon(HmiIcons.PHONE, 24.dp, Hmi.Cyan)
        }
    }
}

/** What the recents column says until the phone's calls are in. */
@Composable
private fun RecentsNote(phone: PhoneState, actions: HmiActions) {
    val name = phone.link?.name ?: "your phone"
    when (phone.sync) {
        PhoneSync.NeedsPermission ->
            Prompt("Allow Revv to use Bluetooth to read the calls on your phone.", "ALLOW", actions::requestBluetoothPermission)
        PhoneSync.NoPhone ->
            Prompt("Pair your phone with this head unit to see its calls here.", "BLUETOOTH", actions::openBluetoothSettings)
        PhoneSync.Reading -> Note("Reading calls from $name…")
        PhoneSync.AwaitingApproval ->
            Note("$name is asking whether to share its contacts and call history with this head unit. Allow it on the phone.")
        PhoneSync.Failed -> Prompt(phone.problem ?: "Couldn't read calls from $name.", "TRY AGAIN", actions::readPhoneAgain)
        PhoneSync.Synced -> Note("No calls on $name yet")
    }
}

/** The phone the calls and contacts come from: a tap reads it again, or with none, pairs one. */
@Composable
private fun LinkCard(phone: PhoneState, timeFormat: DateTimeFormatter, actions: HmiActions) {
    val link = phone.link
    val content = @Composable {
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PathIcon(HmiIcons.BLUETOOTH, 24.dp, if (link?.connected == true) Hmi.Cyan else Hmi.Muted, strokeWidth = 1.8f)
            Column {
                HText(link?.name ?: "No phone connected", size = 22.sp, weight = FontWeight.Medium, maxLines = 1)
                HText(
                    PhoneFormat.status(phone, timeFormat),
                    Modifier.padding(top = 4.dp),
                    size = 14.sp,
                    color = Hmi.Muted,
                    spacing = 1.sp,
                    maxLines = 1,
                )
            }
        }
    }
    val onClick = if (link == null) actions::openBluetoothSettings else actions::readPhoneAgain
    Pressable(onClick, Modifier.fillMaxWidth(), border = Hmi.Line, contentAlignment = Alignment.CenterStart) { content() }
}

@Composable
private fun Favourites(phone: PhoneState, actions: HmiActions, modifier: Modifier) {
    Column(
        modifier.border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Caption("FAVOURITES")
        when {
            phone.favourites.isNotEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Always three rows of two, so a short list doesn't stretch its tiles.
                val shown = phone.favourites.take(FAVOURITE_SLOTS)
                (shown + List(FAVOURITE_SLOTS - shown.size) { null }).chunked(2).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { favourite ->
                            if (favourite == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                FavouriteTile(favourite, { actions.call(favourite.number) }, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
            phone.sync == PhoneSync.Synced -> Note("Mark favourites on the phone to keep them here")
            else -> Note("The phone's favourites and most-called contacts show here")
        }
    }
}

@Composable
private fun FavouriteTile(favourite: Favourite, onClick: () -> Unit, modifier: Modifier) {
    Pressable(onClick = onClick, modifier = modifier, pressedBackground = Hmi.CyanTint, border = Hmi.Line) {
        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            HText(favourite.name, size = 19.sp, maxLines = 1)
            // Filled in from the call history rather than a favourite on the phone.
            if (!favourite.starred) Caption("FREQUENT", Modifier.padding(top = 4.dp), size = 12.sp)
        }
    }
}

@Composable
private fun Prompt(text: String, button: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Note(text)
        AccentButton(button, onClick, Modifier.height(52.dp))
    }
}

@Composable
private fun Note(text: String) {
    HText(text, size = 18.sp, color = Hmi.Muted, lineHeight = 26.sp)
}

private const val MAX_DIGITS = 14
private const val FAVOURITE_SLOTS = 6
