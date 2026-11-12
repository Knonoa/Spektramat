package com.spektra.latent.ui.darkroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spektra.latent.model.Finishing
import com.spektra.latent.model.Look
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.ParamChip
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.pressable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The last stage: the print itself is finished, the way a scan is corrected.
 * Four tools -- tone curve, colour mixer, colour grading, vignette -- each
 * with the control its job needs: a curve is drawn, a tint is picked on a
 * wheel, and everything that is one number is still a ruler.
 */
@Composable
internal fun FinishBench(state: DarkroomState) {
    var tool by remember { mutableIntStateOf(0) }
    val look = state.look
    Column(Modifier.fillMaxWidth()) {
        LazyRow(Modifier.fillMaxWidth().height(60.dp), contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            item { ParamChip("曲线", if (Finishing.curveChanged(look)) "已调" else "—", Finishing.curveChanged(look), tool == 0, { tool = 0 }) }
            item { ParamChip("混色", if (Finishing.mixerChanged(look)) "已调" else "—", Finishing.mixerChanged(look), tool == 1, { tool = 1 }) }
            item { ParamChip("色彩分级", if (Finishing.gradingChanged(look)) "已调" else "—", Finishing.gradingChanged(look), tool == 2, { tool = 2 }) }
            item { ParamChip("暗角", signed(look.vignette), look.vignette != 0f, tool == 3, { tool = 3 }) }
        }
        when (tool) {
            0 -> CurveTool(state)
            1 -> MixerTool(state)
            2 -> GradingTool(state)
            else -> Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
                Ruler(
                    name = "暗角", value = look.vignette, min = -100f, max = 100f, step = 1f, major = 50f, default = 0f,
                    label = ::signed,
                    onChange = { state.change(state.look.copy(vignette = it)) }, onSettled = state::commit,
                )
            }
        }
    }
}

private fun signed(v: Float): String {
    val n = v.roundToInt()
    return if (n > 0) "+$n" else "$n"
}

private fun List<Float>.with(index: Int, value: Float): List<Float> = toMutableList().also { it[index] = value }

/** A small word that undoes a whole tool; shown only when there is something to undo. */
@Composable
private fun Reset(shown: Boolean, onClick: () -> Unit) {
    Txt(
        "复位", Type.label,
        Modifier.pressable(enabled = shown, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (shown) Ink.Text else Color.Transparent,
    )
}

// ---------------------------------------------------------------- tone curve

private val CURVE_NAMES = listOf("亮度", "红", "绿", "蓝")
private val CURVE_INKS = listOf(Ink.Text, Color(0xFFE5605F), Color(0xFF58C06B), Color(0xFF5B8DEF))

@Composable
private fun CurveTool(state: DarkroomState) {
    var channel by remember { mutableIntStateOf(0) }
    fun points(l: Look) = when (channel) { 0 -> l.curve; 1 -> l.curveR; 2 -> l.curveG; else -> l.curveB }
    fun with(l: Look, p: List<Float>) = when (channel) {
        0 -> l.copy(curve = p)
        1 -> l.copy(curveR = p)
        2 -> l.copy(curveG = p)
        else -> l.copy(curveB = p)
    }
    val current = points(state.look)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CURVE_NAMES.forEachIndexed { i, name ->
                val changed = when (i) { 0 -> state.look.curve; 1 -> state.look.curveR; 2 -> state.look.curveG; else -> state.look.curveB } != Finishing.IDENTITY
                Txt(
                    name, Type.label,
                    Modifier.pressable { channel = i }.padding(horizontal = 10.dp, vertical = 8.dp),
                    color = if (i == channel) Ink.Amber else if (changed) Ink.Text else Ink.Dim,
                )
            }
            Box(Modifier.weight(1f))
            Reset(current != Finishing.IDENTITY) {
                state.change(with(state.look, Finishing.IDENTITY))
                state.commit()
            }
        }
        CurveEditor(
            points = current, ink = CURVE_INKS[channel],
            onChange = { state.change(with(state.look, it)) }, onSettled = state::commit,
            modifier = Modifier.fillMaxWidth().height(216.dp).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp),
        )
    }
}

/**
 * A tone curve to draw on: dark tones at the left, light at the right, how
 * light each comes out going up. Touch the curve to put a point on it and
 * drag; a point dragged well off the top or bottom is taken away. The two end
 * points stay at the edges and only move up and down.
 */
@Composable
private fun CurveEditor(points: List<Float>, ink: Color, onChange: (List<Float>) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val now by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val settled by rememberUpdatedState(onSettled)
    Canvas(
        modifier
            .semantics { contentDescription = "色调曲线，${points.size / 2} 个点" }
            .pointerInput(Unit) {
                val reach = 26.dp.toPx()
                val gone = 44.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    var pts = now
                    // the point under the finger, or a new one put on the curve there
                    var index = -1
                    var best = reach
                    for (i in 0 until pts.size / 2) {
                        val d = hypot(pts[i * 2] * w - down.position.x, (1f - pts[i * 2 + 1]) * h - down.position.y)
                        if (d < best) {
                            best = d
                            index = i
                        }
                    }
                    if (index < 0) {
                        if (pts.size / 2 >= MAX_POINTS) return@awaitEachGesture
                        val x = (down.position.x / w).coerceIn(0.02f, 0.98f)
                        val after = (0 until pts.size / 2).indexOfFirst { pts[it * 2] > x }
                        if (after <= 0) return@awaitEachGesture
                        if (x - pts[(after - 1) * 2] < GAP || pts[after * 2] - x < GAP) return@awaitEachGesture
                        val samples = Finishing.sample(pts, 256)
                        val y = samples[(x * 255f).roundToInt()]
                        pts = pts.toMutableList().also {
                            it.add(after * 2, y)
                            it.add(after * 2, x)
                        }
                        index = after
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change(pts)
                    }
                    down.consume()
                    val last = pts.size / 2 - 1
                    var px = pts[index * 2] * w
                    var py = (1f - pts[index * 2 + 1]) * h
                    var removed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val move = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!move.pressed) break
                        val delta = move.positionChange()
                        move.consume()
                        px += delta.x
                        py += delta.y
                        val inner = index in 1 until last
                        // an inner point pulled well clear of the frame comes off; brought back, it is there again
                        val off = inner && (py < -gone || py > h + gone)
                        if (off != removed) {
                            removed = off
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        val next = pts.toMutableList()
                        if (inner) next[index * 2] = (px / w).coerceIn(pts[(index - 1) * 2] + GAP, pts[(index + 1) * 2] - GAP)
                        next[index * 2 + 1] = (1f - py / h).coerceIn(0f, 1f)
                        pts = next
                        change(if (removed) next.filterIndexed { i, _ -> i / 2 != index } else next)
                    }
                    settled()
                }
            },
    ) {
        val w = size.width
        val h = size.height
        // the frame, quarters, and the straight line the curve starts from
        drawRect(Ink.Raised)
        for (k in 1..3) {
            drawLine(Ink.Line, Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1f)
            drawLine(Ink.Line, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
        }
        drawLine(Ink.Faint.copy(alpha = 0.6f), Offset(0f, h), Offset(w, 0f), 1f)
        val samples = Finishing.sample(points, 128)
        val path = Path()
        for (i in samples.indices) {
            val x = i / (samples.size - 1f) * w
            val y = (1f - samples[i]) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ink, style = Stroke(2.dp.toPx()))
        for (i in 0 until points.size / 2) {
            val at = Offset(points[i * 2] * w, (1f - points[i * 2 + 1]) * h)
            drawCircle(Ink.Body, 5.5.dp.toPx(), at)
            drawCircle(ink, 5.5.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
        }
    }
}

private const val MAX_POINTS = 12
/** Two points are never closer than this along the tone axis. */
private const val GAP = 0.03f

// ---------------------------------------------------------------- colour mixer

private val MIXER_PARTS = listOf("色相", "饱和度", "明度")

@Composable
private fun MixerTool(state: DarkroomState) {
    var hue by remember { mutableIntStateOf(0) }
    var part by remember { mutableIntStateOf(1) }
    val mixer = state.look.mixer
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Finishing.HUE_COLOURS.forEachIndexed { i, c ->
                    val touched = (0 until 3).any { mixer[i * 3 + it] != 0f }
                    Box(
                        Modifier
                            .size(34.dp)
                            .pressable { hue = i }
                            .semantics { contentDescription = Finishing.HUE_NAMES[i] },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (i == hue) 26.dp else 20.dp)
                                .background(Color(c[0], c[1], c[2]).copy(alpha = if (i == hue || touched) 1f else 0.55f), CircleShape)
                                .then(if (i == hue) Modifier.border(2.dp, Ink.Text, CircleShape) else Modifier),
                        )
                    }
                }
            }
            Reset(Finishing.mixerChanged(state.look)) {
                state.change(state.look.copy(mixer = Finishing.NO_MIXER))
                state.commit()
            }
        }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(Finishing.HUE_NAMES[hue], Type.label, Modifier.padding(horizontal = 10.dp), color = Ink.Dim)
            MIXER_PARTS.forEachIndexed { i, name ->
                val v = mixer[hue * 3 + i]
                ParamChip(name, signed(v), v != 0f, i == part, { part = i })
            }
        }
        Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
            val index = hue * 3 + part
            Ruler(
                name = "${Finishing.HUE_NAMES[hue]}的${MIXER_PARTS[part]}", value = mixer[index],
                min = -100f, max = 100f, step = 1f, major = 50f, default = 0f, label = ::signed,
                onChange = { state.change(state.look.copy(mixer = state.look.mixer.with(index, it))) },
                onSettled = state::commit,
            )
        }
    }
}

// ---------------------------------------------------------------- colour grading

private val GRADING_DIALS = listOf("阴影明度", "中间调明度", "高光明度", "平衡", "过渡")

@Composable
private fun GradingTool(state: DarkroomState) {
    var dial by remember { mutableIntStateOf(3) }
    val look = state.look
    val g = look.grading
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (range in 0 until 3) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TintWheel(
                            hue = g[range * 3], saturation = g[range * 3 + 1], name = Finishing.RANGE_NAMES[range],
                            onChange = { h, s ->
                                state.change(state.look.copy(grading = state.look.grading.with(range * 3, h).with(range * 3 + 1, s)))
                            },
                            onSettled = state::commit,
                            modifier = Modifier.size(92.dp),
                        )
                        Txt(Finishing.RANGE_NAMES[range], Type.caption, color = if (g[range * 3 + 1] > 0f) Ink.Text else Ink.Dim)
                    }
                }
            }
            Reset(Finishing.gradingChanged(look) || look.gradingBalance != 0f || look.gradingBlending != 50f) {
                state.change(state.look.copy(grading = Finishing.NO_GRADING, gradingBalance = 0f, gradingBlending = 50f))
                state.commit()
            }
        }
        LazyRow(Modifier.fillMaxWidth().height(56.dp), contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(GRADING_DIALS.size) { i ->
                val v = when (i) { 3 -> look.gradingBalance; 4 -> look.gradingBlending; else -> g[i * 3 + 2] }
                val rest = if (i == 4) 50f else 0f
                ParamChip(GRADING_DIALS[i], if (i == 4) "${v.roundToInt()}" else signed(v), v != rest, i == dial, { dial = i })
            }
        }
        Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
            when (dial) {
                3 -> Ruler(
                    name = "平衡", value = look.gradingBalance, min = -100f, max = 100f, step = 1f, major = 50f, default = 0f, label = ::signed,
                    onChange = { state.change(state.look.copy(gradingBalance = it)) }, onSettled = state::commit,
                )
                4 -> Ruler(
                    name = "过渡", value = look.gradingBlending, min = 0f, max = 100f, step = 1f, major = 50f, default = 50f,
                    label = { "${it.roundToInt()}" },
                    onChange = { state.change(state.look.copy(gradingBlending = it)) }, onSettled = state::commit,
                )
                else -> {
                    val index = dial * 3 + 2
                    Ruler(
                        name = GRADING_DIALS[dial], value = g[index], min = -100f, max = 100f, step = 1f, major = 50f, default = 0f, label = ::signed,
                        onChange = { state.change(state.look.copy(grading = state.look.grading.with(index, it))) },
                        onSettled = state::commit,
                    )
                }
            }
        }
    }
}

/**
 * A tint, picked the way colour is: which way from the centre is the hue, how
 * far out is how strong. The centre is no tint at all; a double tap goes back
 * there.
 */
@Composable
private fun TintWheel(
    hue: Float, saturation: Float, name: String,
    onChange: (Float, Float) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val settled by rememberUpdatedState(onSettled)
    Canvas(
        modifier
            .semantics { contentDescription = "${name}的色调" }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    change(0f, 0f)
                    settled()
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val reach = min(size.width, size.height) / 2f - 9.dp.toPx()
                    var moved = false
                    var start = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val move = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!move.pressed) break
                        // a tap is left to the double-tap detector; a drag sets the tint
                        if (!moved && (move.position - start).getDistance() < viewConfiguration.touchSlop) continue
                        moved = true
                        move.consume()
                        val v = move.position - centre
                        val strength = (v.getDistance() / reach).coerceIn(0f, 1f) * 100f
                        val angle = ((atan2(v.y, v.x) * 180f / PI.toFloat()) + 360f) % 360f
                        if (strength < 4f) change(angle, 0f) else change(angle, strength)
                    }
                    if (moved) settled()
                }
            },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val outer = min(size.width, size.height) / 2f - 3.dp.toPx()
        val reach = outer - 6.dp.toPx()
        drawCircle(Ink.Raised, reach, centre)
        // the hues round the rim, where they are on the wheel
        drawCircle(
            Brush.sweepGradient(RIM, centre), outer, centre, style = Stroke(3.dp.toPx()),
            alpha = if (saturation > 0f) 1f else 0.55f,
        )
        drawLine(Ink.Line, Offset(centre.x - reach, centre.y), Offset(centre.x + reach, centre.y), 1f)
        drawLine(Ink.Line, Offset(centre.x, centre.y - reach), Offset(centre.x, centre.y + reach), 1f)
        val angle = hue * PI.toFloat() / 180f
        val r = reach * saturation / 100f
        val at = Offset(centre.x + cos(angle) * r, centre.y + sin(angle) * r)
        val c = Finishing.hueColour(hue)
        val k = saturation / 100f
        val fill = Color(lerp(0.55f, c[0], k), lerp(0.55f, c[1], k), lerp(0.55f, c[2], k))
        drawCircle(Ink.Body, 8.dp.toPx(), at)
        drawCircle(fill, 6.dp.toPx(), at)
        if (abs(saturation) > 0f) drawCircle(Ink.Text, 8.dp.toPx(), at, style = Stroke(1.2.dp.toPx()))
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Hue 0 (red) points right and the hues run clockwise, as Compose sweeps a gradient. */
private val RIM = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)
