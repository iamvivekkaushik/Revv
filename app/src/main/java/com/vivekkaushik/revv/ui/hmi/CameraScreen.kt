package com.vivekkaushik.revv.ui.hmi

import com.vivekkaushik.revv.settings.SettingsStore
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.BoxScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.Manifest
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
fun CameraScreen(live: LiveTelemetry, chosenCameraId: String?, rotation: Int, actions: HmiActions) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val cameras = remember { RearCameras.list(context) }
    val camera = cameras.firstOrNull { it.id == chosenCameraId } ?: cameras.firstOrNull()
    var guides by rememberSaveable { mutableStateOf(true) }
    // Reset when the camera changes, so a camera that failed can be tried again by switching.
    var failedId by remember { mutableStateOf<String?>(null) }
    val feed = when {
        camera == null -> Feed.NoCamera
        !granted -> Feed.NeedsPermission
        failedId == camera.id -> Feed.Failed
        else -> Feed.Live(camera)
    }
    // Compact (display sizes above 130%) gives the picture more room and drops the rear sensors
    // card, which only illustrates: no sensor reading reaches Revv.
    val compact = LocalCompact.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 24.dp else 28.dp)) {
        CameraFeed(live, feed, guides, rotation, { permission.launch(Manifest.permission.CAMERA) }, { failedId = it }, Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(if (compact) 320.dp else 400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (!compact) Column(
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
                Modifier.weight(1f).fillMaxWidth().border(1.dp, Hmi.Line).verticalScroll(rememberScrollState()).padding(if (compact) 24.dp else 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption("VIEW", Modifier.padding(bottom = 6.dp))
                if (guides) {
                    AccentButton("GUIDELINES ON", { guides = false }, Modifier.fillMaxWidth().height(60.dp))
                } else {
                    GhostButton("GUIDELINES OFF", { guides = true }, Modifier.fillMaxWidth().height(60.dp))
                }
                Caption("PICTURE", Modifier.padding(top = 10.dp, bottom = 6.dp))
                GhostButton(
                    "ROTATE · $rotation°",
                    { actions.setChoice(SettingsStore.CAMERA_ROTATION, (rotation / 90 + 1) % 4) },
                    Modifier.fillMaxWidth().height(60.dp),
                )
                if (cameras.size > 1 && camera != null) {
                    Caption("CAMERA", Modifier.padding(top = 10.dp, bottom = 6.dp))
                    GhostButton(
                        camera.label,
                        {
                            failedId = null
                            actions.setRearCameraId(cameras[(cameras.indexOf(camera) + 1) % cameras.size].id)
                        },
                        Modifier.fillMaxWidth().height(60.dp),
                    )
                }
            }
        }
    }
}

/** What the feed area shows. */
private sealed interface Feed {
    data class Live(val camera: CameraOption) : Feed
    data object NeedsPermission : Feed
    data object NoCamera : Feed
    data object Failed : Feed
}

/** The reversing camera's picture with the parking guides over it; a dark backdrop saying why when there is no picture. */
@Composable
private fun CameraFeed(
    live: LiveTelemetry,
    feed: Feed,
    guides: Boolean,
    rotation: Int,
    onAllow: () -> Unit,
    onFailed: (String) -> Unit,
    modifier: Modifier,
) {
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
        if (feed is Feed.Live) {
            CameraPreview(feed.camera.id, rotation, onFailed = { onFailed(feed.camera.id) }, modifier = Modifier.fillMaxSize())
        }
        if (guides) {
            Canvas(Modifier.fillMaxSize()) { drawGuides() }
            Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawSteeringGuides(live.steer) }
        }
        when (feed) {
            Feed.NeedsPermission -> FeedMessage("Allow Revv to use the camera to show the rear view.", "ALLOW", onAllow)
            Feed.NoCamera -> FeedMessage("No camera found on this head unit.", null, {})
            Feed.Failed -> FeedMessage("The camera could not be opened. Something else may be using it.", null, {})
            is Feed.Live -> Unit
        }
        Row(
            Modifier.padding(start = 28.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(10.dp).background(Hmi.Red))
            Caption(if (feed is Feed.Live) "REAR CAMERA · LIVE" else "REAR CAMERA")
        }
        if (feed is Feed.Live) {
            Caption(feed.camera.label, Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 24.dp))
        }
        Caption("CHECK SURROUNDINGS FOR SAFETY", Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

@Composable
private fun BoxScope.FeedMessage(text: String, button: String?, onClick: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HText(text, size = 22.sp, color = Hmi.Muted, align = TextAlign.Center, lineHeight = 30.sp)
        if (button != null) AccentButton(button, onClick, Modifier.height(56.dp))
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
