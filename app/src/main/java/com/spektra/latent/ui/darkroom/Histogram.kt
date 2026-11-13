package com.spektra.latent.ui.darkroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class HistogramData(val bins: Array<IntArray>, val high: Float, val low: Float)

/** Counts the displayed encoded RGB pixels, restricted to the engine's real picture gate. */
internal fun measureHistogram(pixels: IntArray, width: Int, height: Int, gate: FloatArray): HistogramData {
    val bins = Array(3) { IntArray(256) }
    var n = 0
    var high = 0
    var low = 0
    fun inside(x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0..3) {
            val j = (i + 1) % 4
            val cross = (gate[j * 2] - gate[i * 2]) * (y - gate[i * 2 + 1]) - (gate[j * 2 + 1] - gate[i * 2 + 1]) * (x - gate[i * 2])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0 && sign != 0 && s != sign) return false
            if (s != 0) sign = s
        }
        return true
    }
    val stride = max(1, sqrt(width.toDouble() * height / 180000).toInt())
    for (y in 0 until height step stride) for (x in 0 until width step stride) {
        if (!inside((x + .5f) / width, (y + .5f) / height)) continue
        val p = pixels[y * width + x]
        val r = p ushr 16 and 255
        val g = p ushr 8 and 255
        val b = p and 255
        bins[0][r]++; bins[1][g]++; bins[2][b]++; n++
        if (min(r, min(g, b)) >= 250) high++
        if (max(r, max(g, b)) <= 5) low++
    }
    return HistogramData(bins, high.toFloat() / max(n, 1), low.toFloat() / max(n, 1))
}

/** From one per cent of the picture on, a clipped end is marked in amber. */
private const val CLIP_WARN = .01f

private val TRACES = listOf(Color(255, 92, 80), Color(96, 214, 138), Color(92, 160, 255))

/** Two passes of a five-bin average: an 8-bit proof pushed hard leaves a comb of empty bins. */
private fun soften(src: FloatArray): FloatArray = FloatArray(256) { k ->
    var sum = 0f
    var n = 0
    for (j in max(0, k - 2)..min(255, k + 2)) {
        sum += src[j]
        n++
    }
    sum / n
}

/**
 * The histogram: an instrument laid over the foot of the picture, see-through.
 *
 * It measures what the frame is showing -- the proof, or the original while
 * the photograph is held. It is fixed to the bottom edge of the picture (the
 * picture, not the film around it: [gate] is where the picture is inside
 * [image]) and never moves from there. A date printed in a bottom corner
 * ([dateBounds]) prints over it: the histogram is cut away where the date is
 * and its row of marks steps aside.
 *
 * It is dark in both themes, because what it lies on is the photograph and
 * not the page, and nothing behind it is blurred: the picture under the traces
 * stays the picture.
 */
@Composable
internal fun Histogram(image: ImageBitmap, original: Boolean, gate: FloatArray, dateBounds: FloatArray, modifier: Modifier = Modifier, adjustedSource: Boolean = false) {
    var data by remember { mutableStateOf<HistogramData?>(null) }
    LaunchedEffect(image, gate) {
        data = withContext(Dispatchers.Default) {
            val bitmap = image.asAndroidBitmap()
            // Copying also normalises a float/wide-colour proof to displayed sRGB bytes.
            val srgb = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(srgb).drawBitmap(bitmap, 0f, 0f, null)
            try {
                val p = IntArray(srgb.width * srgb.height)
                srgb.getPixels(p, 0, srgb.width, 0, 0, srgb.width, srgb.height)
                measureHistogram(p, srgb.width, srgb.height, gate)
            } finally {
                srgb.recycle()
            }
        }
    }
    val d = data ?: return
    val xs = gate.filterIndexed { i, _ -> i % 2 == 0 }
    val ys = gate.filterIndexed { i, _ -> i % 2 == 1 }
    val left = xs.min().coerceIn(0f, 1f)
    val right = xs.max().coerceIn(0f, 1f)
    val top = ys.min().coerceIn(0f, 1f)
    val bottom = ys.max().coerceIn(0f, 1f)
    fun pc(v: Float) = String.format(Locale.US, "%.1f%%", v * 100)

    BoxWithConstraints(modifier) {
        val pictureW = maxWidth
        val pictureH = maxHeight
        val w = pictureW * (right - left)
        val gateH = pictureH * (bottom - top)
        // A short panorama has no room for readable traces and labels.
        // Keep the instrument within the photograph, including on a phone in landscape.
        if (gateH < 104.dp || w < 140.dp) return@BoxWithConstraints
        val h = (gateH * .32f).coerceIn(60.dp, 112.dp).coerceAtMost(gateH)
        val yTop = pictureH * bottom - h
        // a date stamped where the histogram lies
        val stamped = dateBounds.size == 4 && pictureH * dateBounds[3] > yTop && pictureH * dateBounds[1] < pictureH * bottom
        val narrow = w < 214.dp
        val tight = w < 168.dp
        val ink = Ink.OnPhoto
        val shade = Color(8, 8, 10)
        val caption = Type.numMicro.copy(
            color = ink.copy(alpha = .64f), letterSpacing = .08.em, lineHeight = 11.sp,
            shadow = Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 1f), 3f),
        )
        Column(
            Modifier
                .offset(x = pictureW * left, y = yTop)
                .width(w)
                .height(h)
                .semantics { contentDescription = "RGB 直方图。高光溢出 ${pc(d.high)}，暗部死黑 ${pc(d.low)}" }
                .drawWithContent {
                    val paint: () -> Unit = {
                        drawRect(
                            Brush.verticalGradient(
                                0f to shade.copy(alpha = 0f), .24f to shade.copy(alpha = .4f), .76f to shade.copy(alpha = .54f), 1f to shade.copy(alpha = .76f),
                            ),
                        )
                        drawContent()
                    }
                    if (stamped) {
                        val pad = 3.dp.toPx()
                        val hole = Rect(
                            (dateBounds[0] - left) * pictureW.toPx() - pad, dateBounds[1] * pictureH.toPx() - yTop.toPx() - pad,
                            (dateBounds[2] - left) * pictureW.toPx() + pad, dateBounds[3] * pictureH.toPx() - yTop.toPx() + pad,
                        )
                        val around = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            addRect(hole)
                        }
                        clipPath(around) { paint() }
                    } else paint()
                }
                .padding(top = 10.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 9.dp, end = 9.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!narrow) Txt("RGB · ${if (original) "原图" else if (adjustedSource) "调节" else "样张"} 8 位", caption, Modifier.weight(1f))
                else Box(Modifier.weight(1f))
                val reading = caption.copy(color = ink.copy(alpha = .94f), fontWeight = FontWeight.Medium)
                Txt(if (tight) "高 " else "高光 ", caption.copy(fontFamily = null))
                Txt(pc(d.high), reading, color = if (d.high >= CLIP_WARN) Ink.Amber else Color.Unspecified)
                Gap(width = if (tight) 7.dp else 10.dp)
                Txt(if (tight) "暗 " else "暗部 ", caption.copy(fontFamily = null))
                Txt(pc(d.low), reading, color = if (d.low >= CLIP_WARN) Ink.Amber else Color.Unspecified)
            }
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val cw = size.width
                val ch = size.height
                // quarter lines and the floor
                for (i in 1..3) drawRect(Color.White.copy(alpha = .1f), Offset((cw * i / 4f).toInt().toFloat(), 0f), Size(1.dp.toPx() / 2f, ch))
                drawRect(Color.White.copy(alpha = .3f), Offset(0f, ch - 1.dp.toPx() / 2f), Size(cw, 1.dp.toPx() / 2f))
                // each channel smoothed, then scaled to the tallest bin short of the two ends, so that a
                // clipped spike at 0 or 255 does not flatten everything between them
                val curves = d.bins.map { row -> soften(soften(FloatArray(256) { row[it].toFloat() })) }
                var peak = 1f
                for (c in curves) for (k in 5..250) if (c[k] > peak) peak = c[k]
                fun px(k: Int) = k / 255f * cw
                fun py(v: Float) = ch - 1f - min(1f, v / peak) * (ch - 3f)
                // red, green and blue add up where they overlap, so neutral tones read as white
                curves.forEachIndexed { n, c ->
                    val area = Path().apply {
                        moveTo(0f, ch)
                        for (k in 0..255) lineTo(px(k), py(c[k]))
                        lineTo(cw, ch)
                        close()
                    }
                    drawPath(area, TRACES[n].copy(alpha = .2f), blendMode = BlendMode.Plus)
                }
                // (where a channel is empty its trace lies on the floor, and three of them there would add up to a
                // heavy white line under the picture: the traces are cut off just above it)
                clipRect(bottom = ch - 1.5.dp.toPx()) {
                    curves.forEachIndexed { n, c ->
                        val trace = Path().apply { for (k in 0..255) if (k == 0) moveTo(px(k), py(c[k])) else lineTo(px(k), py(c[k])) }
                        drawPath(trace, TRACES[n].copy(alpha = .95f), style = Stroke(1.2.dp.toPx(), join = StrokeJoin.Round), blendMode = BlendMode.Plus)
                    }
                }
                // a clipped end lights up on its own side
                if (d.low >= CLIP_WARN) drawRect(Ink.Amber, Offset.Zero, Size(2.dp.toPx(), ch))
                if (d.high >= CLIP_WARN) drawRect(Ink.Amber, Offset(cw - 2.dp.toPx(), 0f), Size(2.dp.toPx(), ch))
            }
            if (!stamped) {
                val mark = Type.numMicro.copy(color = ink.copy(alpha = .56f), lineHeight = 11.sp)
                Box(Modifier.fillMaxWidth().height(17.dp)) {
                    Txt("0", mark, Modifier.align(Alignment.CenterStart).padding(start = 9.dp))
                    if (!narrow) Txt("64", mark, Modifier.align(Alignment.Center).offset(x = -w / 4))
                    Txt("128", mark, Modifier.align(Alignment.Center))
                    if (!narrow) Txt("192", mark, Modifier.align(Alignment.Center).offset(x = w / 4))
                    Txt("255", mark, Modifier.align(Alignment.CenterEnd).padding(end = 9.dp))
                }
            }
        }
    }
}
