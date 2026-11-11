package com.spektra.latent.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.PrintJob
import com.spektra.latent.data.Printed
import com.spektra.latent.engine.FilmEngine
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sign that a print is being made: one small tile under the top bar, at
 * the side, on every screen. It sits clear of the middle of the picture and
 * takes no touches except on itself, so the person goes on working -- in the
 * darkroom or anywhere else -- while the print is made behind it.
 *
 * The tile is the photograph before it can be seen: a handful of large pixels.
 * A wave runs across them; where it passes, each pixel opens into the three
 * dye layers of the film (yellow, magenta, cyan, as in the emulsion) and
 * closes again in its developed colour. The next wave takes them back.
 * It loops quickly for as long as the engine works. The line around the tile
 * is the engine's real progress.
 *
 * When the print is done the pixels get finer in a few steps until they are
 * the print, and the tile grows a little. A tap opens it; left alone it goes
 * away by itself.
 */
@Composable
fun PrintOverlay(desk: PrintDesk) {
    val jobs = desk.jobs
    val job = jobs.firstOrNull { it.state is PrintJob.State.Working }
        ?: jobs.firstOrNull { it.state is PrintJob.State.Waiting }
        ?: jobs.lastOrNull()
    var opened by remember { mutableStateOf<Long?>(null) }
    val open = job != null && opened == job.id
    val state = job?.state

    // a finished print leaves by itself, unless it is being looked at
    LaunchedEffect(job?.id, state is PrintJob.State.Done, open) {
        if (job != null && state is PrintJob.State.Done && !open) {
            delay(5000)
            desk.dismiss(job)
        }
    }
    // finished prints that are not the one shown are already in the gallery: nothing more to say about them
    val passed = jobs.count { it !== job && it.state is PrintJob.State.Done }
    LaunchedEffect(passed) {
        if (passed > 0) jobs.filter { it !== job && it.state is PrintJob.State.Done }.forEach(desk::dismiss)
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = job != null,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 62.dp, end = 6.dp),
            enter = fadeIn(tween(120)) + scaleIn(spring(0.6f, 520f), initialScale = 0.4f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.6f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            // the last job is remembered so that the tile can fade out after it has left the desk
            val shown = remember { mutableStateOf(job) }
            if (job != null) shown.value = job
            shown.value?.let { Tile(it, waiting = jobs.count { j -> j !== it && j.state is PrintJob.State.Waiting }) { opened = it.id } }
        }
        if (job != null) {
            Sheet(open, { opened = null }) { Details(job, desk) { opened = null } }
        }
    }
}

private val STAGES = listOf("读取原片", "曝光", "显影", "印相", "扫描", "保存")

@Composable
private fun Tile(job: PrintJob, waiting: Int, onTap: () -> Unit) {
    val state = job.state
    val done = state is PrintJob.State.Done
    val side by animateDpAsState(if (done) 84.dp else 56.dp, spring(dampingRatio = 0.66f, stiffness = 420f), label = "side")
    val progress by animateFloatAsState(
        when (state) {
            is PrintJob.State.Done -> 1f
            is PrintJob.State.Working -> (job.stage + 1) / 6.5f
            else -> 0f
        },
        tween(400), label = "progress",
    )
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(done) { if (done) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    val line = if (state is PrintJob.State.Failed) Ink.Red else Ink.Amber
    val description = when (state) {
        is PrintJob.State.Done -> "成片已保存，点开查看"
        is PrintJob.State.Failed -> "没有冲印出来，点开看原因"
        else -> "正在冲印，点开查看进度"
    }
    Box(Modifier.padding(6.dp)) {
        Box(Modifier.size(side).pressable(onClick = onTap).semantics { contentDescription = description }) {
            // the mount, and around it the line that follows the engine
            Canvas(Modifier.fillMaxSize()) {
                val corner = size.width * 0.25f
                val stroke = 2.dp.toPx()
                val inset = 2.dp.toPx()
                drawRoundRect(Ink.Body, cornerRadius = CornerRadius(corner))
                val around = ringPath(size, inset, corner - inset)
                drawPath(around, Ink.Line, style = Stroke(stroke))
                val shownProgress = if (state is PrintJob.State.Failed) 1f else progress
                if (shownProgress > 0f) {
                    val measure = PathMeasure().apply { setPath(around, false) }
                    val part = Path()
                    measure.getSegment(0f, measure.length * shownProgress, part, true)
                    drawPath(part, line, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            val inner = (side * 0.25f - 5.dp).coerceAtLeast(2.dp)
            Box(
                Modifier.padding(5.dp).fillMaxSize().clip(RoundedCornerShape(inner)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    is PrintJob.State.Done -> Resolved(state.printed, Modifier.fillMaxSize())
                    is PrintJob.State.Failed -> Txt("!", Type.title, color = Ink.Red)
                    else -> Loader(job.before, job.after, Modifier.fillMaxSize())
                }
            }
        }
        if (waiting > 0 && !done) {
            Box(
                Modifier.align(Alignment.BottomStart).size(22.dp).background(Ink.Raised, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Txt("+$waiting", Type.edgeSmall, color = Ink.Text)
            }
        }
    }
}

/** A rounded square drawn clockwise from the middle of its top edge, so that progress starts at twelve o'clock. */
private fun ringPath(size: Size, inset: Float, radius: Float): Path = Path().apply {
    val l = inset
    val t = inset
    val r = size.width - inset
    val b = size.height - inset
    val d = 2f * radius
    moveTo(size.width / 2f, t)
    lineTo(r - radius, t)
    arcTo(Rect(r - d, t, r, t + d), -90f, 90f, false)
    lineTo(r, b - radius)
    arcTo(Rect(r - d, b - d, r, b), 0f, 90f, false)
    lineTo(l + radius, b)
    arcTo(Rect(l, b - d, l + d, b), 90f, 90f, false)
    lineTo(l, t + radius)
    arcTo(Rect(l, t, l + d, t + d), 180f, 90f, false)
    lineTo(size.width / 2f, t)
}

/** One pass of the wave across the tile, in milliseconds. */
private const val SWEEP_MS = 850

/**
 * The photograph as GRID x GRID large pixels, with a wave running corner to
 * corner. In the wave a pixel opens into three bands -- the yellow, magenta
 * and cyan of the film's dye layers, stacked as they are in the emulsion --
 * and closes in the other picture's colour: original to developed on one pass, back on the next, so
 * the loop has no seam. Before the pixels have been read, grey ones do the same.
 */
@Composable
private fun Loader(before: IntArray?, after: IntArray?, modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "loader").animateFloat(
        0f, 2f, infiniteRepeatable(tween(2 * SWEEP_MS, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(modifier) {
        val n = PrintDesk.GRID
        val cell = size.width / n
        val gap = cell * 0.08f
        val w = cell - gap
        val back = turn >= 1f
        val pass = turn - floor(turn)
        for (j in 0 until n) for (i in 0 until n) {
            val k = j * n + i
            val along = (i + j) / (2f * (n - 1))
            val t = ((pass - 0.7f * along) / 0.3f).coerceIn(0f, 1f)   // a narrow front: two diagonals open at a time
            val open = sin(PI.toFloat() * t)
            val changed = smooth(0.3f, 0.7f, t)
            val a = before?.get(k) ?: 0xff4a4a4a.toInt()
            val b = after?.get(k) ?: a
            val from = if (back) b else a
            val to = if (back) a else b
            val red = mix(from shr 16 and 0xff, to shr 16 and 0xff, changed)
            val green = mix(from shr 8 and 0xff, to shr 8 and 0xff, changed)
            val blue = mix(from and 0xff, to and 0xff, changed)
            val x = i * cell + gap / 2f
            val y = j * cell + gap / 2f
            if (open < 0.004f) {
                drawRect(Color(red, green, blue), Offset(x, y), Size(w, w))
            } else {
                // the film's three dye layers, top to bottom as in the emulsion: yellow (it holds
                // back blue), magenta (green), cyan (red). Each band is the pixel with one channel taken out.
                val slit = open * w * 0.1f
                val band = (w - 2f * slit) / 3f
                val keep = 1f - 0.94f * open
                val bandSize = Size(w, band)
                drawRect(Color(red, green, blue * keep), Offset(x, y), bandSize)
                drawRect(Color(red, green * keep, blue), Offset(x, y + band + slit), bandSize)
                drawRect(Color(red * keep, green, blue), Offset(x, y + 2f * (band + slit)), bandSize)
            }
        }
    }
}

private fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mix(a: Int, b: Int, t: Float): Float = ((a + (b - a) * t) / 255f).coerceIn(0f, 1f)

/** The finished print coming clear: the same large pixels, finer at each step, until they are the picture. */
@Composable
private fun Resolved(printed: Printed, modifier: Modifier = Modifier) {
    val steps = remember(printed) {
        val shown = printed.shown
        val edge = min(shown.width, shown.height)
        val square = Bitmap.createBitmap(shown, (shown.width - edge) / 2, (shown.height - edge) / 2, edge, edge)
        listOf(PrintDesk.GRID * 2, PrintDesk.GRID * 4, PrintDesk.GRID * 8).map {
            Bitmap.createScaledBitmap(square, it, it, true).asImageBitmap()
        }
    }
    val full = remember(printed) { printed.shown.asImageBitmap() }
    val clear = remember(printed) { Animatable(0f) }
    LaunchedEffect(printed) { clear.animateTo(1f, tween(110 * (steps.size + 1), easing = LinearEasing)) }
    val level = (clear.value * (steps.size + 1)).toInt()
    if (level >= steps.size) {
        Image(bitmap = full, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Canvas(modifier) {
            drawImage(
                steps[level],
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

@Composable
private fun Details(job: PrintJob, desk: PrintDesk, close: () -> Unit) {
    val context = LocalContext.current
    val film = FilmEngine.catalog.film(job.look.film)?.name ?: job.look.film
    when (val state = job.state) {
        is PrintJob.State.Done -> {
            Box(Modifier.fillMaxWidth().heightIn(max = 380.dp), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = state.printed.shown.asImageBitmap(), contentDescription = "成片",
                    modifier = Modifier.background(Ink.Paper).padding(8.dp), contentScale = ContentScale.Fit,
                )
            }
            Gap(height = 14.dp)
            Txt("$film  ${state.printed.width} × ${state.printed.height}", Type.edge)
            Gap(height = 2.dp)
            Txt("已保存到相册的 Latent 相簿", Type.label, color = Ink.Dim)
            Gap(height = 16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("分享", {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, state.printed.uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            null,
                        ),
                    )
                }, Modifier.weight(1f))
                Pill("好", { close(); desk.dismiss(job) }, Modifier.weight(1f), filled = true)
            }
        }
        is PrintJob.State.Failed -> {
            Txt("没有冲印出来", Type.heading)
            Gap(height = 6.dp)
            Txt(state.reason, Type.body, color = Ink.Dim, maxLines = 5)
            Gap(height = 16.dp)
            Pill("知道了", { close(); desk.dismiss(job) }, Modifier.fillMaxWidth())
        }
        else -> {
            Txt("正在冲印", Type.heading)
            Gap(height = 6.dp)
            Txt("${job.frame.name}   $film   长边 ${job.longEdge}", Type.label, color = Ink.Dim, maxLines = 2)
            Gap(height = 4.dp)
            Txt(
                if (state is PrintJob.State.Waiting) "排在前一张后面" else STAGES[job.stage.coerceIn(0, STAGES.lastIndex)],
                Type.label, color = Ink.Amber,
            )
            Gap(height = 4.dp)
            Txt("冲印在后台进行，可以继续调整或回到片库。", Type.label, color = Ink.Dim, maxLines = 2)
            Gap(height = 16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("停止冲印", { close(); desk.stop(job) }, Modifier.weight(1f), danger = true)
                Pill("收起", close, Modifier.weight(1f))
            }
        }
    }
}
