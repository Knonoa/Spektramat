package com.spektra.latent.data

import android.content.Context
import android.graphics.ColorSpace
import com.spektra.latent.engine.PhotoModel
import kotlin.math.abs
import kotlin.math.pow

/** Output encoding helpers. They never alter the engine-owned floating-point buffer. */
internal object PrintEncoding {
    fun profile(context: Context, model: PhotoModel, linear: Boolean = !model.outputCctfEncoding): ByteArray {
        val key = when (model.outputColorSpace) {
            "Display P3" -> "p3"
            "Adobe RGB (1998)" -> "adobe"
            "ProPhoto RGB" -> "prophoto"
            else -> "srgb"
        }
        return context.assets.open("icc/$key-${if (linear) "linear" else "encoded"}.icc").use { it.readBytes() }
    }

    /** Extended, signed inverse transfer: no connector or range clamp may precede DNG storage. */
    fun linear(value: Float, model: PhotoModel): Float {
        if (!model.outputCctfEncoding) return value
        val v = value.toDouble()
        val decoded = when (model.outputColorSpace) {
            "Adobe RGB (1998)" -> kotlin.math.sign(v) * abs(v).pow(563.0 / 256.0)
            "ProPhoto RGB" -> if (v <= .03125) v / 16.0 else v.pow(1.8)
            else -> if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        }
        return decoded.toFloat()
    }

    /** Normalized transfer value used to compare a linear highlight threshold to encoded pixels. */
    fun encoded(value: Float, model: PhotoModel): Float = when (model.outputColorSpace) {
        "Adobe RGB (1998)" -> value.pow(256f / 563f)
        "ProPhoto RGB" -> if (value <= .001953125f) value * 16f else value.pow(1f / 1.8f)
        else -> if (value <= .0031308f) 12.92f * value else 1.055f * value.pow(1f / 2.4f) - .055f
    }

    /** Row-major linear RGB to D50 XYZ, adapted without passing samples through a clamping connector. */
    fun xyzD50(model: PhotoModel): DoubleArray {
        val source = ColorSpace.get(when (model.outputColorSpace) {
            "Display P3" -> ColorSpace.Named.DISPLAY_P3
            "Adobe RGB (1998)" -> ColorSpace.Named.ADOBE_RGB
            "ProPhoto RGB" -> ColorSpace.Named.PRO_PHOTO_RGB
            else -> ColorSpace.Named.SRGB
        }) as ColorSpace.Rgb
        val adapted = ColorSpace.adapt(source, floatArrayOf(.34567f, .35850f)) as ColorSpace.Rgb
        val matrix = adapted.transform
        return DoubleArray(9) { index -> matrix[index / 3 + 3 * (index % 3)].toDouble() }
    }

    fun inverse(m: DoubleArray): DoubleArray {
        val a=m[0]; val b=m[1]; val c=m[2]; val d=m[3]; val e=m[4]; val f=m[5]
        val g=m[6]; val h=m[7]; val i=m[8]
        val determinant = a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g)
        require(abs(determinant) > 1e-12) { "Invalid output color matrix" }
        return doubleArrayOf(e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d)
            .map { it / determinant }.toDoubleArray()
    }
}
