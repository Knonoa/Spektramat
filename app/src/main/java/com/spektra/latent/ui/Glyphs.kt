package com.spektra.latent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.spektra.latent.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The character matrix: how this app looks when there is nothing to see yet --
 * starting up, a bench with no photograph on it, a monitor with no camera.
 *
 * A grid of characters that change all the time. Where the thing they stand
 * for is (a word, a strip of film, a camera) they are dense; elsewhere a few
 * faint ones come and go. A line of light passes down the grid, and what it
 * touches of the shape turns amber for a moment.
 */

private const val GLYPHS = "▚▞░▒▓#@%&$*+=-<>/\\|0123456789ABCDEF"

/** What the characters gather into. Lengths are in dp, as the design gives them. */
sealed interface GlyphSubject {
    /** Nothing: only the faint ones. */
    data object Room : GlyphSubject

    /** A strip of empty 135 frames, slowly advancing: nothing is loaded yet. [frac] of the height, about [centre]. */
    data class Film(val frac: Float = .3f, val centre: Float = .36f) : GlyphSubject

    /** A camera on the end of a cable: plug one in. */
    data class Camera(val centre: Float = .27f) : GlyphSubject

    /** A word, set large. */
    data class Word(val text: String, val y0: Float = .46f, val fit: Float = .8f, val font: Float = 96f) : GlyphSubject

    /**
     * A photograph as characters, each in the colour of the picture under it. [revealed] in 0..1 is
     * how far down the picture has come out; below that line there is only the faint field.
     */
    class Picture(val bitmap: Bitmap, val revealed: Float = 1f) : GlyphSubject
}

/** With fewer cells than this to a letter, a drawn letter loses its strokes on the grid. */
private const val DRAWN_CELLS = 9

/** A 5 x 7 dot face, capitals and digits: seven rows from the top. */
private val DOT_FACE: Map<Char, String> = mapOf(
    'A' to ".###. #...# #...# ##### #...# #...# #...#",
    'B' to "####. #...# #...# ####. #...# #...# ####.",
    'C' to ".###. #...# #.... #.... #.... #...# .###.",
    'D' to "####. #...# #...# #...# #...# #...# ####.",
    'E' to "##### #.... #.... ####. #.... #.... #####",
    'F' to "##### #.... #.... ####. #.... #.... #....",
    'G' to ".###. #...# #.... #.### #...# #...# .###.",
    'H' to "#...# #...# #...# ##### #...# #...# #...#",
    'I' to ".###. ..#.. ..#.. ..#.. ..#.. ..#.. .###.",
    'J' to "..### ...#. ...#. ...#. ...#. #..#. .##..",
    'K' to "#...# #..#. #.#.. ##... #.#.. #..#. #...#",
    'L' to "#.... #.... #.... #.... #.... #.... #####",
    'M' to "#...# ##.## #.#.# #.#.# #...# #...# #...#",
    'N' to "#...# #...# ##..# #.#.# #..## #...# #...#",
    'O' to ".###. #...# #...# #...# #...# #...# .###.",
    'P' to "####. #...# #...# ####. #.... #.... #....",
    'Q' to ".###. #...# #...# #...# #.#.# #..#. .##.#",
    'R' to "####. #...# #...# ####. #.#.. #..#. #...#",
    'S' to ".#### #.... #.... .###. ....# ....# ####.",
    'T' to "##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..",
    'U' to "#...# #...# #...# #...# #...# #...# .###.",
    'V' to "#...# #...# #...# #...# #...# .#.#. ..#..",
    'W' to "#...# #...# #...# #.#.# #.#.# #.#.# .#.#.",
    'X' to "#...# #...# .#.#. ..#.. .#.#. #...# #...#",
    'Y' to "#...# #...# .#.#. ..#.. ..#.. ..#.. ..#..",
    'Z' to "##### ....# ...#. ..#.. .#... #.... #####",
    '0' to ".###. #...# #..## #.#.# ##..# #...# .###.",
    '1' to "..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###.",
    '2' to ".###. #...# ....# ...#. ..#.. .#... #####",
    '3' to "##### ...#. ..#.. ...#. ....# #...# .###.",
    '4' to "...#. ..##. .#.#. #..#. ##### ...#. ...#.",
    '5' to "##### #.... ####. ....# ....# #...# .###.",
    '6' to "..##. .#... #.... ####. #...# #...# .###.",
    '7' to "##### ....# ...#. ..#.. .#... .#... .#...",
    '8' to ".###. #...# #...# .###. #...# #...# .###.",
    '9' to ".###. #...# #...# .#### ....# ...#. .##..",
)

/**
 * [text] in the dot face, a cell to a dot (more where whole cells allow), on the grid's own lines. False when the
 * word is short enough to be drawn as letters, or too long for the grid even at a cell to a dot.
 */
private fun dots(text: String, canvas: AndroidCanvas, w: Int, h: Int, cell: Float, y0: Float, paint: Paint): Boolean {
    val n = text.length
    val cols = (w / cell).toInt()
    val rows = (h / cell).toInt()
    if (n == 0 || cols / n >= DRAWN_CELLS) return false
    val wide = n * 6 - 1                       // five dots to a letter and one between
    val k = min(cols / (wide + 2), rows / 9)   // at least a dot of air on every side
    if (k < 1) return false
    val x0 = (cols - wide * k) / 2
    val top = (rows * y0).roundToInt() - 7 * k / 2
    for ((i, ch) in text.withIndex()) {
        val glyph = DOT_FACE[ch.uppercaseChar()] ?: continue
        for (gy in 0 until 7) for (gx in 0 until 5) {
            if (glyph[gy * 6 + gx] != '#') continue   // the rows of a glyph are five dots and a space
            val x = (x0 + (i * 6 + gx) * k) * cell
            val y = (top + gy * k) * cell
            canvas.drawRect(x, y, x + k * cell, y + k * cell, paint)
        }
    }
    return true
}

private var monoFace: Typeface? = null
internal fun mono(context: Context): Typeface = monoFace ?: try {
    Typeface.createFromAsset(context.assets, "fonts/DejaVuSansMono-Bold.ttf")
} catch (e: Exception) {
    Typeface.MONOSPACE
}.also { monoFace = it }

/** Animations are off: the app's own switch, or the system's. Honoured by everything here that moves by itself. */
fun animationsOff(context: Context): Boolean =
    context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("reduceMotion", false) ||
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** A number in 0..1 that is the same every time for the same three. */
private fun chance(x: Int, y: Int, t: Int): Float {
    var h = x * 374761393 + y * 668265263 + t * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xffffff) / 16777216f
}

/** The shape as a picture of how dense the characters are, one sample to a dp. */
private class Mask(val w: Int, val h: Int, val alpha: ByteArray, val colour: IntArray? = null, val period: Int = 0, val speed: Float = 0f) {
    fun at(x: Int, y: Int): Float = if (x < 0 || y < 0 || x >= w || y >= h) 0f else (alpha[y * w + x].toInt() and 0xff) / 255f
}

private fun maskOf(subject: GlyphSubject, wDp: Int, hDp: Int, cell: Float, bold: Typeface): Mask? {
    if (wDp <= 0 || hDp <= 0 || subject == GlyphSubject.Room) return null
    // a strip of film runs past: it is drawn one frame longer than the field, and the frames and the
    // perforations both repeat exactly in that length, so it can go round without a seam
    val period = if (subject is GlyphSubject.Film) {
        val pitch = hDp * subject.frac * .92f + cell * 2.2f
        (max(1, (pitch / (cell * 2)).roundToInt()) * cell * 2).roundToInt()
    } else 0
    val w = wDp + period
    val bitmap = Bitmap.createBitmap(w, hDp, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE }
    fun a(v: Float) = (v * 255).roundToInt()
    var colour: IntArray? = null
    when (subject) {
        is GlyphSubject.Film -> {
            val sh = hDp * subject.frac
            val top = hDp * subject.centre - sh / 2
            val hole = cell * 1.1f
            val pitch = period.toFloat()
            fill.alpha = a(.07f)
            canvas.drawRect(0f, top, w.toFloat(), top + sh, fill)
            fill.alpha = a(.9f)
            var px = 0f
            while (px < w) {
                canvas.drawRect(px, top + cell * 1.2f, px + hole, top + cell * 1.2f + hole, fill)
                canvas.drawRect(px, top + sh - cell * 1.2f - hole, px + hole, top + sh - cell * 1.2f, fill)
                px += cell * 2
            }
            stroke.strokeWidth = cell * .95f
            var fx = cell
            while (fx < w) {
                fill.alpha = a(.14f)
                canvas.drawRect(fx, top + cell * 3.6f, fx + pitch - cell * 2.2f, top + sh - cell * 3.6f, fill)
                stroke.alpha = a(.9f)
                canvas.drawRect(fx, top + cell * 3.6f, fx + pitch - cell * 2.2f, top + sh - cell * 3.6f, stroke)
                fx += pitch
            }
        }
        is GlyphSubject.Camera -> {
            val bw = min(w * .5f, 230f)
            val bh = bw * .62f
            val cx = w / 2f
            val cy = hDp * subject.centre
            val l = cx - bw / 2
            val t = cy - bh / 2
            fill.alpha = a(.12f)
            canvas.drawRect(l, t, l + bw, t + bh, fill)
            fill.alpha = 255
            stroke.strokeWidth = cell * .9f
            canvas.drawRect(l, t, l + bw, t + bh, stroke)
            canvas.drawRect(cx - bw * .16f, t - bh * .16f, cx + bw * .16f, t, fill)
            canvas.drawRect(l + bw * .08f, t - bh * .07f, l + bw * .2f, t, fill)
            canvas.drawCircle(cx, cy + bh * .04f, bh * .30f, stroke)
            canvas.drawCircle(cx, cy + bh * .04f, bh * .13f, fill)
            // the cable, out of the picture at the right
            canvas.drawPath(AndroidPath().apply {
                moveTo(l + bw, cy + bh * .2f)
                cubicTo(l + bw + bw * .28f, cy + bh * .2f, l + bw + bw * .12f, cy + bh * 1.05f, w + 20f, cy + bh * .95f)
            }, stroke)
        }
        is GlyphSubject.Word -> if (!dots(subject.text, canvas, w, hDp, cell, subject.y0, fill)) {
            fill.typeface = bold
            fill.textAlign = Paint.Align.CENTER
            fill.textSize = subject.font
            val wide = fill.measureText(subject.text)
            if (wide > w * subject.fit) fill.textSize = floor(subject.font * w * subject.fit / wide)
            canvas.drawText(subject.text, w / 2f, hDp * subject.y0 - (fill.ascent() + fill.descent()) / 2f, fill)
        }
        is GlyphSubject.Picture -> {
            // the photograph fitted inside the field, keeping its shape
            val src = subject.bitmap
            val k = min(w.toFloat() / src.width, hDp.toFloat() / src.height)
            val pw = max(1, (src.width * k).roundToInt())
            val ph = max(1, (src.height * k).roundToInt())
            val left = (w - pw) / 2f
            val top = (hDp - ph) / 2f
            canvas.drawBitmap(src, null, android.graphics.RectF(left, top, left + pw, top + ph), Paint(Paint.FILTER_BITMAP_FLAG))
            colour = IntArray(w * hDp)
        }
        GlyphSubject.Room -> Unit
    }
    val px = IntArray(w * hDp)
    bitmap.getPixels(px, 0, w, 0, 0, w, hDp)
    bitmap.recycle()
    colour?.let { System.arraycopy(px, 0, it, 0, px.size) }
    return Mask(w, hDp, ByteArray(px.size) { (px[it] ushr 24).toByte() }, colour, period, if (period > 0) 9f else 0f)
}

/**
 * The character matrix.
 *
 * @param cell the side of one character's cell
 * @param fps how often the characters change; ten times a second is plenty
 * @param noise how many of the cells outside the shape hold a faint character, 0..1
 * @param strength scales how visible all of it is
 * @param scan whether the line of light passes down it
 */
@Composable
fun GlyphField(
    subject: GlyphSubject,
    modifier: Modifier = Modifier,
    cell: Dp = 8.dp,
    fps: Int = 10,
    noise: Float = .10f,
    strength: Float = 1f,
    scan: Boolean = true,
    ink: Color = Color.Unspecified,
    hot: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val face = remember { mono(context) }
    val bold = remember { ResourcesCompat.getFont(context, R.font.heros_bold) ?: Typeface.DEFAULT_BOLD }
    val still = LocalReducedMotion.current || animationsOff(context)
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still, fps) {
        if (still) return@LaunchedEffect
        val start = withFrameNanos { it }
        var last = 0L
        val gap = 1_000_000_000L / fps
        while (true) {
            val now = withFrameNanos { it }
            if (now - last >= gap) {
                last = now
                time = (now - start) / 1e9f
            }
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textAlign = Paint.Align.CENTER } }
    val chars = remember { GLYPHS.toCharArray() }
    val cache = remember { arrayOfNulls<Any>(2) }      // what the mask was made for, and the mask
    val dim = if (ink != Color.Unspecified) ink else Ink.Glyph
    val lit = if (hot != Color.Unspecified) hot else Ink.GlyphHot
    val light = Ink.light

    Canvas(modifier) {
        val d = density
        val wDp = (size.width / d).roundToInt()
        val hDp = (size.height / d).roundToInt()
        val c = cell.value
        if (wDp <= 0 || hDp <= 0) return@Canvas
        val key = Triple(if (subject is GlyphSubject.Picture) subject.bitmap else subject, wDp, hDp)
        if (cache[0] != key) {
            cache[0] = key
            cache[1] = maskOf(subject, wDp, hDp, c, bold)
        }
        val mask = cache[1] as Mask?
        val cols = ceil(wDp / c).toInt()
        val rows = ceil(hDp / c).toInt()
        val t = time
        val tick = (t * fps).toInt()
        val line = if (!scan || still) -9999f else (t * 22f) % (hDp + 160f) - 80f
        val shift = if (mask != null && mask.period > 0) floor((t * mask.speed) % mask.period).toInt() else 0
        val base = if (light) .10f else .06f
        val revealed = (subject as? GlyphSubject.Picture)?.revealed?.coerceIn(0f, 1f)
        paint.textSize = c * d
        val centre = -(paint.ascent() + paint.descent()) / 2f
        val dimArgb = dim.toArgb()
        val litArgb = lit.toArgb()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (y in 0 until rows) {
                val near = abs(y * c - line) < 24f
                for (x in 0 until cols) {
                    val sx = min((mask?.w ?: 1) - 1, (x * c + c / 2).toInt() + shift)
                    val sy = min((mask?.h ?: 1) - 1, (y * c + c / 2).toInt())
                    var a = mask?.at(sx, sy) ?: 0f
                    var argb = dimArgb
                    if (mask?.colour != null) {
                        // a photograph: its own colours above the line it has come out to, nothing below
                        if (a > .05f && revealed != null && y <= revealed * rows) {
                            val p = mask.colour[sy * mask.w + sx]
                            val luma = (0.2126f * (p shr 16 and 0xff) + 0.7152f * (p shr 8 and 0xff) + 0.0722f * (p and 0xff)) / 255f
                            argb = p or (0xff shl 24)
                            a = .3f + .7f * luma
                        } else a = 0f
                    }
                    if (a < .05f && chance(x, y, tick) > noise) continue
                    var alpha = if (a > .05f) .10f + a * .90f else base + chance(y, x, tick + 7) * .04f
                    if (near) alpha = min(1f, alpha + .3f)
                    if (mask?.colour == null) argb = if (a > .58f && near) litArgb else dimArgb
                    paint.color = argb
                    paint.alpha = (alpha * strength * (argb ushr 24) / 255f * 255f).roundToInt().coerceIn(0, 255)
                    val g = (chance(x * 31 + 5, y * 17 + 3, tick) * chars.size).toInt().coerceIn(0, chars.size - 1)
                    native.drawText(chars, g, 1, (x * c + c / 2) * d, (y * c + c / 2) * d + centre, paint)
                }
            }
        }
    }
}

/**
 * The same characters going away along the diagonal, to uncover what is under
 * them: a photograph that has just arrived. Lay it over the photograph with
 * the same size; [progress] runs 0..1.
 */
@Composable
fun GlyphVeil(progress: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val face = remember { mono(context) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textAlign = Paint.Align.CENTER } }
    val chars = remember { GLYPHS.toCharArray() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (now - last > 80_000_000L) {
                last = now
                tick++
            }
        }
    }
    val bg = Ink.Bg2
    val dim = Ink.Glyph
    val hot = Ink.GlyphHot
    Canvas(modifier) {
        if (progress >= 1f) return@Canvas
        val cell = max(6.dp.toPx(), (min(size.width, size.height) / 22f))
        val cols = ceil(size.width / cell).toInt()
        val rows = ceil(size.height / cell).toInt()
        val band = .28f
        paint.textSize = cell
        val centre = -(paint.ascent() + paint.descent()) / 2f
        drawIntoCanvas { canvas ->
            for (y in 0 until rows) for (x in 0 until cols) {
                val threshold = (x.toFloat() / cols + y.toFloat() / rows) / 2f * (1f - band)
                val lp = (progress - threshold) / band
                if (lp >= 1f) continue
                val keep = if (lp <= 0f) 1f else 1f - lp
                drawRect(bg.copy(alpha = keep), Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
                paint.color = (if (lp <= 0f) dim else hot).toArgb()
                paint.alpha = ((if (lp <= 0f) .42f else min(1f, keep + .25f)) * 255).roundToInt()
                val g = (chance(x, y, tick) * chars.size).toInt().coerceIn(0, chars.size - 1)
                canvas.nativeCanvas.drawText(chars, g, 1, x * cell + cell / 2, y * cell + cell / 2 + centre, paint)
            }
        }
    }
}

private const val LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&$*+=/<>"
// the words of a darkroom: a Chinese title settles out of these, so its letters keep their width
private const val HAN = "胶片显影定影冲印曝光潜影银盐相纸底片暗房光圈快门颗粒"

private fun stray(c: Char, seed: Float): Char = when {
    c == ' ' -> ' '
    c.code > 0x2E80 -> HAN[(seed * HAN.length).toInt().coerceIn(0, HAN.length - 1)]
    else -> LATIN[(seed * LATIN.length).toInt().coerceIn(0, LATIN.length - 1)]
}

/**
 * Text that settles out of noise: every character starts as some other one,
 * all of them changing, and they come to rest one by one from the left. It
 * does this when it first appears and again whenever [text] or [trigger]
 * changes -- an answer to something having changed, never a loop. Each
 * character keeps the width it will have, so nothing around it moves.
 *
 * @param stagger milliseconds between one character coming to rest and the next
 * @param delay milliseconds before the first one does
 * @param hot the colour of a character that has not come to rest yet
 */
@Composable
fun ScrambleText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    stagger: Int = 50,
    delay: Int = 180,
    hot: Color = Color.Unspecified,
    trigger: Any? = null,
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val drawn = if (color != Color.Unspecified) style.copy(color = color) else style
    val slots = remember(text, drawn) { text.map { measurer.measure(it.toString(), drawn) } }
    var elapsed by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    LaunchedEffect(text, trigger) {
        if (reduced || animationsOff(context) || text.isBlank()) {
            elapsed = Float.MAX_VALUE
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        val total = delay + stagger * text.length
        elapsed = 0f
        while (elapsed < total) elapsed = (withFrameNanos { it } - start) / 1e6f
        elapsed = Float.MAX_VALUE
    }
    val width = slots.sumOf { it.size.width }
    val height = slots.maxOfOrNull { it.size.height } ?: 0
    val unsettled = if (hot != Color.Unspecified) drawn.copy(color = hot) else drawn
    Canvas(
        modifier
            .semantics { contentDescription = text }
            .layout { measurable, _ ->
                val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
                layout(width, height) { p.place(0, 0) }
            },
    ) {
        var x = 0f
        val roll = floor(elapsed / 42f).toInt()
        for ((i, slot) in slots.withIndex()) {
            val c = text[i]
            val settled = elapsed >= delay + i * stagger || c == ' '
            val result: TextLayoutResult =
                if (settled) slot else measurer.measure(stray(c, chance(i, 11, roll)).toString(), unsettled)
            drawText(result, topLeft = Offset(x + (slot.size.width - result.size.width) / 2f, 0f))
            x += slot.size.width
        }
    }
}
