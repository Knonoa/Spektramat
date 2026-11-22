package com.spektra.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.LinearImage
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.model.CreativeLook
import com.spektra.latent.model.ExposureLayer
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CreativeSourceTest {
    private val app get() = ApplicationProvider.getApplicationContext<LatentApp>()

    @Test fun importsOwnExactBytesAndEnforceFiveImagesIncludingTheBase() = runBlocking {
        ready()
        val sources = CreativeSource(app)
        val inputs = (1..6).map { png("import-$it", 24, 16) }
        val imported = mutableListOf<ExposureLayer>()
        try {
            val bytes = inputs.map { it.readBytes() }
            val report = sources.import(inputs.map(Uri::fromFile), 4)
            imported += report.layers
            assertEquals(4, report.layers.size)
            assertEquals(2, report.overflow)
            assertTrue(report.failures.toString(), report.failures.isEmpty())
            for ((index, layer) in report.layers.withIndex()) {
                assertArrayEquals(bytes[index], sources.file(layer).readBytes())
                assertNotEquals(inputs[index].canonicalPath, sources.file(layer).canonicalPath)
            }
            inputs.forEach { it.delete() }
            for (layer in report.layers) Decoder.decode(sources.file(layer), 0).use {
                assertEquals(24, it.width); assertEquals(16, it.height)
            }
            val full = sources.import(listOf(Uri.fromFile(sources.file(imported.first()))), 0)
            assertTrue(full.layers.isEmpty()); assertEquals(1, full.overflow)
        } finally {
            inputs.forEach { it.delete() }
            imported.forEach { sources.thumbnail(it)?.delete(); sources.file(it).delete() }
        }
    }

    @Test fun floatingSourcesRetainHdrShadowsAndIndependentOwnership() = runBlocking {
        ready()
        val sources = CreativeSource(app)
        val file = File(app.cacheDir, "creative-hdr-${System.nanoTime()}.tif")
        val imported = mutableListOf<ExposureLayer>()
        val layerRgb = floatArrayOf(-.2f, .6f, 4f)
        val samples = FloatArray(8 * 4 * 3) { layerRgb[it % 3] }
        try {
            FilmEngine.outputPixels(8, 4, samples, PhotoModel(outputCctfEncoding = false)).use { pixels ->
                file.outputStream().use { FloatTiff.write(app, pixels, it) }
            }
            val report = sources.import(listOf(Uri.fromFile(file)), 4)
            imported += report.layers
            assertTrue(report.failures.toString(), report.failures.isEmpty())
            val layer = report.layers.single().copy(weight = 50f)
            // TIFF's ICC round trip has its own matrix error. Test the compositor
            // against the decoder's float output rather than the pre-ICC fixture.
            val decodedLayer = Decoder.decode(sources.file(layer), 0).use(::values)
            val baseRgb = floatArrayOf(-.4f, .2f, 2f)
            val baseSamples = FloatArray(8 * 4 * 3) { baseRgb[it % 3] }
            val buffer = java.nio.ByteBuffer.allocate(baseSamples.size * 4).order(java.nio.ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(baseSamples)
            FilmEngine.restoreProxy(8, 4, buffer, false, 0).use { base ->
                val look = Look(creative = CreativeLook(layers = listOf(layer)))
                sources.apply(base, look, 0).use { result ->
                    val expected = FloatArray(baseSamples.size) { (baseSamples[it] + decodedLayer[it] * .5f) / 1.5f }
                    assertArrayEquals(expected, values(result), .000002f)
                    assertTrue(values(result)[0] < 0f); assertTrue(values(result)[2] > 1f)
                }
                assertArrayEquals(baseSamples, values(base), 0f)
                sources.apply(base, Look(), 0).close()
                assertArrayEquals("A returned copy must not own the caller's source", baseSamples, values(base), 0f)
                val composite = FilmEngine.exposureComposite(base)
                val detached = composite.detach()
                composite.close()
                detached.use {
                    assertArrayEquals("Detached images belong to the caller", baseSamples, values(it), 0f)
                    try {
                        composite.add(base, 100f)
                        fail("A detached composite cannot accept further layers")
                    } catch (_: IllegalStateException) { }
                }
                try {
                    sources.apply(base, look.copy(creative = CreativeLook(layers = List(5) { layer })), 0).close()
                    fail("Native composition must also enforce the five-image limit")
                } catch (_: IllegalArgumentException) { }
                try {
                    sources.apply(base, look.copy(creative = CreativeLook(layers = listOf(
                        ExposureLayer(UUID.randomUUID().toString(), "missing original")))), 0).close()
                    fail("A missing original must fail instead of silently changing the composition")
                } catch (_: IOException) { }
            }
        } finally {
            file.delete()
            imported.forEach { sources.thumbnail(it)?.delete(); sources.file(it).delete() }
        }
    }

    @Test fun jpegExportUsesAllNativeOriginalsAfterWarmingATinyProxy() = runBlocking {
        val startup = ready()
        val sources = CreativeSource(app)
        val baseFile = png("export-base", 96, 64)
        val extraFile = png("export-layer", 72, 96)
        val imported = mutableListOf<ExposureLayer>()
        try {
            val report = sources.import(listOf(Uri.fromFile(extraFile)), 4)
            imported += report.layers
            assertTrue(report.failures.toString(), report.failures.isEmpty())
            val look = Look(grain = 0f, halation = 0f, glare = 0f, interLayer = 0f,
                creative = CreativeLook(softFocus = 55f, flare = 25f, glow = 40f,
                    layers = listOf(report.layers.single().copy(weight = 65f))))
            val frame = Frame(-998313L, -1L, 1, baseFile, "creative native export", false, 96, 64,
                0L, "", "", 0, 0.0, 0.0, 0.0, 0f, look, baseFile, 1)
            PhotoProxyStore.get(app).acquire(frame, "", true, 24).use { proxy ->
                sources.apply(proxy, look, 24).use { assertEquals(24, it.width); assertEquals(16, it.height) }
            }
            val expected = Decoder.decode(baseFile, 0).use { original ->
                sources.apply(original, look, 0).use { composed ->
                    FilmEngine.developFloat(composed, look.photoGrade(),
                        look.photoOrder(frame.id * 7919L + 1L, Shooting.of(frame), Library.frameSeed(frame), frame.number),
                        look.finish(), look.finishTable()).use { it.display(0) }
                }
            }
            try {
                val printed = Printer.print(app, startup.library, frame, look, 0, PrintFormat.JPEG) { }
                try {
                    assertEquals(96, printed.width); assertEquals(64, printed.height)
                    assertTrue("The finished JPEG must be visible in the gallery", printed.inGallery)
                    assertArrayEquals("The print must use native originals and the complete creative pipeline",
                        pixels(expected), pixels(printed.shown))
                    val encoded = ByteArrayOutputStream().also { expected.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
                    val expectedJpeg = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)!!
                    val actualJpeg = app.contentResolver.openInputStream(printed.uri)!!.use { BitmapFactory.decodeStream(it)!! }
                    try { assertArrayEquals(pixels(expectedJpeg), pixels(actualJpeg)) }
                    finally { expectedJpeg.recycle(); actualJpeg.recycle() }
                } finally {
                    printed.shown.recycle(); app.contentResolver.delete(printed.uri, null, null)
                }
            } finally { expected.recycle() }
        } finally {
            baseFile.delete(); extraFile.delete()
            imported.forEach { sources.thumbnail(it)?.delete(); sources.file(it).delete() }
        }
    }

    private suspend fun ready() = withTimeout(120_000) {
        app.startup.first { it is Startup.Ready } as Startup.Ready
    }

    private fun png(name: String, w: Int, h: Int): File {
        val file = File(app.cacheDir, "creative-$name-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bitmap.setPixel(x, y,
            if (x in w / 3..w * 2 / 3 && y in h / 3..h * 2 / 3) Color.WHITE
            else Color.rgb(20 + x * 160 / w, 30 + y * 150 / h, 70))
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return file
    }

    private fun values(image: LinearImage) = FloatArray(image.width * image.height * 3).also { image.pixels().asFloatBuffer().get(it) }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
