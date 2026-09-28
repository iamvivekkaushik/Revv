package com.vivekkaushik.revv.ui.hmi

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.vivekkaushik.revv.vehicle.DemoData

private val DIAL_KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

@Composable
fun PhoneScreen(actions: HmiActions) {
    var number by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Caption("RECENTS", Modifier.padding(bottom = 10.dp))
            DemoData.recents.forEach { call ->
                Pressable(
                    onClick = {},
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
                            HText(call.name, size = 22.sp, weight = FontWeight.Medium)
                            HText(
                                call.detail,
                                Modifier.padding(top = 4.dp),
                                size = 14.sp,
                                color = if (call.missed) Hmi.Red else Hmi.Muted,
                                spacing = 1.sp,
                            )
                        }
                        PathIcon(HmiIcons.PHONE, 24.dp, Hmi.Cyan)
                    }
                }
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
                ) {
                    PathIcon(HmiIcons.BACKSPACE, 28.dp, Hmi.Muted, strokeWidth = 1.8f)
                }
            }
            KeyPad(DIAL_KEYS, onKey = { digit -> number = (number + digit).take(MAX_DIGITS) }, Modifier.weight(1f).fillMaxWidth())
            SolidButton("CALL", onClick = { actions.dial(number) }, Modifier.fillMaxWidth().height(68.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(
                Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = 28.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                PathIcon(HmiIcons.BLUETOOTH, 24.dp, Hmi.Cyan, strokeWidth = 1.8f)
                Column {
                    HText(DemoData.PHONE, size = 22.sp, weight = FontWeight.Medium)
                    HText(
                        "CONNECTED · ${DemoData.PHONE_BATTERY} · ${DemoData.PHONE_NETWORK}",
                        Modifier.padding(top = 4.dp),
                        size = 14.sp,
                        color = Hmi.Muted,
                        spacing = 1.sp,
                    )
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Caption("FAVOURITES")
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DemoData.favourites.chunked(2).forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { name ->
                                Pressable(
                                    onClick = {},
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    pressedBackground = Hmi.CyanTint,
                                    border = Hmi.Line,
                                ) {
                                    HText(name, size = 19.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_DIGITS = 14
