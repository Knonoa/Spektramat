package com.spektra.latent.engine

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.model.Look
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExposureProofTest {
    @Before fun startEngine() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
    }

    @Test fun negativeExposureChangesBrightnessAndPrintCompensationReducesItsRange() {
        withSource { source ->
            FilmEngine.fromBitmap(source).use { image ->
                val uncompensated = brightnessAtExposures(image, cleanLook(compensated = false))
                val compensated = brightnessAtExposures(image, cleanLook(compensated = true))
                assertVisibleExposureResponse("Portra without print compensation", uncompensated)

                // Compensation holds the print near its reference exposure; it
                // need not remove the stock's changes in contrast and colour.
                val uncompensatedRange = range(uncompensated)
                val compensatedRange = range(compensated)
                assertTrue(
                    "Print compensation should reduce the EV -2/0/+2 brightness range: " +
                        "uncompensated=$uncompensated compensated=$compensated",
                    compensatedRange < uncompensatedRange * .75,
                )
            }
        }
    }

    @Test fun changingProofExposureMatchesFreshRenderAndPreservesSourceBytes() {
        withSource { source ->
            FilmEngine.fromBitmap(source).use { image ->
                val original = sourceBytes(image)
                // Reverse direction and revisit old values to exercise both
                // invalidation and reuse of the proof lane's negative cache.
                val sequence = listOf(-2f, 0f, 2f, 0f, -2f, 1f, 2f)
                for (compensated in listOf(false, true)) {
                    val base = cleanLook(compensated = compensated)
                    for (ev in sequence) {
                        val look = base.copy(exposure = ev)
                        val proof = render(image, look, FilmEngine.PROOF)
                        try {
                            assertArrayEquals("PROOF changed source bytes at EV=$ev", original, sourceBytes(image))
                            // THUMB uses the same full photo algorithm and
                            // source size, without the PROOF negative cache.
                            FilmEngine.fromBitmap(source).use { fresh ->
                                val freshBytes = sourceBytes(fresh)
                                val expected = render(fresh, look, FilmEngine.THUMB)
                                try {
                                    assertEquals(expected.width, proof.width)
                                    assertEquals(expected.height, proof.height)
                                    assertArrayEquals(
                                        "PROOF differs from fresh THUMB at EV=$ev, compensation=$compensated",
                                        bitmapPixels(expected), bitmapPixels(proof),
                                    )
                                    assertArrayEquals("THUMB changed source bytes", freshBytes, sourceBytes(fresh))
                                } finally { expected.recycle() }
                            }
                        } finally { proof.recycle() }
                    }
                }
                assertArrayEquals("Exposure sequence changed source bytes", original, sourceBytes(image))
            }
        }
    }

    @Test fun reversalFilmAlsoRespondsToCameraExposure() {
        val stock = "kodak_ektachrome_100"
        assertTrue("The fixture must be a reversal stock", FilmEngine.catalog.film(stock)?.positive == true)
        withSource { source ->
            FilmEngine.fromBitmap(source).use { image ->
                val look = cleanLook(film = stock)
                assertTrue("Reversal film should bypass paper", look.scanFilm)
                assertVisibleExposureResponse("Ektachrome", brightnessAtExposures(image, look))
            }
        }
    }

    private fun cleanLook(film: String = Look.DEFAULT_FILM, compensated: Boolean = false) = Look(
        film = film, halation = 0f, grain = 0f, interLayer = 0f, glare = 0f,
        model = PhotoModel(printExposureCompensation = compensated),
    )

    private fun render(image: LinearImage, look: Look, lane: Int): Bitmap = FilmEngine.develop(
        image, look.photoGrade(), look.order(7919L).copy(scatter = 0f, sharpen = false),
        look.finish(), look.finishTable(), lane = lane,
    )

    private fun brightnessAtExposures(image: LinearImage, look: Look): List<Double> =
        listOf(-2f, 0f, 2f).map { ev ->
            val output = render(image, look.copy(exposure = ev), FilmEngine.THUMB)
            try {
                bitmapPixels(output).map { argb ->
                    (.2126 * ((argb ushr 16) and 255) +
                        .7152 * ((argb ushr 8) and 255) + .0722 * (argb and 255)) / 255.0
                }.average()
            } finally { output.recycle() }
        }

    private fun assertVisibleExposureResponse(label: String, brightness: List<Double>) {
        // Broad visibility checks, not golden image values: four stops should
        // do more than move a few output codes, and each step should brighten.
        assertTrue("$label EV -2/0/+2 should brighten in order: $brightness",
            brightness.zipWithNext().all { (low, high) -> high - low > 4.0 / 255.0 })
        assertTrue("$label should visibly change across four stops: $brightness", range(brightness) > .10)
    }

    private fun range(values: List<Double>) = values.max() - values.min()

    private fun sourceBytes(image: LinearImage): ByteArray {
        val buffer = image.pixels().duplicate().apply { clear() }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun bitmapPixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun withSource(block: (Bitmap) -> Unit) {
        val width = 32
        val height = 24
        // Alternating rows of grey and coloured 4x4 patches keep the source
        // deterministic while covering shadows, midtones and highlights.
        val palette = intArrayOf(
            0xff202020.toInt(), 0xff404040.toInt(), 0xff606060.toInt(), 0xff808080.toInt(),
            0xffa0a0a0.toInt(), 0xffc0c0c0.toInt(), 0xffe0e0e0.toInt(), 0xfff8f8f8.toInt(),
            0xffb04030.toInt(), 0xff40a050.toInt(), 0xff4050b0.toInt(), 0xffb09030.toInt(),
            0xff309090.toInt(), 0xffa04090.toInt(), 0xff704830.toInt(), 0xff7090b0.toInt(),
        )
        val pixels = IntArray(width * height) { i ->
            palette[((i % width) / 4 + (i / width) / 4 * 8) % palette.size]
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try { block(source) } finally { source.recycle() }
    }
}
