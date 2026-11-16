package com.spektra.latent.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
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
    @Test fun allFormatsKeepFixedPhotoExposureAndPublishRealFiles() = runBlocking {
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
            for (format in PrintFormat.entries) {
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
                            val bytes = app.contentResolver.openInputStream(printed.uri)!!.use { it.readBytes() }
                            assertFile(format, bytes, 48, 32)
                            if (Build.VERSION.SDK_INT >= 29) {
                                assertTrue("$format must be published to MediaStore", printed.inGallery)
                                app.contentResolver.query(printed.uri, arrayOf(MediaStore.Images.Media.MIME_TYPE,
                                    MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
                                    assertTrue(it.moveToFirst())
                                    assertEquals(format.mime, it.getString(0))
                                    assertEquals(0, it.getInt(1))
                                    assertEquals("Pictures/Spektramat/", it.getString(2))
                                }
                            }
                        } finally { expectedDisplay.recycle() }
                    } finally {
                        printed.shown.recycle()
                        app.contentResolver.delete(printed.uri, null, null)
                    }
                } finally { expected.close() }
            }
            // No explicit format: the default must be an album-compatible JPEG.
            val small = Printer.print(app, startup.library, frame, look, 24) { }
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
                app.contentResolver.openInputStream(small.uri)!!.use { assertFile(PrintFormat.JPEG, it.readBytes(), 24, 16) }
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

    private fun assertFile(format: PrintFormat, bytes: ByteArray, width: Int, height: Int) {
        when (format) {
            PrintFormat.JPEG -> {
                assertEquals(255, bytes[0].toInt() and 255); assertEquals(216, bytes[1].toInt() and 255)
            }
            PrintFormat.PNG -> assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), bytes.copyOf(8))
            PrintFormat.DNG -> {
                assertEquals('I'.code.toByte(), bytes[0]); assertEquals('I'.code.toByte(), bytes[1])
            }
        }
        if (format != PrintFormat.DNG) {
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            try { assertEquals(width, decoded.width); assertEquals(height, decoded.height) }
            finally { decoded.recycle() }
        }
    }
}
