package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import com.spektra.latent.engine.PhotoModel
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lossless float TIFF for wide-gamut/linear prints; samples keep their output
 * encoding and may be negative or above white. The ICC identifies both. */
internal object FloatTiff {
    fun write(context: Context, bitmap: Bitmap, model: PhotoModel, output: OutputStream) {
        require(bitmap.config == Bitmap.Config.RGBA_F16)
        val key = when (model.outputColorSpace) {
            "Display P3" -> "p3"
            "Adobe RGB (1998)" -> "adobe"
            "ProPhoto RGB" -> "prophoto"
            else -> "srgb"
        }
        val suffix = if (model.outputCctfEncoding) "encoded" else "linear"
        val icc = context.assets.open("icc/$key-$suffix.icc").use { it.readBytes() }
        val description = ("Spektramat; ${model.outputColorSpace}; CCTF=${model.outputCctfEncoding}; IEEE float32\u0000")
            .toByteArray(Charsets.US_ASCII)
        val entries = 14
        val extraStart = 8 + 2 + entries * 12 + 4
        val bitsOffset = extraStart
        val sampleOffset = bitsOffset + 6
        val descriptionOffset = sampleOffset + 6
        val iccOffset = (descriptionOffset + description.size + 3) and -4
        val pixelOffset = (iccOffset + icc.size + 3) and -4
        val pixelBytes = bitmap.width.toLong() * bitmap.height * 12
        require(pixelOffset + pixelBytes <= 0xffffffffL) { "TIFF exceeds 4 GiB" }
        val header = ByteBuffer.allocate(pixelOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(entries.toShort())
        fun tag(id: Int, type: Int, count: Int, value: Int) {
            header.putShort(id.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
        }
        tag(256, 4, 1, bitmap.width)
        tag(257, 4, 1, bitmap.height)
        tag(258, 3, 3, bitsOffset)
        tag(259, 3, 1, 1) // uncompressed, one strip
        tag(262, 3, 1, 2) // RGB
        tag(270, 2, description.size, descriptionOffset)
        tag(273, 4, 1, pixelOffset)
        tag(274, 3, 1, 1)
        tag(277, 3, 1, 3)
        tag(278, 4, 1, bitmap.height)
        tag(279, 4, 1, pixelBytes.toInt())
        tag(284, 3, 1, 1) // interleaved samples
        tag(339, 3, 3, sampleOffset)
        tag(34675, 7, icc.size, iccOffset)
        header.putInt(0)
        repeat(3) { header.putShort(32) }
        repeat(3) { header.putShort(3) } // IEEE floating point
        header.put(description)
        header.position(iccOffset); header.put(icc)
        output.write(header.array())
        val pixels = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(pixels)
        val row = ByteBuffer.allocate(bitmap.width * 12).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until bitmap.height) {
            row.clear()
            for (x in 0 until bitmap.width) for (c in 0..2) {
                row.putFloat(half(pixels.getShort(y * bitmap.rowBytes + x * 8 + c * 2).toInt() and 0xffff))
            }
            output.write(row.array())
        }
    }

    private fun half(bits: Int): Float {
        val sign = if (bits and 0x8000 == 0) 1f else -1f
        val exponent = (bits ushr 10) and 31
        val fraction = bits and 1023
        return when (exponent) {
            0 -> sign * Math.scalb(fraction.toFloat(), -24)
            31 -> if (fraction == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * Math.scalb((1024 + fraction).toFloat(), exponent - 25)
        }
    }
}
