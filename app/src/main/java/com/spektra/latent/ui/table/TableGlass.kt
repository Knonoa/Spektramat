package com.spektra.latent.ui.table

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The loupes as glass in a saved picture: the trace of LENS_SHADER, step for
 * step, on the CPU. A saved picture is drawn without the GPU, so the shader
 * cannot bend it; this does, with the same numbers.
 */

private fun refract(i: FloatArray, n: FloatArray, eta: Float, out: FloatArray): Boolean {
    val d = n[0] * i[0] + n[1] * i[1] + n[2] * i[2]
    val k = 1f - eta * eta * (1f - d * d)
    if (k < 0f) return false
    val f = eta * d + sqrt(k)
    out[0] = eta * i[0] - f * n[0]
    out[1] = eta * i[1] - f * n[1]
    out[2] = eta * i[2] - f * n[2]
    return true
}

private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun unit(x: Float, y: Float, z: Float): FloatArray {
    val m = sqrt(x * x + y * y + z * z)
    return floatArrayOf(x / m, y / m, z / m)
}

/**
 * Bends [bitmap] under each lens. [lenses]: centre x, centre y, radius and
 * power, in pixels of the bitmap. Every lens looks at the picture as it was
 * before any lens touched it. [room]: what the glass reflects.
 */
fun glassLoupes(bitmap: Bitmap, lenses: List<FloatArray>, room: Color) {
    class Box(val x0: Int, val y0: Int, val w: Int, val h: Int, val src: IntArray)

    val boxes = lenses.map { l ->
        val x0 = (l[0] - l[2] - 2).toInt().coerceIn(0, bitmap.width - 1)
        val y0 = (l[1] - l[2] - 2).toInt().coerceIn(0, bitmap.height - 1)
        val x1 = (l[0] + l[2] + 3).toInt().coerceIn(x0 + 1, bitmap.width)
        val y1 = (l[1] + l[2] + 3).toInt().coerceIn(y0 + 1, bitmap.height)
        Box(x0, y0, x1 - x0, y1 - y0, IntArray((x1 - x0) * (y1 - y0)).also { bitmap.getPixels(it, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0) })
    }
    val roomRgb = floatArrayOf(room.red * 255f, room.green * 255f, room.blue * 255f)
    val down = floatArrayOf(0f, 0f, -1f)
    val up = floatArrayOf(0f, 0f, 1f)
    val n = FloatArray(3)
    val t1 = FloatArray(3)
    val t2 = FloatArray(3)
    val hit = FloatArray(2)
    val key = unit(-0.42f, -0.5f, 0.76f)
    val fill = unit(0.5f, 0.55f, 0.67f)
    val etas = floatArrayOf(LENS_ETA + LENS_RED, LENS_ETA, LENS_ETA + LENS_BLUE)   // red, green, blue

    lenses.forEachIndexed { index, l ->
        val box = boxes[index]
        val out = box.src.copyOf()
        val a = l[2]
        val bigR = a * LENS_CURVE
        val zc = sqrt(bigR * bigR - a * a)
        val m = max(l[3], 1.0001f)
        val h = max(0f, bigR * (1f - 1f / m) / (LENS_ETA - 1f) - (bigR - zc) / LENS_ETA)

        // one channel (0 red, 1 green, 2 blue) of the picture at a point, 0..255; past the box, the colour of the room
        fun board(px: Float, py: Float, channel: Int): Float {
            val fx = px - box.x0 - 0.5f
            val fy = py - box.y0 - 0.5f
            if (fx < 0f || fy < 0f || fx > box.w - 1f || fy > box.h - 1f) return roomRgb[channel]
            val ix = fx.toInt().coerceIn(0, max(box.w - 2, 0))
            val iy = fy.toInt().coerceIn(0, max(box.h - 2, 0))
            val tx = fx - ix
            val ty = fy - iy
            val shift = 16 - 8 * channel
            fun c(x: Int, y: Int) = (box.src[min(y, box.h - 1) * box.w + min(x, box.w - 1)] shr shift and 0xff).toFloat()
            return (c(ix, iy) * (1 - tx) + c(ix + 1, iy) * tx) * (1 - ty) + (c(ix, iy + 1) * (1 - tx) + c(ix + 1, iy + 1) * tx) * ty
        }

        // where the ray entering at (dx, dy) from the centre meets the table, into `hit`; false when it never leaves the glass
        fun trace(dx: Float, dy: Float, eta: Float): Boolean {
            val zs = sqrt(max(bigR * bigR - dx * dx - dy * dy, 0f)) - zc
            val len = sqrt(dx * dx + dy * dy + (zs + zc) * (zs + zc))
            n[0] = dx / len
            n[1] = dy / len
            n[2] = (zs + zc) / len
            if (!refract(down, n, 1f / eta, t1)) return false
            val k1 = zs / max(-t1[2], 1e-4f)
            val qx = dx + t1[0] * k1
            val qy = dy + t1[1] * k1
            if (!refract(t1, up, eta, t2)) return false
            val k2 = h / max(-t2[2], 1e-4f)
            hit[0] = qx + t2[0] * k2
            hit[1] = qy + t2[1] * k2
            return true
        }

        val c = FloatArray(3)
        for (y in 0 until box.h) for (x in 0 until box.w) {
            val dx = box.x0 + x + 0.5f - l[0]
            val dy = box.y0 + y + 0.5f - l[1]
            val r = hypot(dx, dy)
            if (r > a + 1f) continue
            for (ch in 0..2) c[ch] = if (trace(dx, dy, etas[ch])) board(l[0] + hit[0], l[1] + hit[1], ch) else 0f
            val zs = sqrt(max(bigR * bigR - r * r, 0f)) - zc
            val len = hypot(r, zs + zc)
            val nx = dx / len
            val ny = dy / len
            val nz = (zs + zc) / len
            val fresnel = 0.04f + 0.96f * (1f - nz.coerceIn(0f, 1f)).pow(5)
            // the ray of the eye mirrored in the glass: where the lights of the room show
            val bx = 2f * nz * nx
            val by = 2f * nz * ny
            val bz = -1f + 2f * nz * nz
            val shine = (max(bx * key[0] + by * key[1] + bz * key[2], 0f).pow(90) * 1.4f +
                max(bx * fill[0] + by * fill[1] + bz * fill[2], 0f).pow(30) * 0.25f) * 255f
            val rim = smooth(a - 2.5f, a - 0.5f, r) * 0.7f
            val cover = 1f - smooth(a - 0.6f, a + 0.6f, r)
            val old = box.src[y * box.w + x]
            var packed = 0xff shl 24
            for (ch in 0..2) {
                var v = c[ch] * 0.965f
                v += (roomRgb[ch] - v) * fresnel + shine
                v += (roomRgb[ch] * 0.55f + 0.25f * 255f - v) * rim
                val before = (old shr (16 - 8 * ch) and 0xff).toFloat()
                packed = packed or ((before + (v - before) * cover).toInt().coerceIn(0, 255) shl (16 - 8 * ch))
            }
            out[y * box.w + x] = packed
        }
        bitmap.setPixels(out, 0, box.w, box.x0, box.y0, box.w, box.h)
    }
}
