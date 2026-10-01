package com.vivekkaushik.revv.ui.hmi

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.phone.Contact
import com.vivekkaushik.revv.phone.PhoneBook
import kotlinx.coroutines.launch

/** One line of the contact list: a letter heading, or a contact under it. */
private sealed interface ContactLine {
    data class Heading(val letter: Char) : ContactLine
    data class Person(val contact: Contact) : ContactLine
}

/** The phone's contacts A to Z with a letter rail on the right to jump about; tapping a contact calls them. */
@Composable
fun ContactsList(contacts: List<Contact>, onCall: (String) -> Unit, modifier: Modifier = Modifier) {
    val lines = remember(contacts) {
        buildList {
            var current: Char? = null
            contacts.forEach { contact ->
                val letter = PhoneBook.initial(contact.name)
                if (letter != current) {
                    current = letter
                    add(ContactLine.Heading(letter))
                }
                add(ContactLine.Person(contact))
            }
        }
    }
    val letters = remember(lines) { lines.filterIsInstance<ContactLine.Heading>().map { it.letter } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var touched by remember { mutableStateOf<Char?>(null) }

    fun jumpTo(letter: Char) {
        touched = letter
        val index = lines.indexOfFirst { it is ContactLine.Heading && it.letter == letter }
        if (index >= 0) scope.launch { state.scrollToItem(index) }
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state) {
            itemsIndexed(lines) { _, line ->
                when (line) {
                    is ContactLine.Heading -> HText(
                        line.letter.toString(),
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        size = 16.sp,
                        color = Hmi.Cyan,
                        spacing = 2.sp,
                    )
                    is ContactLine.Person -> Pressable(
                        onClick = { onCall(line.contact.number) },
                        modifier = Modifier.fillMaxWidth().edgeLine(Hmi.LineSoft),
                        pressedBackground = Hmi.CyanTint,
                        border = null,
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            HText(line.contact.name, size = 19.sp, weight = FontWeight.Medium, maxLines = 1)
                            HText(line.contact.number, Modifier.padding(top = 2.dp), size = 13.sp, color = Hmi.Muted, maxLines = 1)
                        }
                    }
                }
            }
        }
        LetterRail(letters, touched, ::jumpTo, onRelease = { touched = null }, Modifier.width(72.dp).fillMaxHeight())
    }
}

/** A column of the letters that have contacts; tap one or slide a finger along them to jump. */
@Composable
private fun LetterRail(letters: List<Char>, active: Char?, onLetter: (Char) -> Unit, onRelease: () -> Unit, modifier: Modifier) {
    if (letters.isEmpty()) return
    var height by remember { mutableStateOf(1f) }
    fun at(y: Float): Char = letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
    Column(
        modifier
            .pointerInput(letters) {
                detectTapGestures(onPress = { offset ->
                    height = size.height.toFloat()
                    onLetter(at(offset.y))
                    tryAwaitRelease()
                    onRelease()
                })
            }
            .pointerInput(letters) {
                detectDragGestures(
                    onDragStart = { height = size.height.toFloat() },
                    onDragEnd = onRelease,
                    onDragCancel = onRelease,
                ) { change, _ ->
                    change.consume()
                    onLetter(at(change.position.y))
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { letter ->
            HText(letter.toString(), size = 16.sp, color = if (letter == active) Hmi.Cyan else Hmi.Muted, weight = FontWeight.Medium)
        }
    }
}
