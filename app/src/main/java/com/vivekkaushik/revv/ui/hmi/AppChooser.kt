package com.vivekkaushik.revv.ui.hmi

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.apps.AppKey
import com.vivekkaushik.revv.apps.LauncherApp

/**
 * A full-pane list of installed apps to pick one from. The first row, [defaultTitle], stands for
 * "no app chosen" and calls [onChoose] with null; picking anything calls [onDone] afterwards.
 */
@Composable
fun AppChooser(
    title: String,
    hint: String,
    apps: List<LauncherApp>,
    chosen: AppKey?,
    defaultTitle: String,
    defaultDetail: String,
    onChoose: (LauncherApp?) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HText(title, size = 26.sp, family = Hmi.Display)
        HText(hint, Modifier.padding(top = 6.dp, bottom = 16.dp), size = 15.sp, color = Hmi.Muted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "default") {
                ChooserRow(chosen == null, if (chosen == null) "IN USE" else null, {
                    onChoose(null)
                    onDone()
                }) {
                    Column(Modifier.weight(1f)) {
                        HText(defaultTitle, size = 20.sp, weight = FontWeight.Medium, maxLines = 1)
                        HText(defaultDetail, size = 14.sp, color = Hmi.Muted, spacing = 1.sp, maxLines = 1)
                    }
                }
            }
            items(apps, key = { it.key.serialize() }) { app ->
                val selected = app.key == chosen
                ChooserRow(selected, if (selected) "SELECTED" else null, {
                    onChoose(app)
                    onDone()
                }) {
                    AppIcon(app, 40.dp)
                    HText(app.label, Modifier.weight(1f), size = 20.sp, weight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Row(Modifier.padding(top = 16.dp)) {
            GhostButton("CANCEL", onDone, Modifier.height(56.dp))
        }
    }
}

@Composable
private fun ChooserRow(
    selected: Boolean,
    tag: String?,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(76.dp),
        background = if (selected) Hmi.Cyan.copy(alpha = 0.10f) else Color.Transparent,
        pressedBackground = Hmi.CyanTint,
        border = if (selected) Hmi.Cyan else Hmi.Line,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            content()
            if (tag != null) Caption(tag, color = Hmi.Cyan)
        }
    }
}
