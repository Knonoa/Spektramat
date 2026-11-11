package com.spektra.latent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The app's one way of setting a number: a scale that slides under a fixed
 * amber needle, like the distance scale of a lens.
 *
 * Drag to move the scale; let go while moving and it coasts. It clicks (a
 * light haptic) at every numbered mark and holds a little at the default, so
 * "back to zero" can be felt without looking. Double-tap returns to the
 * default.
 *
 * @param major spacing of the numbered marks, in the value's unit; ten small
 *   marks to each.
 * @param onChange every step while moving
 * @param onSettled once, when the hand and the coasting have both stopped
 */
@Composable
fun Ruler(
    name: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    major: Float,
    default: Float,
    label: (Float) -> String,
    onChange: (Float) -> Unit,
    onSettled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val minor = major / 10f
    val spacing = with(density) { 12.dp.toPx() }
    val pxPerUnit = spacing / minor
    val detent = minor * 0.4f

    val change by rememberUpdatedState(onChange)
    val settled by rememberUpdatedState(onSettled)
    val current by rememberUpdatedState(value)

    // where the scale is; between steps while it moves
    var position by remember { mutableFloatStateOf(value) }
    val coast = remember { Animatable(value) }
    fun quantise(v: Float): Float {
        val q = (v / step).roundToInt() * step
        return (if (abs(v - default) < detent) default else q).coerceIn(min, max)
    }
    // an undo, a reset or another photograph moved the value from outside
    LaunchedEffect(value) {
        if (abs(quantise(position) - value) > step * 0.5f) {
            coast.stop()
            position = value
        }
    }
    fun moveTo(v: Float) {
        val clamped = v.coerceIn(min, max)
        position = clamped
        val q = quantise(clamped)
        if (q != current) {
            val crossedMajor = floor(q / major) != floor(current / major) || q == default
            if (crossedMajor) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(q)
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .semantics {
                contentDescription = name
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max)
                setProgress { target -> change(quantise(target)); settled(); true }
            }
            .pointerInput(min, max, step, major, default) {
                detectTapGestures(onDoubleTap = {
                    scope.launch {
                        coast.stop()
                        coast.snapTo(position)
                        coast.animateTo(default, spring(stiffness = 300f)) { moveTo(this.value) }
                        moveTo(default)
                        settled()
                    }
                })
            }
            .pointerInput(min, max, step, major, default) {
                val friction = exponentialDecay<Float>(frictionMultiplier = 2.4f)
                val tracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { coast.stop() }
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragged = false
                    horizontalDrag(down.id) { c ->
                        val dx = c.positionChange().x
                        if (dx != 0f) {
                            dragged = true
                            tracker.addPosition(c.uptimeMillis, c.position)
                            c.consume()
                            moveTo(position - dx / pxPerUnit)
                        }
                    }
                    if (dragged) {
                        val velocity = -tracker.calculateVelocity().x / pxPerUnit
                        scope.launch {
                            if (abs(velocity) > 3f * minor) {
                                coast.updateBounds(min, max)
                                coast.snapTo(position)
                                coast.animateDecay(velocity, friction) { moveTo(this.value) }
                            }
                            // rest on a step, not between two
                            position = quantise(position)
                            settled()
                        }
                    }
                }
            },
    ) {
        val cx = size.width / 2f
        val half = size.width / 2f
        val baseline = size.height * 0.62f
        val shown = if (abs(position - default) < detent) default else position
        val first = ceil((shown - half / pxPerUnit) / minor).toInt()
        val last = floor((shown + half / pxPerUnit) / minor).toInt()
        for (k in first..last) {
            val v = k * minor
            if (v < min - minor * 0.01f || v > max + minor * 0.01f) continue
            val x = cx + (v - shown) * pxPerUnit
            val edge = 1f - (abs(x - cx) / half).let { it * it }   // the scale fades into the sides
            val isMajor = k % 10 == 0
            val isMid = k % 5 == 0
            val length = if (isMajor) 20.dp.toPx() else if (isMid) 13.dp.toPx() else 8.dp.toPx()
            tick(x, baseline - length, baseline, (if (isMajor) Ink.Dim else Ink.Faint).copy(alpha = edge), 1.dp.toPx())
            if (isMajor) {
                val text = measurer.measure(label(v), Type.edgeSmall)
                drawText(text, topLeft = Offset(x - text.size.width / 2f, baseline + 5.dp.toPx()), alpha = edge)
            }
        }
        // where the default is: a small mark below the scale
        val dx = cx + (default - shown) * pxPerUnit
        if (dx > 0f && dx < size.width) {
            drawCircle(Ink.Dim, radius = 1.6.dp.toPx(), center = Offset(dx, baseline - 26.dp.toPx()))
        }
        // the needle
        tick(cx, baseline - 30.dp.toPx(), baseline + 2.dp.toPx(), Ink.Amber, 2.dp.toPx())
        drawPath(
            Path().apply {
                moveTo(cx - 4.dp.toPx(), baseline - 36.dp.toPx())
                lineTo(cx + 4.dp.toPx(), baseline - 36.dp.toPx())
                lineTo(cx, baseline - 30.dp.toPx())
                close()
            },
            Ink.Amber,
        )
    }
}
