package com.spektra.latent.data

import android.content.Context
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.engine.PhotoPixels
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

/** A 16-bit RGB PNG, encoded directly from the finished float pixels with the matching ICC. */
internal object PhotoPng {
    fun write(context: Context, image: PhotoPixels, output: OutputStream, shouldStop: () -> Boolean = { false }) {
        val pixels = image.pixels().asFloatBuffer()
        writeRows(context, image.width, image.height, image.model, output, shouldStop) { _, row ->
            pixels.get(row)
        }
    }

    /** Fill each scanline in the common RGB output space without a full compositing buffer. */
    fun writeRows(context: Context, width: Int, height: Int, model: PhotoModel,
                  output: OutputStream, shouldStop: () -> Boolean, fill: (Int, FloatArray) -> Unit) {
        fun checkStopped() { if (shouldStop()) throw DevelopCancelled() }
        checkStopped()
        output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply { writeInt(width); writeInt(height); write(byteArrayOf(16,2,0,0,0)) }
        }.toByteArray()
        chunk(output, "IHDR", header)
        val profile = ByteArrayOutputStream().also { bytes ->
            bytes.write("Spektramat\u0000".toByteArray(Charsets.US_ASCII)); bytes.write(0)
            DeflaterOutputStream(bytes).use { it.write(PrintEncoding.profile(context, model)) }
        }.toByteArray()
        chunk(output, "iCCP", profile)
        // Stream bounded IDAT chunks rather than retaining another complete image in memory.
        val chunks = object : OutputStream() {
            val buffer = ByteArray(64 * 1024)
            var used = 0
            override fun write(value: Int) { buffer[used++] = value.toByte(); if (used == buffer.size) flush() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var at = offset; var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size - used)
                    bytes.copyInto(buffer, used, at, at + count)
                    used += count; at += count; remaining -= count
                    if (used == buffer.size) flush()
                }
            }
            override fun flush() { if (used > 0) { checkStopped(); chunk(output, "IDAT", buffer, used); used = 0 } }
            override fun close() = flush()
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            val samples = FloatArray(width * 3)
            val row = ByteArray(1 + width * 6)
            DeflaterOutputStream(chunks, deflater).use { compressed ->
                for (y in 0 until height) {
                    checkStopped()
                    fill(y, samples)
                    row[0] = 0 // PNG filter None; exact samples are preserved after integer encoding.
                    for (x in samples.indices) {
                        val value = samples[x]
                        require(value.isFinite()) { "PNG cannot encode non-finite samples" }
                        val code = (value.coerceIn(0f, 1f).toDouble() * 65535.0).roundToInt()
                        row[1 + x * 2] = (code ushr 8).toByte()
                        row[2 + x * 2] = code.toByte()
                    }
                    compressed.write(row)
                }
            }
        } finally { deflater.end() }
        checkStopped()
        chunk(output, "IEND", ByteArray(0))
    }

    private fun chunk(output: OutputStream, kind: String, bytes: ByteArray, count: Int = bytes.size) {
        val type = kind.toByteArray(Charsets.US_ASCII)
        val stream = DataOutputStream(output)
        stream.writeInt(count); stream.write(type); stream.write(bytes,0,count)
        val crc = CRC32().apply { update(type); update(bytes,0,count) }
        stream.writeInt(crc.value.toInt())
    }
}
