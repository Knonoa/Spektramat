package com.spektra.latent.engine

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Output-size choices must resample float pixels, never their display copy. */
@RunWith(AndroidJUnit4::class)
class PhotoScaleTest {
    @Before fun startEngine() {
        assertNull(FilmEngine.start(ApplicationProvider.getApplicationContext<android.content.Context>()))
    }

    @Test fun encodedBlackAndWhiteResizeAveragesLightRatherThanSrgbCodes() {
        val samples = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
        FilmEngine.outputPixels(2, 1, samples,
            PhotoModel(outputColorSpace = "sRGB", outputCctfEncoding = true)).use { original ->
            original.scaled(1).use { resized ->
                assertEquals(1, resized.width)
                assertEquals(1, resized.height)
                val rgb = resized.pixels().asFloatBuffer()
                // Equal areas of black and white have 50% linear luminance,
                // whose standard sRGB code is approximately 0.735357.
                repeat(3) { c -> assertEquals(.735357f, rgb[c], .000002f) }
            }
            assertArrayEquals("Resampling must not rewrite finished full-resolution samples",
                samples, FloatArray(samples.size).also { original.pixels().asFloatBuffer().get(it) }, 0f)
        }
    }

    @Test fun linearResizeKeepsNegativeAndAboveWhiteValuesAtFloatPrecision() {
        val pixel = floatArrayOf(-.25001f, .50001f, 2.0001f)
        val samples = pixel + pixel
        FilmEngine.outputPixels(2, 1, samples,
            PhotoModel(outputColorSpace = "sRGB", outputCctfEncoding = false)).use { original ->
            original.scaled(1).use { resized ->
                val rgb = resized.pixels().asFloatBuffer()
                repeat(3) { c ->
                    assertEquals("Linear sample $c must not be clamped or rounded to F16",
                        pixel[c].toRawBits(), rgb[c].toRawBits())
                }
            }
            assertArrayEquals(samples,
                FloatArray(samples.size).also { original.pixels().asFloatBuffer().get(it) }, 0f)
        }
    }
}
