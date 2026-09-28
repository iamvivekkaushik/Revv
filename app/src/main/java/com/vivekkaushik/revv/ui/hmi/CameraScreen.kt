package com.vivekkaushik.revv.ui.hmi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

@Composable
fun CameraScreen(live: LiveTelemetry) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        CameraFeed(live, Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(
                Modifier.fillMaxWidth().border(1.dp, Hmi.Line).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Caption("REAR SENSORS")
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(220.dp, 12.dp).background(Hmi.Red.copy(alpha = 0.18f)))
                    Box(Modifier.size(250.dp, 12.dp).background(Hmi.Amber.copy(alpha = 0.2f)))
                    Box(Modifier.size(280.dp, 12.dp).background(Hmi.Cyan))
                    Box(Modifier.size(300.dp, 12.dp).background(Hmi.Cyan))
                    Box(Modifier.padding(top = 12.dp).size(200.dp, 56.dp).border(1.dp, Color.White.copy(alpha = 0.3f)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HText("NEAREST", Modifier.alignByBaseline(), size = 16.sp, color = Hmi.Muted)
                    Reading("1.4", " M", 32.sp, Modifier.alignByBaseline(), color = Hmi.Cyan)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption("VIEW", Modifier.padding(bottom = 6.dp))
                AccentButton("STANDARD", {}, Modifier.fillMaxWidth().height(60.dp))
                GhostButton("WIDE", {}, Modifier.fillMaxWidth().height(60.dp))
                GhostButton("GUIDELINES OFF", {}, Modifier.fillMaxWidth().height(60.dp))
            }
        }
    }
}

/** A placeholder for the reversing camera: parking guides over a dark, scan-lined backdrop. */
@Composable
private fun CameraFeed(live: LiveTelemetry, modifier: Modifier) {
    Box(
        modifier
            .border(1.dp, Hmi.Line)
            .clipToBounds()
            .drawBehind {
                val center = Offset(size.width * 0.5f, size.height * 0.3f)
                val reach = hypot(size.width * 0.5f, size.height * 0.7f)
                drawRect(Brush.radialGradient(0f to Color(0xFF141A22), 0.75f to Hmi.Bg, center = center, radius = reach))
                val band = 2.dp.toPx()
                val pitch = 5.dp.toPx()
                var y = size.height - band
                while (y > -band) {
                    drawRect(Color.White.copy(alpha = 0.03f), Offset(0f, y), Size(size.width, band))
                    y -= pitch
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawGuides() }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawSteeringGuides(live.steer) }
        Row(
            Modifier.padding(start = 28.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(10.dp).background(Hmi.Red))
            Caption("REAR CAMERA · LIVE")
        }
        Caption("FEED PLACEHOLDER", Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 24.dp))
        Caption("CHECK SURROUNDINGS FOR SAFETY", Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

// The guides are laid out on a 1400×730 grid stretched to the feed, with unscaled strokes.
private fun DrawScope.at(x: Float, y: Float) = Offset(x / 1400f * size.width, y / 730f * size.height)

private fun DrawScope.guide(color: Color, width: Float, vararg points: Float, dashed: Boolean = false) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 12.dp.toPx())) else null
    for (i in points.indices step 4) {
        drawLine(color, at(points[i], points[i + 1]), at(points[i + 2], points[i + 3]), width.dp.toPx(), pathEffect = effect)
    }
}

private fun DrawScope.drawGuides() {
    guide(Hmi.Red, 4f, 520f, 540f, 360f, 730f, 880f, 540f, 1040f, 730f)
    guide(Hmi.Amber, 4f, 540f, 380f, 520f, 540f, 860f, 380f, 880f, 540f)
    guide(Hmi.Cyan, 4f, 560f, 250f, 540f, 380f, 840f, 250f, 860f, 380f)
    guide(Color.White.copy(alpha = 0.7f), 2f, 560f, 250f, 840f, 250f, 540f, 380f, 860f, 380f, 520f, 540f, 880f, 540f, dashed = true)
}

/** The curved trajectory lines that bend with the steering wheel. */
private fun DrawScope.drawSteeringGuides(steer: Float) {
    val path = Path().apply {
        val leftStart = at(470f, 730f)
        val leftControl = at(490f + steer, 480f)
        val leftEnd = at(610f + steer * 1.6f, 300f)
        moveTo(leftStart.x, leftStart.y)
        quadraticTo(leftControl.x, leftControl.y, leftEnd.x, leftEnd.y)
        val rightStart = at(930f, 730f)
        val rightControl = at(910f + steer, 480f)
        val rightEnd = at(790f + steer * 1.6f, 300f)
        moveTo(rightStart.x, rightStart.y)
        quadraticTo(rightControl.x, rightControl.y, rightEnd.x, rightEnd.y)
    }
    drawPath(
        path,
        Hmi.Text,
        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 12.dp.toPx()))),
    )
}
