package com.spektra.latent.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finishing: what is done to the print itself -- tone curve, colour mixer,
 * colour grading, vignette. The arithmetic is the last section of
 * latent.glsl, run by the GPU for the preview and by the CPU for thumbnails
 * and prints. This file turns a [Look] into the two things that code takes:
 * the `Finish` block (19 floats) and the finishing table (two rows of 256 RGB
 * samples: the tone curve, and the colour mixer over hue).
 */
object Finishing {
    const val BLOCK_FLOATS = 19
    const val SAMPLES = 256
    const val TABLE_FLOATS = SAMPLES * 2 * 3

    /** A curve that changes nothing: the straight line from black to white. Points are x0, y0, x1, y1, ... */
    val IDENTITY: List<Float> = listOf(0f, 0f, 1f, 1f)

    /** The colour mixer's eight hues, in the order of [Look.mixer]: three numbers each (hue, saturation, lightness). */
    val HUE_NAMES = listOf("红", "橙", "黄", "绿", "青", "蓝", "紫", "品红")

    /** The eight hues as sRGB colours (0..1), for swatches and for finding where each sits. */
    val HUE_COLOURS = listOf(
        floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0.5f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 1f, 1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0.5f, 0f, 1f), floatArrayOf(1f, 0f, 1f),
    )
    val NO_MIXER: List<Float> = List(24) { 0f }

    /** Colour grading: shadows, midtones, highlights, each (hue 0..360, saturation 0..100, lightness -100..100). */
    val RANGE_NAMES = listOf("阴影", "中间调", "高光")
    val NO_GRADING: List<Float> = List(9) { 0f }

    /** The most the mixer turns a hue, in turns (30 degrees). */
    private const val HUE_REACH = 30f / 360f
    /** The strongest tint colour grading adds, and the most lightness, in sRGB-encoded units. */
    private const val TINT_REACH = 0.3f
    private const val LIFT_REACH = 0.2f
    /** Stops at the corners at full vignette, and where it begins. */
    private const val VIGNETTE_STOPS = 2f
    private const val VIGNETTE_MID = 0.25f

    fun curveChanged(look: Look) =
        look.curve != IDENTITY || look.curveR != IDENTITY || look.curveG != IDENTITY || look.curveB != IDENTITY

    fun mixerChanged(look: Look) = look.mixer.any { it != 0f }

    fun gradingChanged(look: Look): Boolean {
        val g = look.grading
        return (0 until 3).any { g[it * 3 + 1] != 0f || g[it * 3 + 2] != 0f }
    }

    /** The `Finish` block of latent.glsl. */
    fun block(look: Look): FloatArray {
        val o = FloatArray(BLOCK_FLOATS)
        o[0] = if (curveChanged(look)) 1f else 0f
        o[1] = if (mixerChanged(look)) 1f else 0f
        o[2] = if (gradingChanged(look)) 1f else 0f
        val g = look.grading
        for (range in 0 until 3) {
            val tint = tint(g[range * 3], g[range * 3 + 1] / 100f * TINT_REACH)
            tint.copyInto(o, 3 + range * 3)
            o[12 + range] = g[range * 3 + 2] / 100f * LIFT_REACH
        }
        o[15] = look.gradingBalance / 100f
        o[16] = look.gradingBlending / 100f
        o[17] = look.vignette / 100f * VIGNETTE_STOPS
        o[18] = VIGNETTE_MID
        return o
    }

    /** A hue (degrees) as a colour difference of size [amount] that carries no luminance: added to a grey it tints it. */
    fun tint(hueDegrees: Float, amount: Float): FloatArray {
        val c = hueColour(hueDegrees)
        val y = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
        return floatArrayOf((c[0] - y) * amount, (c[1] - y) * amount, (c[2] - y) * amount)
    }

    /** The fully saturated colour of a hue on the usual colour wheel (red at 0, green at 120, blue at 240). */
    fun hueColour(hueDegrees: Float): FloatArray {
        val h = ((hueDegrees % 360f) + 360f) % 360f / 60f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        return when (h.toInt()) {
            0 -> floatArrayOf(1f, x, 0f)
            1 -> floatArrayOf(x, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f)
            4 -> floatArrayOf(x, 0f, 1f)
            else -> floatArrayOf(1f, 0f, x)
        }
    }

    // ---- the table

    private class Key(val curve: List<Float>, val r: List<Float>, val g: List<Float>, val b: List<Float>, val mixer: List<Float>) {
        fun matches(l: Look) = curve == l.curve && r == l.curveR && g == l.curveG && b == l.curveB && mixer == l.mixer
    }

    @Volatile
    private var last: Pair<Key, FloatArray>? = null

    /**
     * The finishing table for [look]. The same array is returned while the
     * curves and the mixer stay as they are (a ruler elsewhere moves sixty
     * times a second); do not write to it.
     */
    fun table(look: Look): FloatArray {
        last?.let { (key, table) -> if (key.matches(look)) return table }
        val t = FloatArray(TABLE_FLOATS)
        val master = sample(look.curve, SAMPLES)
        val channels = arrayOf(sample(look.curveR, SAMPLES), sample(look.curveG, SAMPLES), sample(look.curveB, SAMPLES))
        for (i in 0 until SAMPLES) {
            for (c in 0 until 3) t[i * 3 + c] = at(channels[c], master[i])
        }
        val row = SAMPLES * 3
        val m = look.mixer
        for (i in 0 until SAMPLES) {
            val hue = i / SAMPLES.toFloat()
            // between the two hues it lies between, shared by a raised cosine: their shares add up to one
            var next = ORDER.indexOfFirst { CENTRES[it] > hue }
            if (next < 0) next = 0
            val a = ORDER[(next + ORDER.size - 1) % ORDER.size]
            val b = ORDER[next]
            var span = CENTRES[b] - CENTRES[a]
            if (span <= 0f) span += 1f
            var along = hue - CENTRES[a]
            if (along < 0f) along += 1f
            val wb = 0.5f - 0.5f * cos(PI.toFloat() * (along / span).coerceIn(0f, 1f))
            val wa = 1f - wb
            t[row + i * 3] = (m[a * 3] * wa + m[b * 3] * wb) / 100f * HUE_REACH
            t[row + i * 3 + 1] = (m[a * 3 + 1] * wa + m[b * 3 + 1] * wb) / 100f
            t[row + i * 3 + 2] = (m[a * 3 + 2] * wa + m[b * 3 + 2] * wb) / 100f
        }
        last = Key(look.curve, look.curveR, look.curveG, look.curveB, look.mixer) to t
        return t
    }

    /** Where each of the eight hues sits on Oklab's hue circle, in turns; and the hues in the order they come round it. */
    private val CENTRES = FloatArray(8) { oklabHue(HUE_COLOURS[it]) }
    private val ORDER = (0 until 8).sortedBy { CENTRES[it] }

    private fun oklabHue(srgb: FloatArray): Float {
        fun linear(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow((v + 0.055) / 1.055, 2.4).toFloat()
        val r = linear(srgb[0])
        val g = linear(srgb[1])
        val b = linear(srgb[2])
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        val turns = (atan2(bb, a) / (2f * PI.toFloat()))
        return turns - kotlin.math.floor(turns)
    }

    // ---- curves

    /**
     * A curve through [points] (x0, y0, x1, y1, ... with x ascending) at [n]
     * evenly spaced inputs from 0 to 1. Between points it is a cubic that never
     * turns back on itself where the points do not (Fritsch-Carlson), so a
     * gentle S stays an S; outside the first and last point it is level.
     */
    fun sample(points: List<Float>, n: Int): FloatArray {
        val count = points.size / 2
        if (count < 2) return FloatArray(n) { it / (n - 1f) }
        val xs = FloatArray(count) { points[it * 2] }
        val ys = FloatArray(count) { points[it * 2 + 1] }
        val slope = FloatArray(count - 1) { (ys[it + 1] - ys[it]) / max(xs[it + 1] - xs[it], 1e-6f) }
        val tangent = FloatArray(count)
        tangent[0] = slope[0]
        tangent[count - 1] = slope[count - 2]
        for (i in 1 until count - 1) tangent[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
        for (i in 0 until count - 1) {
            if (slope[i] == 0f) {
                tangent[i] = 0f
                tangent[i + 1] = 0f
            } else {
                val a = tangent[i] / slope[i]
                val b = tangent[i + 1] / slope[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val k = 3f / sqrt(s)
                    tangent[i] = k * a * slope[i]
                    tangent[i + 1] = k * b * slope[i]
                }
            }
        }
        val out = FloatArray(n)
        var seg = 0
        for (j in 0 until n) {
            val x = j / (n - 1f)
            out[j] = when {
                x <= xs[0] -> ys[0]
                x >= xs[count - 1] -> ys[count - 1]
                else -> {
                    while (seg < count - 2 && x > xs[seg + 1]) seg++
                    val h = xs[seg + 1] - xs[seg]
                    val u = (x - xs[seg]) / h
                    val u2 = u * u
                    val u3 = u2 * u
                    (2f * u3 - 3f * u2 + 1f) * ys[seg] + (u3 - 2f * u2 + u) * h * tangent[seg] +
                        (-2f * u3 + 3f * u2) * ys[seg + 1] + (u3 - u2) * h * tangent[seg + 1]
                }
            }.coerceIn(0f, 1f)
        }
        return out
    }

    /** A sampled curve at [x] in 0..1, linear between samples. */
    private fun at(samples: FloatArray, x: Float): Float {
        val p = x.coerceIn(0f, 1f) * (samples.size - 1)
        val i = min(p.toInt(), samples.size - 2)
        return samples[i] + (samples[i + 1] - samples[i]) * (p - i)
    }

    /** A list of numbers from saved JSON, or [fallback] when it is not [size] finite numbers within [lo]..[hi]. */
    fun numbers(json: org.json.JSONArray?, size: Int, lo: Float, hi: Float, fallback: List<Float>): List<Float> {
        if (json == null || json.length() != size) return fallback
        val out = ArrayList<Float>(size)
        for (i in 0 until size) {
            val v = json.optDouble(i, Double.NaN).toFloat()
            if (!v.isFinite()) return fallback
            out += v.coerceIn(lo, hi)
        }
        return out
    }

    /** Curve points from saved JSON: at least two, inside the unit square, x strictly ascending; else the straight line. */
    fun points(json: org.json.JSONArray?): List<Float> {
        if (json == null || json.length() < 4 || json.length() % 2 != 0 || json.length() > 32) return IDENTITY
        val out = ArrayList<Float>(json.length())
        var previous = -1f
        for (i in 0 until json.length() step 2) {
            val x = json.optDouble(i, Double.NaN).toFloat()
            val y = json.optDouble(i + 1, Double.NaN).toFloat()
            if (!x.isFinite() || !y.isFinite() || x < 0f || x > 1f || x <= previous) return IDENTITY
            out += x
            out += y.coerceIn(0f, 1f)
            previous = x
        }
        return out
    }
}
