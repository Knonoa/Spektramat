package com.spektra.latent.data

import android.content.Context
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.PhotoPixels
import java.io.OutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import java.util.zip.DeflaterOutputStream

/** DNG 1.4 LinearRaw RGB print, not camera-mosaic RAW. Float samples retain extended range. */
internal object LinearDng {
    fun write(context: Context, image: PhotoPixels, output: OutputStream, shouldStop: () -> Boolean = { false }) {
        fun checkStopped() { if (shouldStop()) throw DevelopCancelled() }
        checkStopped()
        val pixelBytes = image.width.toLong() * image.height * 12
        require(pixelBytes <= 0xffffffffL) { "DNG exceeds 4 GiB" }
        // DNG float data uses Deflate with the TIFF floating-point predictor.
        // Spool the compressed strip to disk so the full native image does not
        // require a second image-sized allocation to discover its byte count.
        val strip = File.createTempFile(".linear-dng-", ".strip", context.cacheDir)
        try {
            strip.outputStream().use { raw ->
                DeflaterOutputStream(raw).use { compressed ->
                    val samples = image.pixels().asFloatBuffer()
                    val count = image.width * 3
                    val row = ByteArray(count * 4)
                    for (y in 0 until image.height) {
                        checkStopped()
                        for (x in 0 until count) {
                            val value = PrintEncoding.linear(samples[y * count + x], image.model)
                            require(value.isFinite()) { "DNG cannot encode non-finite samples" }
                            val bits = value.toRawBits()
                            // Predictor 3 first arranges byte planes in MSB order.
                            for (plane in 0..3) row[plane * count + x] = (bits ushr (24 - 8 * plane)).toByte()
                        }
                        for (i in row.lastIndex downTo 3) row[i] = (row[i].toInt() - row[i - 3].toInt()).toByte()
                        compressed.write(row)
                    }
                }
            }
            checkStopped()
            val stripBytes = strip.length()
            require(stripBytes in 1..0xffffffffL) { "DNG strip exceeds 4 GiB" }
            fun words(vararg values: Int) = ByteBuffer.allocate(values.size*2).order(ByteOrder.LITTLE_ENDIAN)
                .also { b -> values.forEach { b.putShort(it.toShort()) } }.array()
            fun longs(vararg values: Int) = ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN)
                .also { b -> values.forEach { b.putInt(it) } }.array()
            fun rationals(values: DoubleArray) = ByteBuffer.allocate(values.size*8).order(ByteOrder.LITTLE_ENDIAN)
                .also { b -> values.forEach { b.putInt((it*1_000_000.0).roundToInt()); b.putInt(1_000_000) } }.array()
            fun ascii(value: String) = (value + "\u0000").toByteArray(Charsets.US_ASCII)
            data class Field(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
            val matrix = PrintEncoding.xyzD50(image.model)
            val icc = PrintEncoding.profile(context, image.model, linear = true)
            val fields = mutableListOf(
                Field(254,4,1,longs(0)),
                Field(256,4,1,longs(image.width)), Field(257,4,1,longs(image.height)),
                Field(258,3,3,words(32,32,32)), Field(259,3,1,words(8)),
                Field(262,3,1,words(34892)), // LinearRaw, required by the DNG RGB raw-image definition.
                Field(270,2,0,ascii("Spektramat film-processed print; ${image.model.outputColorSpace}; linear IEEE float32")),
                Field(271,2,0,ascii("Spektramat")), Field(272,2,0,ascii("Linear Print")),
                Field(273,4,1,longs(0)), Field(274,3,1,words(1)), Field(277,3,1,words(3)),
                Field(278,4,1,longs(image.height)), Field(279,4,1,longs(stripBytes.toInt())),
                Field(284,3,1,words(1)), Field(305,2,0,ascii("Spektramat")),
                Field(317,3,1,words(3)),
                Field(339,3,3,words(3,3,3)),
                Field(34675,7,icc.size,icc),
                Field(50706,1,4,byteArrayOf(1,4,0,0)), Field(50707,1,4,byteArrayOf(1,4,0,0)),
                Field(50708,2,0,ascii("Spektramat Linear Print")),
                Field(50713,3,2,words(1,1)), Field(50714,5,3,rationals(doubleArrayOf(0.0,0.0,0.0))),
                Field(50717,4,3,longs(1,1,1)),
                Field(50718,5,2,rationals(doubleArrayOf(1.0,1.0))),
                Field(50719,4,2,longs(0,0)), Field(50720,4,2,longs(image.width,image.height)),
                Field(50721,10,9,rationals(PrintEncoding.inverse(matrix))),
                Field(50727,5,3,rationals(doubleArrayOf(1.0,1.0,1.0))),
                Field(50728,5,3,rationals(doubleArrayOf(1.0,1.0,1.0))),
                Field(50730,10,1,rationals(doubleArrayOf(0.0))),
                Field(50778,3,1,words(23)), // D50; all primaries were chromatically adapted to this white.
                Field(50964,10,9,rationals(matrix)),
            ).map { if (it.type == 2) it.copy(count = it.bytes.size) else it }.sortedBy { it.tag }
            val extraStart = 8 + 2 + fields.size * 12 + 4
            val extraBytes = fields.filter { it.bytes.size > 4 }.sumOf { (it.bytes.size + 3) and -4 }
            val pixelStart = extraStart + extraBytes
            require(pixelStart + stripBytes <= 0xffffffffL) { "DNG exceeds 4 GiB" }
            val header = ByteBuffer.allocate(pixelStart).order(ByteOrder.LITTLE_ENDIAN)
            header.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(fields.size.toShort())
            var extra = extraStart
            for (field in fields) {
                header.putShort(field.tag.toShort()).putShort(field.type.toShort()).putInt(field.count)
                if (field.tag == 273) header.putInt(pixelStart)
                else if (field.bytes.size <= 4) {
                    header.put(field.bytes); repeat(4 - field.bytes.size) { header.put(0) }
                } else {
                    header.putInt(extra)
                    val next = header.position()
                    header.position(extra); header.put(field.bytes); header.position(next)
                    extra += (field.bytes.size + 3) and -4
                }
            }
            header.putInt(0)
            output.write(header.array())
            strip.inputStream().use { input ->
                val block = ByteArray(64 * 1024)
                while (true) {
                    checkStopped()
                    val count = input.read(block)
                    if (count < 0) break
                    output.write(block, 0, count)
                }
            }
        } finally { strip.delete() }
    }
}
