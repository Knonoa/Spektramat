package com.spektra.latent.engine

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.model.Finishing
import com.spektra.latent.model.Look
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AdjustmentPhotoTest {
    @Before fun startEngine() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
    }

    @Test fun zeroAdjustmentsMatchTheDisplayedOriginalWithinOneSrgbCode() {
        withSource { image ->
            val displayed = FilmEngine.display(image)
            val expected = try { bitmapPixels(displayed) } finally { displayed.recycle() }
            val actual = adjustedPixels(image, Look())
            var maximum = 0
            for (i in expected.indices) maximum = maxOf(maximum, maxRgbDifference(expected[i], actual[i]))
            assertTrue("Zero adjustments differ from the original by $maximum sRGB codes", maximum <= 1)
            assertTrue("The adjusted source must stay opaque", actual.all { (it ushr 24) == 255 })
        }
    }

    @Test fun sourceExposureRespondsToBothNegativeAndPositiveEv() {
        withSource { image ->
            val brightness = listOf(-1.5f, 0f, 1.5f).map { ev ->
                meanBrightness(adjustedPixels(image, Look(exposure = ev)))
            }
            // A visible response in each direction, without requiring a
            // particular tone curve or reproducing the grade calculation.
            assertTrue("EV -1.5/0/+1.5 should visibly brighten the source: $brightness",
                brightness.zipWithNext().all { (low, high) -> high - low > .08 })
        }
    }

    @Test fun eachFinishingControlChangesTheOriginalWithoutFilm() {
        withSource { image ->
            val neutral = Look()
            val baseline = adjustedPixels(image, neutral)
            val controls = listOf(
                "curve" to neutral.copy(curve = listOf(0f, 0f, .5f, .75f, 1f, 1f)),
                "HSL mixer" to neutral.copy(mixer = Finishing.HUE_NAMES.flatMap { listOf(0f, -75f, 0f) }),
                "colour grading" to neutral.copy(grading = listOf(
                    225f, 65f, 0f, 225f, 65f, 0f, 225f, 65f, 0f,
                )),
                "vignette" to neutral.copy(vignette = -80f),
            )
            for ((name, look) in controls) {
                val actual = adjustedPixels(image, look)
                var totalDifference = 0L
                var changedPixels = 0
                for (i in baseline.indices) {
                    if (maxRgbDifference(baseline[i], actual[i]) > 2) changedPixels++
                    for (shift in listOf(16, 8, 0)) {
                        totalDifference += abs(((baseline[i] ushr shift) and 255) - ((actual[i] ushr shift) and 255))
                    }
                }
                val meanDifference = totalDifference.toDouble() / (baseline.size * 3)
                assertTrue("$name should visibly change the original; mean difference=$meanDifference codes",
                    meanDifference > 2.0)
                assertTrue("$name should affect more than a few pixels; changed=$changedPixels/${baseline.size}",
                    changedPixels > baseline.size / 8)
            }
        }
    }

    @Test fun filmPaperEdgeAndGrainSelectionsDoNotChangeSourceAdjustments() {
        withSource { image ->
            // Keep real corrections active while changing simulation controls,
            // so this also catches a selection resetting the adjusted preview.
            val base = Look(exposure = .35f, temperature = 12f, contrast = 10f,
                curve = listOf(0f, 0f, .5f, .65f, 1f, 1f), vignette = -20f)
            val expected = adjustedPixels(image, base)
            val otherFilm = FilmEngine.catalog.films.first { it.id != base.film }.id
            val otherPaper = FilmEngine.catalog.papers.first { it.id != base.paper }.id
            val selections = listOf(
                "film" to base.copy(film = otherFilm),
                "paper" to base.copy(paper = otherPaper),
                "edge" to base.copy(edge = base.edge.copy(on = true, fog = 250f, leaks = 150f)),
                "grain off" to base.copy(grain = 0f),
                "grain 200%" to base.copy(grain = 200f),
            )
            for ((name, look) in selections) {
                assertArrayEquals("Changing $name must not affect source adjustments", expected, adjustedPixels(image, look))
            }
        }
    }

    private fun adjustedPixels(image: LinearImage, look: Look): IntArray {
        val original = sourceBytes(image)
        val output = FilmEngine.adjustPhoto(image, look.grade(0f), look.finish(), look.finishTable())
        try {
            assertEquals("Source adjustment width", image.width, output.width)
            assertEquals("Source adjustment height", image.height, output.height)
            assertArrayEquals("adjustPhoto must preserve original linear RGB bytes", original, sourceBytes(image))
            return bitmapPixels(output)
        } finally { output.recycle() }
    }

    private fun meanBrightness(pixels: IntArray) = pixels.map { argb ->
        (.2126 * ((argb ushr 16) and 255) +
            .7152 * ((argb ushr 8) and 255) + .0722 * (argb and 255)) / 255.0
    }.average()

    private fun maxRgbDifference(a: Int, b: Int) = maxOf(
        abs(((a ushr 16) and 255) - ((b ushr 16) and 255)),
        abs(((a ushr 8) and 255) - ((b ushr 8) and 255)),
        abs((a and 255) - (b and 255)),
    )

    private fun sourceBytes(image: LinearImage): ByteArray {
        val buffer = image.pixels().duplicate().apply { clear() }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun bitmapPixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun withSource(block: (LinearImage) -> Unit) {
        val width = 48
        val height = 32
        val palette = intArrayOf(
            0xff202020.toInt(), 0xff404040.toInt(), 0xff606060.toInt(), 0xff808080.toInt(),
            0xffa0a0a0.toInt(), 0xffc0c0c0.toInt(), 0xffe0e0e0.toInt(), 0xfff8f8f8.toInt(),
            0xffb04030.toInt(), 0xff40a050.toInt(), 0xff4050b0.toInt(), 0xffb09030.toInt(),
            0xff309090.toInt(), 0xffa04090.toInt(), 0xff704830.toInt(), 0xff7090b0.toInt(),
        )
        val pixels = IntArray(width * height) { i ->
            palette[((i % width) / 6 + (i / width) / 4 * 8) % palette.size]
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            FilmEngine.fromBitmap(source).use { image ->
                val original = sourceBytes(image)
                block(image)
                assertArrayEquals("Adjustment sequence must preserve the source bytes", original, sourceBytes(image))
            }
        } finally { source.recycle() }
    }
}
