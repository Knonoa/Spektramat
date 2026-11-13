package com.spektra.latent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class FloatTiffRoundTripTest {
    @Test fun linearFloatTiffCanBeImportedWithoutDisplayQuantizationOrRawMisclassification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(FilmEngine.start(context))
        val samples = floatArrayOf(-.25001f, .50001f, 2.0001f, -.25002f, .50002f, 2.0002f)
        val file = File(context.cacheDir, "float-roundtrip-${System.nanoTime()}.tif")
        try {
            FilmEngine.outputPixels(2, 1, samples,
                PhotoModel(outputColorSpace = "sRGB", outputCctfEncoding = false)).use { image ->
                file.outputStream().use { FloatTiff.write(context, image, it) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val original = digest.digest(file.readBytes())
            assertFalse("An ordinary RGB TIFF must not enter the camera RAW decoder", FilmEngine.isRaw(file.path))
            assertArrayEquals(intArrayOf(2, 1), Decoder.size(file))
            Decoder.decode(file, 0).use { decoded ->
                assertFalse(decoded.previewOnly)
                val rgb = FloatArray(6).also { decoded.pixels().asFloatBuffer().get(it) }
                // ICC matrix coefficients have finite precision, so allow a
                // small colour-conversion tolerance while requiring distinct
                // nearby samples to survive the round trip.
                samples.forEachIndexed { i, value -> assertEquals("Float TIFF sample $i", value, rgb[i], .001f) }
                assertTrue(rgb[0] < 0f)
                assertTrue(rgb[2] > 1f)
                repeat(3) { c ->
                    assertTrue("Adjacent native samples in channel $c must remain distinct", rgb[c] != rgb[c + 3])
                }
            }
            assertArrayEquals("Decode must preserve the original file", original, digest.digest(file.readBytes()))
        } finally { file.delete() }
    }
}
