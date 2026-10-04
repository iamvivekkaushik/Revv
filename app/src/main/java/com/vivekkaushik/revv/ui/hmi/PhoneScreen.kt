package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.phone.PhoneBook
import com.vivekkaushik.revv.phone.Contact
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
    val matches = remember(number, phone.contacts, phone.recents) {
        PhoneBook.suggestions(number, phone.contacts, phone.recents)
    }
    val pickMatch = { person: Contact -> number = person.number.filter { it.isDigit() || it in "+*#" }.take(MAX_DIGITS) }
    // Compact (display sizes above 130%): recents, favourites and contacts share one column as tabs,
    // and the linked-phone card goes; the recents note still offers pairing and reading again.
    if (LocalCompact.current) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PhoneLists(phone, matches, pickMatch, now, timeFormat, actions, Modifier.weight(1f).fillMaxHeight())
            Dialer(number, { number = it }, actions, Modifier.width(520.dp).fillMaxHeight())
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp)) {
            if (matches.isNotEmpty()) {
                Matches(matches, pickMatch)
            } else {
                Caption("RECENTS", Modifier.padding(bottom = 16.dp))
                Recents(phone, now, timeFormat, actions)
            }
        }
        Dialer(number, { number = it }, actions, Modifier.width(560.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            LinkCard(phone, timeFormat, actions)
            Favourites(phone, actions, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** T9: the keys typed so far pick out contacts by name; tapping one fills in their number. */
@Composable
private fun ColumnScope.Matches(matches: List<Contact>, onPick: (Contact) -> Unit) {
    Caption("MATCHES · ${matches.size}", Modifier.padding(bottom = 16.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(matches) { person -> MatchRow(person) { onPick(person) } }
    }
}

@Composable
private fun ColumnScope.Recents(phone: PhoneState, now: LocalDateTime, timeFormat: DateTimeFormatter, actions: HmiActions) {
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

/** The number being typed, the keypad and CALL. */
@Composable
private fun Dialer(number: String, onNumber: (String) -> Unit, actions: HmiActions, modifier: Modifier) {
    val compact = LocalCompact.current
    Column(
        modifier.border(1.dp, Hmi.Line).padding(horizontal = if (compact) 28.dp else 32.dp, vertical = if (compact) 24.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp).edgeLine(), verticalAlignment = Alignment.CenterVertically) {
            HText(
                number.ifEmpty { "ENTER NUMBER" },
                Modifier.weight(1f),
                size = if (compact) 30.sp else 34.sp,
                color = if (number.isEmpty()) Hmi.Faint else Hmi.Text,
                family = Hmi.Display,
                spacing = 2.sp,
                maxLines = 1,
            )
            Pressable(
                onClick = { onNumber(number.dropLast(1)) },
                modifier = Modifier.size(52.dp),
                pressedBackground = Hmi.PressedWhite,
                border = null,
                repeatEveryMillis = 70,
                sound = UiSound.DialDelete,
            ) {
                PathIcon(HmiIcons.BACKSPACE, 28.dp, Hmi.Muted, strokeWidth = 1.8f)
            }
        }
        KeyPad(DIAL_KEYS, onKey = { digit -> onNumber((number + digit).take(MAX_DIGITS)) }, Modifier.weight(1f).fillMaxWidth())
        SolidButton("CALL", onClick = { actions.call(number) }, Modifier.fillMaxWidth().height(68.dp))
    }
}

private enum class PhoneList(val label: String) { Recents("RECENTS"), Favourites("FAVOURITES"), Contacts("CONTACTS") }

/** The compact Phone screen's one list column: T9 matches while typing, else recents, favourites or contacts. */
@Composable
private fun PhoneLists(
    phone: PhoneState,
    matches: List<Contact>,
    onPickMatch: (Contact) -> Unit,
    now: LocalDateTime,
    timeFormat: DateTimeFormatter,
    actions: HmiActions,
    modifier: Modifier,
) {
    var shown by rememberSaveable { mutableStateOf(PhoneList.Recents) }
    // The contact list's letter rail runs close to the card's right edge, where a thumb lands easily.
    val rail = matches.isEmpty() && shown == PhoneList.Contacts && phone.contacts.isNotEmpty()
    Column(
        modifier.border(1.dp, Hmi.Line).padding(start = 28.dp, end = if (rail) 6.dp else 28.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (matches.isNotEmpty()) {
            Matches(matches, onPickMatch)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(end = if (rail) 22.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhoneList.entries.forEach { list -> ListTab(list.label, selected = list == shown) { shown = list } }
            Caption(
                PhoneFormat.badge(phone.link),
                Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1,
            )
        }
        when (shown) {
            PhoneList.Recents -> Recents(phone, now, timeFormat, actions)
            PhoneList.Favourites -> FavouriteList(phone, actions)
            PhoneList.Contacts -> ContactList(phone, actions)
        }
    }
}

@Composable
private fun MatchRow(person: Contact, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft),
        pressedBackground = Hmi.CyanTint,
        border = null,
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            HText(person.name, size = 22.sp, weight = FontWeight.Medium, maxLines = 1)
            HText(person.number, Modifier.padding(top = 4.dp), size = 14.sp, color = Hmi.Muted, spacing = 1.sp, maxLines = 1)
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
    var showContacts by rememberSaveable { mutableStateOf(false) }
    Column(
        // The contact list's letter rail runs close to the card's right edge, where a thumb lands easily.
        modifier.border(1.dp, Hmi.Line).padding(start = 32.dp, end = if (showContacts) 6.dp else 32.dp, top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(end = if (showContacts) 26.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListTab("FAVOURITES", selected = !showContacts) { showContacts = false }
            ListTab("CONTACTS", selected = showContacts) { showContacts = true }
        }
        if (showContacts) ContactList(phone, actions) else FavouriteList(phone, actions)
    }
}

@Composable
private fun ColumnScope.ContactList(phone: PhoneState, actions: HmiActions) {
    when {
        phone.contacts.isNotEmpty() -> ContactsList(phone.contacts, actions::call, Modifier.weight(1f).fillMaxWidth())
        phone.sync == PhoneSync.Synced -> Note("The phone did not share any contacts. Allow contacts for this head unit in its Bluetooth settings, then read again.")
        else -> Note("The phone's contacts show here once it has been read")
    }
}

@Composable
private fun ColumnScope.FavouriteList(phone: PhoneState, actions: HmiActions) {
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

@Composable
private fun ListTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        background = if (selected) Hmi.CyanWash else Color.Transparent,
        border = if (selected) Hmi.Cyan else Hmi.Line,
    ) {
        HText(label, Modifier.padding(horizontal = 18.dp), size = 14.sp, color = if (selected) Hmi.Cyan else Hmi.Muted, spacing = 2.sp, maxLines = 1)
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
