package com.spektra.latent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val needleGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/**
 * The app's one way of setting a number: a needle over a scale.
 *
 * The scale has eight long marks with five short ones to each. A line runs
 * from where the value rests ([origin]: zero, or the low end) to the needle.
 * Both are neutral while the value is at its [default] and light up amber once
 * it has been moved.
 *
 * Drag sideways anywhere on it: the needle moves by as much as the finger
 * does, so it never jumps to where it was touched -- the scale sits in a list
 * that scrolls, and a touch on the way past must not change a value. The
 * origin holds the needle a little ([sticky]); double-tap goes back to the
 * default.
 *
 * @param onChange every step while moving
 * @param onSettled once, when the hand has let go
 */
@Composable
fun Ruler(
    name: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    default: Float,
    onChange: (Float) -> Unit,
    onSettled: () -> Unit,
    modifier: Modifier = Modifier,
    origin: Float = if (min < 0f) 0f else min,
    sticky: Boolean = true,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val change by rememberUpdatedState(onChange)
    val settled by rememberUpdatedState(onSettled)
    val current by rememberUpdatedState(value)
    val span = max - min

    fun quantise(v: Float): Float {
        var q = (v / step).roundToInt() * step
        if (sticky && abs(q - origin) < step * 1.5f) q = origin
        return q.coerceIn(min, max)
    }

    val text = Ink.GlassText
    val dim = Ink.GlassDim
    val accent = Ink.Accent
    val tick = Ink.Tick
    val major = Ink.TickMajor
    val light = Ink.light

    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .semantics {
                contentDescription = name
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max)
                setProgress { target -> change(quantise(target)); settled(); true }
            }
            .pointerInput(min, max, step, default, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onDoubleTap = {
                    if (current != default) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change(default)
                        settled()
                    }
                })
            }
            .pointerInput(min, max, step, default, origin, sticky, enabled) {
                if (!enabled) return@pointerInput
                var start = 0f
                var travelled = 0f
                var moved = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = current
                        travelled = 0f
                        moved = false
                    },
                    onDragEnd = { if (moved) settled() },
                    onDragCancel = { if (moved) settled() },
                ) { input, dx ->
                    input.consume()
                    travelled += dx
                    val q = quantise(start + travelled / size.width * span)
                    if (q != current) {
                        moved = true
                        if (q == origin || q == default || q == min || q == max) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change(q)
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val px = 1.dp.toPx()
        val floor = h - 4.dp.toPx()
        // eight long marks, five short ones to each
        for (i in 0..40) {
            val x = min(w - px / 2, i / 40f * (w - px) + px / 2)
            val long = i % 5 == 0
            tick(x, floor - (if (long) 10 else 5).dp.toPx(), floor, if (long) major else tick, px)
        }
        val set = value != default
        val p = ((value - min) / span).coerceIn(0f, 1f) * w
        val o = ((origin - min) / span).coerceIn(0f, 1f) * w
        // from where it rests to where it is
        if (p != o) {
            drawRect(
                (if (set) accent else dim).copy(alpha = if (set) .8f else .6f),
                Offset(min(p, o), floor - 1.5.dp.toPx()), Size(abs(p - o), 1.5.dp.toPx()),
            )
        }
        val ink = if (set) accent else text
        if (set && !light) drawIntoCanvas {
            needleGlow.color = Ink.Amber.copy(alpha = .45f).toArgb()
            needleGlow.maskFilter = android.graphics.BlurMaskFilter(5.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            it.nativeCanvas.drawRect(p - px, 2.dp.toPx(), p + px, h - 2.dp.toPx(), needleGlow)
        }
        drawRect(ink, Offset(p - .75.dp.toPx(), 2.dp.toPx()), Size(1.5.dp.toPx(), h - 4.dp.toPx()))
        drawRoundRect(ink, Offset(p - 4.5.dp.toPx(), 0f), Size(9.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
    }
}
