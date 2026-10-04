package com.vivekkaushik.revv.ui.hmi

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.revv.R
import kotlin.math.min

/**
 * Tokens from the "Swift HMI v4" design. Every size in this package is in design pixels: inside
 * [DesignCanvas], 1.dp (and 1.sp) is one pixel of the 1920×1080 artboard.
 */
object Hmi {
    val Black = Color(0xFF000000)
    val Bg = Color(0xFF05070A)
    val MapBg = Color(0xFF03050A)
    val Text = Color(0xFFE6EDF3)
    val TextPressed = Color(0xFFB8C4CE)
    val Muted = Color(0xFF7D8A96)
    val Faint = Color(0xFF3E4852)
    val Cyan = Color(0xFF4DD8FF)
    val CyanLight = Color(0xFF9AE9FF)
    val Red = Color(0xFFFF3B3B)
    val Amber = Color(0xFFFFB000)

    val Line = Color.White.copy(alpha = 0.10f)
    val LineStrong = Color.White.copy(alpha = 0.15f)
    val LineSoft = Color.White.copy(alpha = 0.08f)
    val CyanWash = Cyan.copy(alpha = 0.08f)
    val CyanTint = Cyan.copy(alpha = 0.15f)
    val CyanPressed = Cyan.copy(alpha = 0.25f)
    val PressedWhite = Color.White.copy(alpha = 0.12f)

    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
    val Display = FontFamily(Font(R.font.michroma_regular))

    /** CSS `ease`. */
    val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** The design's `cubic-bezier(.2,.9,.2,1)` for panels settling into place. */
    val Settle = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)

    /** The design's `cubic-bezier(.2,1,.3,1)` for app screens sliding in. */
    val Glide = CubicBezierEasing(0.2f, 1f, 0.3f, 1f)

    const val DESIGN_WIDTH = 1920f
    const val DESIGN_HEIGHT = 1080f

    /** Display sizes above this use the compact layouts (see [LocalCompact]). */
    const val COMPACT_ABOVE_PERCENT = 130
}

private const val HOLD_DELAY_MILLIS = 450L

/** Whether taps give haptic feedback (Settings → Sound → Touch feedback). */
val LocalTouchFeedback = compositionLocalOf { true }

/**
 * Whether screens use their compact layouts, for display sizes above 130%. There the artboard has
 * as little as 1200×675 design pixels to fill, so margins tighten and the least needed panels go;
 * type and touch targets keep their design sizes, which is what a bigger display size is for.
 */
val LocalCompact = staticCompositionLocalOf { false }

/**
 * Scales the 1920×1080 artboard uniformly to fit, then stretches whichever axis has room left
 * over instead of letterboxing, so wide 1920×720 head units still fill the screen.
 */
@Composable
fun DesignCanvas(modifier: Modifier = Modifier, sizePercent: Int = 100, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val scale = min(constraints.maxWidth / Hmi.DESIGN_WIDTH, constraints.maxHeight / Hmi.DESIGN_HEIGHT) * sizePercent / 100f
        CompositionLocalProvider(
            LocalDensity provides Density(scale, fontScale = 1f),
            LocalCompact provides (sizePercent > Hmi.COMPACT_ABOVE_PERCENT),
        ) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
fun HText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    color: Color = Hmi.Text,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = Hmi.Mono,
    spacing: TextUnit = 0.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow? = null,
) {
    HText(AnnotatedString(text), modifier, size, color, weight, family, spacing, lineHeight, align, maxLines, overflow)
}

@Composable
fun HText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    size: TextUnit = 15.sp,
    color: Color = Hmi.Text,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = Hmi.Mono,
    spacing: TextUnit = 0.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = family,
            letterSpacing = spacing,
            lineHeight = lineHeight,
            textAlign = align,
        ),
        overflow = overflow ?: if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        softWrap = maxLines != 1,
        maxLines = maxLines,
    )
}

/** The small tracked-out caption that heads every panel, e.g. "FUEL · RANGE". */
@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Hmi.Muted,
    size: TextUnit = 15.sp,
    maxLines: Int = Int.MAX_VALUE,
) {
    HText(text, modifier, size = size, color = color, spacing = 2.sp, maxLines = maxLines)
}

/** Cyan outline button: the design's primary in-panel action ("CALL BACK", active toggles). */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(onClick, modifier, background = Hmi.CyanWash, pressedBackground = Hmi.CyanPressed, border = Hmi.Cyan) {
        HText(text, Modifier.padding(horizontal = 22.dp), size = 15.sp, color = Hmi.Cyan, spacing = 2.sp, maxLines = 1)
    }
}

/** Grey outline button for the inactive options next to an [AccentButton]. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(onClick, modifier, background = Hmi.MapBg.copy(alpha = 0.9f)) {
        HText(text, Modifier.padding(horizontal = 22.dp), size = 15.sp, color = Hmi.Muted, spacing = 2.sp, maxLines = 1)
    }
}

/** Solid cyan call-to-action ("CALL", "START PROJECTION"). */
@Composable
fun SolidButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pressable(onClick, modifier, background = Hmi.Cyan, pressedBackground = Hmi.CyanLight, border = null) {
        HText(text, size = 18.sp, weight = FontWeight.Bold, color = Hmi.Bg, spacing = 3.sp, maxLines = 1)
    }
}

/** A Michroma reading with a smaller mono unit on the same baseline, e.g. "412 KM". */
@Composable
fun Reading(
    value: String,
    unit: String,
    valueSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Hmi.Text,
    valueSpacing: TextUnit = 0.sp,
    unitSize: TextUnit = 16.sp,
    unitGap: Dp = 0.dp,
) {
    Row(modifier) {
        HText(value, Modifier.alignByBaseline(), size = valueSize, color = color, family = Hmi.Display, spacing = valueSpacing)
        HText(unit, Modifier.alignByBaseline().padding(start = unitGap), size = unitSize, color = Hmi.Muted)
    }
}

/**
 * The design's flat, square button: a hairline border and a tint while pressed, no ripple.
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    background: Color = Color.Transparent,
    pressedBackground: Color = Hmi.PressedWhite,
    border: Color? = Hmi.LineStrong,
    pressedBorder: Color? = border,
    contentAlignment: Alignment = Alignment.Center,
    /** When above zero, holding the button repeats the click this often (after a short pause), like a held key. */
    repeatEveryMillis: Long = 0,
    /** What tapping it sounds like; null for silence. */
    sound: UiSound? = UiSound.Menu,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    val pressed by interactionSource.collectIsPressedAsState()
    val touchFeedback = LocalTouchFeedback.current
    val menuSound = LocalMenuSound.current
    val view = LocalView.current
    val borderColor = if (pressed) pressedBorder else border
    Box(
        modifier
            .background(if (pressed) pressedBackground else background)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor) else Modifier)
            .then(
                if (repeatEveryMillis > 0) {
                    Modifier
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            this.onClick { currentOnClick(); true }
                        }
                        .pointerInput(repeatEveryMillis) {
                            detectTapGestures(onPress = { offset ->
                                val press = PressInteraction.Press(offset)
                                interactionSource.emit(press)
                                fun fire(first: Boolean = false) {
                                    if (touchFeedback) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    if (first && sound != null) menuSound?.play(sound)
                                    currentOnClick()
                                }
                                fire(first = true)
                                val repeating = scope.launch {
                                    delay(HOLD_DELAY_MILLIS)
                                    while (true) {
                                        fire()
                                        delay(repeatEveryMillis)
                                    }
                                }
                                tryAwaitRelease()
                                repeating.cancel()
                                interactionSource.emit(PressInteraction.Release(press))
                            })
                        }
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onLongClick = onLongClick,
                        onClick = {
                            if (touchFeedback) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (sound != null) menuSound?.play(sound)
                            onClick()
                        },
                    )
                },
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** The dialer's 3 × 4 keypad: each key shows a big character over an optional caption. */
@Composable
fun KeyPad(keys: List<Pair<String, String>>, onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, caption) ->
                    Pressable(
                        onClick = { onKey(key) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        pressedBackground = Hmi.CyanTint,
                        border = Hmi.Line,
                        sound = UiSound.dial(key),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HText(key, size = 30.sp, family = Hmi.Display, lineHeight = 30.sp)
                            if (caption.isNotEmpty()) Caption(caption, size = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Draws an SVG path from the design's icon set, stroked with square caps like the original. */
@Composable
fun PathIcon(
    pathData: String,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 1.6f,
    filled: Boolean = false,
    viewport: Float = 24f,
) {
    val path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    Canvas(modifier.size(size)) {
        val factor = this.size.minDimension / viewport
        scale(factor, factor, pivot = Offset.Zero) {
            if (filled) {
                drawPath(path, color)
            } else {
                drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Square, join = StrokeJoin.Miter))
            }
        }
    }
}

/** The faint 60px blueprint grid behind every screen. */
fun Modifier.blueprintGrid(alpha: () -> Float = { 1f }): Modifier = drawBehind {
    val color = Color.White.copy(alpha = 0.025f * alpha())
    val step = 60.dp.toPx()
    val line = 1.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawRect(color, Offset(x, 0f), Size(line, size.height))
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawRect(color, Offset(0f, y), Size(size.width, line))
        y += step
    }
}

/** Hairline along one edge, standing in for a CSS `border-bottom` / `border-right`. */
fun Modifier.edgeLine(color: Color = Hmi.Line, bottom: Boolean = true): Modifier = drawBehind {
    val line = 1.dp.toPx()
    if (bottom) {
        drawRect(color, Offset(0f, size.height - line), Size(size.width, line))
    } else {
        drawRect(color, Offset(size.width - line, 0f), Size(line, size.height))
    }
}

/**
 * Makes a layer as solid to touch as its background is to the eye: a tap on an empty part of it
 * stops there instead of reaching whatever the layer covers. Its own controls work as before.
 */
fun Modifier.opaqueToTouch(): Modifier = pointerInput(Unit) {
    // Listening is enough: of overlapping siblings, Compose offers a touch only to the topmost one
    // that listens. Consuming it as well would cancel taps on the layer's own controls.
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/** Fades (and optionally rises) into place like the design's CSS transitions, delay included. */
@Composable
fun Modifier.reveal(
    visible: Boolean,
    fadeMillis: Int,
    delayMillis: Int = 0,
    riseBy: Dp = 0.dp,
    riseMillis: Int = 900,
): Modifier {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(fadeMillis, delayMillis, Hmi.Ease), label = "fade")
    val rise by animateFloatAsState(if (visible) 0f else 1f, tween(riseMillis, delayMillis, Hmi.Settle), label = "rise")
    return graphicsLayer {
        this.alpha = alpha
        translationY = rise * riseBy.toPx()
    }
}
