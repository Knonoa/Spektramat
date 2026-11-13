package com.spektra.latent.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Exercise the real export route, including its output-format model handling. */
@RunWith(AndroidJUnit4::class)
class PrinterExposureTest {
    @Test fun jpegAndFloatExportsKeepFixedPhotoExposureForLegacyRecipes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val startup = withTimeout(120_000) { app.startup.first { it is Startup.Ready } as Startup.Ready }
        val file = File(app.cacheDir, "printer-exposure-${System.nanoTime()}.png")
        val source = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(245, 243, 240))
            for (y in 10..21) for (x in 18..29) setPixel(x, y, Color.rgb(150, 130, 110))
        }
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val originalHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val look = Look(exposure = .5f, grain = 0f, halation = 0f, interLayer = 0f, glare = 0f,
                model = PhotoModel(autoExposure = true, printExposureCompensation = true, unsharpAmount = 0f))
            // An unregistered test frame prevents touching the user's catalogue.
            val frame = Frame(-998881L, -1L, 1, file, "test export", false, 48, 32,
                0L, "", "", 0, 0.0, 0.0, 0.0, -2f, look, file, 1)
            for (format in listOf(PrintFormat.JPEG, PrintFormat.FLOAT_TIFF, PrintFormat.AUTO)) {
                val expected = Decoder.decode(file, 0, look.lensShading, look.aspect).use { image ->
                    FilmEngine.developFloat(image, look.photoGrade(),
                        look.photoOrder(frame.id * 7919L + 1L, Shooting.of(frame), Library.frameSeed(frame), frame.number),
                        look.finish(), look.finishTable())
                }
                try {
                    val printed = Printer.print(app, startup.library, frame, look, 0, format) { }
                    try {
                        val expectedDisplay = expected.display(0)
                        try {
                            assertEquals(48, printed.width)
                            assertEquals(32, printed.height)
                            assertArrayEquals("$format must not restore automatic exposure while selecting output format",
                                pixels(expectedDisplay), pixels(printed.shown))
                            app.contentResolver.openInputStream(printed.uri)!!.use { assertTrue(it.read() >= 0) }
                        } finally { expectedDisplay.recycle() }
                    } finally {
                        printed.shown.recycle()
                        app.contentResolver.delete(printed.uri, null, null)
                    }
                } finally { expected.close() }
            }
            val small = Printer.print(app, startup.library, frame, look, 24, PrintFormat.AUTO) { }
            try {
                assertEquals(24, small.width)
                assertEquals(16, small.height)
                Decoder.decode(file, 0, look.lensShading, look.aspect).use { original ->
                    FilmEngine.developFloat(original, look.photoGrade(),
                        look.photoOrder(frame.id * 7919L + 1L, Shooting.of(frame), Library.frameSeed(frame), frame.number),
                        look.finish(), look.finishTable()).use { full ->
                        full.scaled(24).use { reduced ->
                            val expected = reduced.display(0)
                            try { assertArrayEquals("Resize must follow full-resolution rendering",
                                pixels(expected), pixels(small.shown)) }
                            finally { expected.recycle() }
                        }
                    }
                }
                app.contentResolver.openInputStream(small.uri)!!.use {
                    assertEquals('I'.code, it.read())
                    assertEquals('I'.code, it.read())
                }
            } finally {
                small.shown.recycle()
                app.contentResolver.delete(small.uri, null, null)
            }
            assertArrayEquals("Export must leave source bytes unchanged", originalHash,
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally {
            source.recycle()
            file.delete()
        }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
