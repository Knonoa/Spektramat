package com.spektra.latent.data

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.PhotoModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class FloatTiffTest {
    @Test fun exportPreservesFloatSamplesAndEmbedsMatchingColorProfile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_F16, true,
            ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        val pixels = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.nativeOrder())
        pixels.putShort(0xb400.toShort()).putShort(0x3800).putShort(0x4000).putShort(0x3c00).rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        try {
            for (space in PhotoModel.outputColorSpaces) for (encoded in listOf(true, false)) {
                val output = ByteArrayOutputStream()
                FloatTiff.write(context, bitmap, PhotoModel(outputColorSpace = space, outputCctfEncoding = encoded), output)
                val bytes = output.toByteArray()
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(42, data.getShort(2).toInt())
                val ifd = data.getInt(4)
                val count = data.getShort(ifd).toInt()
                fun field(tag: Int): Pair<Int, Int> {
                    for (i in 0 until count) {
                        val offset = ifd + 2 + i * 12
                        if (data.getShort(offset).toInt() and 0xffff == tag)
                            return data.getInt(offset + 4) to data.getInt(offset + 8)
                    }
                    error("Missing TIFF tag $tag")
                }
                val strip = field(273).second
                assertEquals(-.25f, data.getFloat(strip), 0f)
                assertEquals(.5f, data.getFloat(strip + 4), 0f)
                assertEquals(2f, data.getFloat(strip + 8), 0f)
                val (size, offset) = field(34675)
                val profile = ByteBuffer.wrap(bytes, offset, size).slice().order(ByteOrder.BIG_ENDIAN)
                assertEquals(size, profile.getInt(0))
                assertEquals("acsp", String(bytes, offset + 36, 4, Charsets.US_ASCII))
                // The ICC v4 description is UTF-16; ensure encoded/linear
                // exports identify the requested colour space independently.
                val text = String(bytes.copyOfRange(offset, offset + size), Charsets.UTF_16BE)
                assertTrue(text.contains(space))
                assertEquals(!encoded, text.contains("(linear)"))
            }
        } finally { bitmap.recycle() }
    }
}
