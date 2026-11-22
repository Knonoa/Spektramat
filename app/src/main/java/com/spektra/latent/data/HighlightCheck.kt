package com.spektra.latent.data

import android.graphics.Bitmap
import com.spektra.latent.engine.PhotoPixels
import com.spektra.latent.engine.PhotoPreviewLut
import kotlin.math.roundToInt

internal const val HIGHLIGHT_PREF = "highlightWarning"

/** Counts photo pixels, excluding the rebate and the date imprint. A single RGB channel can clip. */
data class HighlightReport(val total: Int, val near: Int, val over: Int) {
    fun percent(count: Int): String {
        if (count == 0 || total == 0) return "0%"
        val tenths = (1000.0 * count / total).roundToInt()
        return if (tenths == 0) "<0.1%" else "${tenths / 10}.${tenths % 10}%"
    }

    fun label(format: PrintFormat): String = if (format == PrintFormat.DNG)
        "接近白位 ${percent(near)} · 超白 ${percent(over)}（浮点保留）"
    else "接近白位 ${percent(near)} · 超出 ${format.label} 上限 ${percent(over)}"
}

internal data class HighlightMask(val report: HighlightReport, val overlay: Bitmap?)

internal object HighlightCheck {
    private const val NEAR = 0.95f // normalized linear light, independent of the selected transfer curve
    private const val OVER = 1f
    private const val AMBER = 0xA0FFB42E.toInt()
    private const val RED = 0xB0EF3254.toInt()

    /** Read the finite float values before JPEG/PNG quantization or the screen's range limit. */
    fun inspect(pixels: PhotoPixels, format: PrintFormat, gate: FloatArray, dates: FloatArray,
                drawMask: Boolean, check: () -> Unit = {}): HighlightMask {
        val w = pixels.width
        val h = pixels.height
        val source = pixels.pixels().asFloatBuffer()
        val model = pixels.model
        val jpegMatrix = if (format == PrintFormat.JPEG && model.outputColorSpace != "sRGB")
            PhotoPreviewLut.displayMatrix(model.outputColorSpace) else null
        val alpha = if (drawMask) IntArray(w * h) else null
        val area = if (gate.size > 4) floatArrayOf(
            gate.indices.filter { it % 2 == 0 }.minOf { gate[it] }, gate.indices.filter { it % 2 != 0 }.minOf { gate[it] },
            gate.indices.filter { it % 2 == 0 }.maxOf { gate[it] }, gate.indices.filter { it % 2 != 0 }.maxOf { gate[it] },
        ) else gate
        var total = 0
        var near = 0
        var over = 0
        val threshold = if (jpegMatrix == null && model.outputCctfEncoding) PrintEncoding.encoded(NEAR, model) else NEAR
        for (y in 0 until h) {
            if (y % 32 == 0) check()
            val py = (y + .5f) / h
            for (x in 0 until w) {
                val px = (x + .5f) / w
                if (!inside(area, 0, px, py) || inDates(dates, px, py)) continue
                val i = y * w + x
                val at = i * 3
                val r = source[at]; val g = source[at + 1]; val b = source[at + 2]
                if (!r.isFinite() || !g.isFinite() || !b.isFinite()) continue
                val peak = if (jpegMatrix == null) maxOf(r, g, b) else {
                    val rl = PrintEncoding.linear(r, model); val gl = PrintEncoding.linear(g, model); val bl = PrintEncoding.linear(b, model)
                    maxOf(rgbRow(jpegMatrix, 0, rl, gl, bl), rgbRow(jpegMatrix, 3, rl, gl, bl), rgbRow(jpegMatrix, 6, rl, gl, bl))
                }
                total++
                if (peak > OVER) {
                    over++
                    if (alpha != null && (x + y) % 10 < 5) alpha[i] = if (format == PrintFormat.DNG) AMBER else RED
                } else if (peak >= threshold) {
                    near++
                    if (alpha != null && (x + y) % 10 < 5) alpha[i] = AMBER
                }
            }
        }
        return HighlightMask(HighlightReport(total, near, over),
            if (alpha != null && (near > 0 || over > 0)) Bitmap.createBitmap(alpha, w, h, Bitmap.Config.ARGB_8888) else null)
    }

    private fun rgbRow(m: FloatArray, i: Int, r: Float, g: Float, b: Float) = m[i] * r + m[i + 1] * g + m[i + 2] * b
    private fun inDates(bounds: FloatArray, x: Float, y: Float): Boolean {
        for (i in 0 until bounds.size / 4) if (inside(bounds, i * 4, x, y)) return true
        return false
    }
    private fun inside(bounds: FloatArray, i: Int, x: Float, y: Float) =
        bounds.size < i + 4 || (x >= bounds[i] && x < bounds[i + 2] && y >= bounds[i + 1] && y < bounds[i + 3])
}
