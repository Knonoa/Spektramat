package com.spektra.latent.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.data.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(AndroidJUnit4::class)
class PhotoInputTest {
    @Test fun sixteenBitPngKeepsAdjacentCodesThatAnF16SurfaceWouldMerge() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val file = File(context.cacheDir, "precision-${System.nanoTime()}.png")
        val codes = intArrayOf(32768, 32769, 65534, 65535)
        try {
            DataOutputStream(file.outputStream()).use { out ->
                out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
                fun chunk(type: String, data: ByteArray) {
                    val kind = type.toByteArray(Charsets.US_ASCII)
                    out.writeInt(data.size); out.write(kind); out.write(data)
                    val crc = CRC32().apply { update(kind); update(data) }
                    out.writeInt(crc.value.toInt())
                }
                val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                    .putInt(codes.size).putInt(1).put(16.toByte()).put(2.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte())
                chunk("IHDR", header.array())
                val compressed = ByteArrayOutputStream()
                DataOutputStream(DeflaterOutputStream(compressed)).use { row ->
                    row.writeByte(0)
                    for (v in codes) repeat(3) { row.writeShort(v) }
                }
                chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
            }
            Decoder.decode(file, 0).use { image ->
                assertEquals(4, image.width); assertEquals(1, image.height)
                val actual = image.pixels().asFloatBuffer()
                fun linear(code: Int): Float {
                    val v = code / 65535.0
                    return (if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)).toFloat()
                }
                for (i in codes.indices) for (c in 0..2)
                    assertEquals("16-bit sample $i/$c", linear(codes[i]), actual[i * 3 + c], .000001f)
                assertTrue(actual[0] < actual[3]); assertTrue(actual[6] < actual[9])
            }
        } finally { file.delete() }
    }

    @Test fun floatExportKeepsOriginalSamplesAndHasNoF16Quantization() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val source = FloatArray(16 * 12 * 3) { i -> if (i % 3 == 0) -.010001f else .180003f + i * .000017f }
        FilmEngine.fromLinearRgb(16, 12, source).use { image ->
            val look = com.spektra.latent.model.Look(grain = 0f, glare = 0f)
            FilmEngine.developFloat(image, look.photoGrade(), look.photoOrder(7), look.finish(), look.finishTable()).use { output ->
                assertEquals(16, output.width); assertEquals(12, output.height)
                val samples = FloatArray(16 * 12 * 3).also { output.pixels().asFloatBuffer().get(it) }
                assertTrue(samples.all { it.isFinite() })
                // Every normal F16 sample is on a coarser grid than float32.
                assertTrue(samples.any { v -> v > .125f && v < 1f && v * 8192f != kotlin.math.floor(v * 8192f) })
            }
            val unchanged = FloatArray(source.size).also { image.pixels().asFloatBuffer().get(it) }
            org.junit.Assert.assertArrayEquals(source, unchanged, 0f)
        }
    }

    @Test fun extendedLinearFloatBitmapKeepsNegativeAndAboveWhiteChannels() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_F16, true,
            ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        val bytes = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        // Half floats [-0.25, 0.5, 2.0, 1.0].
        bytes.putShort(0xb400.toShort()).putShort(0x3800).putShort(0x4000).putShort(0x3c00).rewind()
        bitmap.copyPixelsFromBuffer(bytes)
        try {
            FilmEngine.fromBitmap(bitmap).use { image ->
                val rgb = image.pixels().asFloatBuffer()
                assertEquals(-0.25f, rgb[0], 0f)
                assertEquals(0.5f, rgb[1], 0f)
                assertEquals(2f, rgb[2], 0f)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun taggedRasterDecodeRemainsLinearWithoutDoubleCctf() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val file = File(context.cacheDir, "photo-input-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff808080.toInt()) }
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Decoder.decode(file, 0).use { image ->
                val rgb = image.pixels().asFloatBuffer()
                assertEquals(0.21586f, rgb[0], 0.001f)
                assertEquals(rgb[0], rgb[1], 0.0001f)
                assertEquals(rgb[1], rgb[2], 0.0001f)
            }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun repeatedProofPreservesSourceAndMatchesFirstResult() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff604020.toInt()) }
        // Use the app's own neutral finishing contract instead of encoding a
        // second copy of it in this test.
        val look = com.spektra.latent.model.Look(grain = 0f, glare = 0f)
        try {
            FilmEngine.fromBitmap(bitmap).use { image ->
                fun pixels() = FloatArray(image.width * image.height * 3).also { image.pixels().asFloatBuffer().get(it) }
                val source = pixels()
                val grade = look.photoGrade()
                val order = look.order(7L).copy(exposureEv = 1f)
                val first = FilmEngine.develop(image, grade, order, look.finish(), look.finishTable(), lane = FilmEngine.PROOF)
                val second = FilmEngine.develop(image, grade, order, look.finish(), look.finishTable(), lane = FilmEngine.PROOF)
                try {
                    org.junit.Assert.assertArrayEquals(source, pixels(), 0f)
                    val a = IntArray(first.width * first.height)
                    val b = IntArray(second.width * second.height)
                    first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
                    second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
                    org.junit.Assert.assertArrayEquals(a, b)
                } finally { first.recycle(); second.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun taggedDisplayP3RasterConvertsPrimariesAndTransferExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.RGBA_F16, true, p3)
        val bytes = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
        repeat(4) { bytes.putShort(0x3800).putShort(0x3400).putShort(0x3a00).putShort(0x3c00) }
        bytes.rewind(); bitmap.copyPixelsFromBuffer(bytes)
        val file = File(context.cacheDir, "photo-p3-${System.nanoTime()}.png")
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val expected = ColorSpace.connect(p3, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                .transform(.5f, .25f, .75f)
            Decoder.decode(file, 0).use { image ->
                val actual = image.pixels().asFloatBuffer()
                for (c in 0..2) assertEquals("ICC channel $c", expected[c], actual[c], .002f)
            }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun wideGamutAndLinearPhotoOutputsUseFloatColorManagedBitmaps() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff804020.toInt()) }
        try {
            FilmEngine.fromBitmap(source).use { image ->
                for (space in PhotoModel.outputColorSpaces) {
                    for (encoded in listOf(true, false)) {
                        val look = com.spektra.latent.model.Look(grain = 0f, glare = 0f,
                            model = PhotoModel(outputColorSpace = space, outputCctfEncoding = encoded))
                        val output = FilmEngine.develop(image, look.photoGrade(), look.order(7L),
                            look.finish(), look.finishTable(), lane = FilmEngine.PROOF, highPrecision = true)
                        try {
                            assertEquals(Bitmap.Config.RGBA_F16, output.config)
                            assertTrue(output.getColor(0, 0).components.all { it.isFinite() })
                            if (!encoded) {
                                val rgb = output.colorSpace as ColorSpace.Rgb
                                assertEquals(.25, rgb.eotf.applyAsDouble(.25), 0.00001)
                            }
                        } finally { output.recycle() }
                    }
                }
            }
        } finally { source.recycle() }
    }
}
