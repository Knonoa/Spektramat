package com.spektra.latent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/*
 * The character matrix: how this app looks when it is waiting, empty, or at
 * work on something it cannot show yet.
 *
 * A grid of monospace characters whose weight follows a field of brightness:
 * slow noise (the room at rest), a word, or a photograph -- which then shows
 * through as characters, each in the colour of the picture under it. It is
 * drawn at a modest frame rate and only while it is on screen.
 */

/** From nothing to solid: the characters a cell can be, by how bright the field is there. */
private const val RAMP = " .·:-=+*xX#%@"

/** What the grid shows. */
sealed interface GlyphSubject {
    /** Slow drifting noise. */
    data object Room : GlyphSubject

    /** A word standing out of the noise in dense, flickering characters. Latin capitals and digits read best. */
    data class Word(val text: String) : GlyphSubject

    /**
     * A photograph as characters. [revealed] in 0..1 is how far down the
     * picture has come out; below that line there is only faint noise.
     */
    class Picture(val bitmap: Bitmap, val revealed: Float = 1f) : GlyphSubject
}

private var monoFace: Typeface? = null
private fun mono(context: Context): Typeface = monoFace ?: try {
    Typeface.createFromAsset(context.assets, "fonts/DejaVuSansMono-Bold.ttf")
} catch (e: Exception) {
    Typeface.MONOSPACE
}.also { monoFace = it }

/** The system setting that turns animations off: honoured by everything here that moves by itself. */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun hash(x: Float, y: Float): Float {
    val s = sin(x * 127.1f + y * 311.7f) * 43758.547f
    return s - floor(s)
}

/** Smooth value noise in 0..1. */
private fun noise(x: Float, y: Float): Float {
    val xi = floor(x)
    val yi = floor(y)
    val xf = x - xi
    val yf = y - yi
    val u = xf * xf * (3 - 2 * xf)
    val v = yf * yf * (3 - 2 * yf)
    val a = hash(xi, yi)
    val b = hash(xi + 1, yi)
    val c = hash(xi, yi + 1)
    val d = hash(xi + 1, yi + 1)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
}

/** A brightness per cell, [cols] x [rows], and for a picture its colours. */
private class Field(val cols: Int, val rows: Int, val luma: FloatArray?, val argb: IntArray?)

private fun fieldOf(subject: GlyphSubject, cols: Int, rows: Int, cellW: Float, cellH: Float, face: Typeface): Field {
    if (cols <= 0 || rows <= 0) return Field(0, 0, null, null)
    when (subject) {
        GlyphSubject.Room -> return Field(cols, rows, null, null)
        is GlyphSubject.Word -> {
            // the word drawn small, one pixel per cell
            val bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = face
                textAlign = Paint.Align.CENTER
                // cells are taller than wide: the word is drawn narrow so that it reads true on the grid
                textScaleX = cellH / cellW
                textSize = min(rows * 0.62f, cols / (subject.text.length.coerceAtLeast(1) * 0.66f * (cellH / cellW)))
            }
            AndroidCanvas(bitmap).drawText(subject.text, cols / 2f, rows * 0.5f - (paint.ascent() + paint.descent()) / 2f, paint)
            val px = IntArray(cols * rows)
            bitmap.getPixels(px, 0, cols, 0, 0, cols, rows)
            bitmap.recycle()
            return Field(cols, rows, FloatArray(px.size) { (px[it] and 0xff) / 255f }, null)
        }
        is GlyphSubject.Picture -> {
            // the photograph fitted inside the grid, keeping its shape on screen
            val src = subject.bitmap
            val gridW = cols * cellW
            val gridH = rows * cellH
            val k = min(gridW / src.width, gridH / src.height)
            val w = (src.width * k / cellW).roundToInt().coerceIn(1, cols)
            val h = (src.height * k / cellH).roundToInt().coerceIn(1, rows)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== src) small.recycle()
            val luma = FloatArray(cols * rows)
            val argb = IntArray(cols * rows)
            val x0 = (cols - w) / 2
            val y0 = (rows - h) / 2
            for (y in 0 until h) for (x in 0 until w) {
                val p = px[y * w + x]
                val i = (y + y0) * cols + x + x0
                argb[i] = p or (0xff shl 24)
                luma[i] = (0.2126f * (p shr 16 and 0xff) + 0.7152f * (p shr 8 and 0xff) + 0.0722f * (p and 0xff)) / 255f
            }
            return Field(cols, rows, luma, argb)
        }
    }
}

/**
 * The character matrix.
 *
 * @param cell the height of one character cell
 * @param strength how much of the room's noise shows, 0..1
 * @param ink the colour of a strong character; weak ones are drawn in [faint]
 */
@Composable
fun GlyphField(
    subject: GlyphSubject,
    modifier: Modifier = Modifier,
    cell: Dp = 11.dp,
    strength: Float = 1f,
    ink: Color = Ink.Faint,
    faint: Color = Ink.Line,
    spark: Color = Ink.Text,
) {
    val context = LocalContext.current
    val face = remember { mono(context) }
    val still = remember { animationsOff(context) }
    var time by remember { mutableFloatStateOf(0f) }
    // about twenty pictures a second is plenty for drifting characters
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        val start = withFrameNanos { it }
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - last >= 48_000_000L) {
                last = now
                time = (now - start) / 1e9f
            }
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face } }
    var field by remember { mutableStateOf(Field(0, 0, null, null)) }
    var fieldKey by remember { mutableStateOf<Any?>(null) }

    Canvas(modifier) {
        val cellH = cell.toPx()
        paint.textSize = cellH * 0.92f
        val cellW = paint.measureText("M")
        val cols = ceil(size.width / cellW).toInt()
        val rows = ceil(size.height / cellH).toInt()
        if (cols <= 0 || rows <= 0) return@Canvas
        val key = Triple(subject, cols, rows)
        if (key != fieldKey && !(subject is GlyphSubject.Picture && (fieldKey as? Triple<*, *, *>)?.let {
                (it.first as? GlyphSubject.Picture)?.bitmap === subject.bitmap && it.second == cols && it.third == rows
            } == true)) {
            field = fieldOf(subject, cols, rows, cellW, cellH, face)
        }
        fieldKey = key
        val f = field
        val t = time
        val baseline = -paint.ascent() * 0.9f
        val scan = (subject as? GlyphSubject.Picture)?.revealed?.coerceIn(0f, 1f)?.times(rows) ?: 0f
        val colours = intArrayOf(faint.toArgb(), ink.toArgb(), spark.toArgb())
        val lines = Array(3) { CharArray(cols) }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val one = CharArray(1)
            for (y in 0 until rows) {
                for (l in lines) l.fill(' ')
                var any = false
                for (x in 0 until cols) {
                    val n = noise(x * 0.09f + t * 0.35f, y * 0.11f - t * 0.22f)
                    val luma = f.luma
                    var v: Float
                    var tone = -1   // -1: decided by how strong the character is
                    when {
                        luma == null -> v = n.pow(2.6f) * strength
                        f.argb != null -> {
                            // a photograph: revealed above the scan line, faint noise below it
                            if (y > scan || f.argb[y * cols + x] == 0) {
                                v = n * 0.14f * strength
                            } else {
                                v = 0.22f + luma[y * cols + x] * (0.72f + 0.28f * (hash(x.toFloat(), y + floor(t * 12f)) - 0.5f))
                                val k = (v * (RAMP.length - 1)).roundToInt().coerceIn(0, RAMP.length - 1)
                                if (k > 0) {
                                    one[0] = RAMP[k]
                                    paint.color = f.argb[y * cols + x]
                                    native.drawText(one, 0, 1, x * cellW, y * cellH + baseline, paint)
                                }
                                continue
                            }
                        }
                        luma[y * cols + x] > 0.5f -> {
                            v = 0.55f + 0.45f * hash(x + floor(t * 8f), y.toFloat())
                            tone = if (hash(x.toFloat(), y + floor(t * 3f)) > 0.82f) 2 else 1
                        }
                        else -> v = n.pow(3f) * 0.5f * strength
                    }
                    val k = (v * (RAMP.length - 1)).roundToInt().coerceIn(0, RAMP.length - 1)
                    if (k == 0) continue
                    if (tone < 0) tone = if (v > 0.86f && hash(x.toFloat(), y.toFloat()) > 0.7f) 2 else if (k > (RAMP.length - 1) * 0.55f) 1 else 0
                    lines[tone][x] = RAMP[k]
                    any = true
                }
                if (!any) continue
                // a monospace face: a whole row of one tone is one call, the gaps are spaces
                for (tone in 0..2) {
                    paint.color = colours[tone]
                    native.drawText(lines[tone], 0, cols, 0f, y * cellH + baseline, paint)
                }
            }
        }
    }
}

private const val LATIN = "ABCDEFGHJKLMNPQRSTUVWXYZ#%*+=<>/"
private const val DIGITS = "0123456789"
// the words of a darkroom: a Chinese title settles out of these, so its width never jumps
private const val HAN = "胶片显影定影冲印曝光潜影银盐相纸底片暗房光圈快门颗粒"

private fun stray(c: Char, seed: Int): Char = when {
    c == ' ' -> ' '
    c.isDigit() -> DIGITS[seed.mod(DIGITS.length)]
    c.code > 0x2E80 -> HAN[seed.mod(HAN.length)]
    c.isLetter() -> LATIN[seed.mod(LATIN.length)]
    else -> c
}

/**
 * Text that arrives: each character runs through a few others before it
 * settles, left to right. It does this once when it first appears and again
 * whenever [text] changes -- an answer to something having changed, never a
 * loop.
 */
@Composable
fun ScrambleText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    stepMs: Long = 34,
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (animationsOff(context) || text.isEmpty()) {
            shown = text
            return@LaunchedEffect
        }
        // each character settles a little after the one before it; all of it is over in under half a second
        val steps = (text.length + 5).coerceAtMost(13)
        for (step in 0..steps) {
            val settled = (step.toFloat() / steps * (text.length + 1)).toInt() - 1
            shown = buildString(text.length) {
                text.forEachIndexed { i, c -> append(if (i <= settled) c else stray(c, i * 31 + step * 17 + text.length)) }
            }
            delay(stepMs)
        }
        shown = text
    }
    Txt(shown, style, modifier, color = color)
}
