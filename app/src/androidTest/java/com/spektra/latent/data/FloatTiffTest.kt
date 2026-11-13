package com.spektra.latent.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.engine.FilmEngine
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
        val samples = floatArrayOf(-.25001f, .50001f, 2.0001f)
            for (space in PhotoModel.outputColorSpaces) for (encoded in listOf(true, false)) {
                val output = ByteArrayOutputStream()
                FilmEngine.outputPixels(1, 1, samples,
                    PhotoModel(outputColorSpace = space, outputCctfEncoding = encoded)).use {
                    FloatTiff.write(context, it, output)
                }
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
                for (c in 0..2) assertEquals(samples[c].toRawBits(), data.getFloat(strip + c * 4).toRawBits())
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
    }
}
