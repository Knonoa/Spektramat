package com.spektra.latent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.model.Look
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PhotoProxyStoreTest {
    @Test fun memoryAndDiskProxiesRetainFloatPrecisionAndDoNotModifyOriginals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(FilmEngine.start(context))
        val directory = File(context.cacheDir, "proxy-test-${System.nanoTime()}")
        val file = File(context.cacheDir, "proxy-input-${System.nanoTime()}.tif")
        val store = PhotoProxyStore(context, directory)
        try {
            val samples = floatArrayOf(-.25001f, .50001f, 2.0001f, -.25002f, .50002f, 2.0002f)
            writeFloat(context, file, 2, 1, samples)
            val frame = frame(file, 2, 1)
            val originalDigest = digest(file)
            val expected = Decoder.decode(file, 0).use { pixels(it.pixels()) }
            store.acquire(frame, "", true, 1280).use { source ->
                assertArrayEquals(expected, pixels(source.pixels()), 0f)
                assertTrue(source.pixels().asFloatBuffer()[0] < 0f)
                assertTrue(source.pixels().asFloatBuffer()[2] > 1f)
            }
            // A different recipe and smaller swatch share the same undecorated source.
            store.acquire(frame.copy(look = frame.look.copy(exposure = 2f)), "", true, 128).use { }
            assertEquals(1, store.decodeCount)
            store.clearMemory()
            store.acquire(frame, "", true, 1280).use { restored ->
                assertArrayEquals(expected, pixels(restored.pixels()), 0f)
                assertNotEquals(restored.pixels().asFloatBuffer()[0], restored.pixels().asFloatBuffer()[3])
                assertFalse(restored.previewOnly)
            }
            assertEquals(1, store.decodeCount)
            assertEquals(1, store.diskHitCount)
            assertArrayEquals(originalDigest, digest(file))

            // Truncated or altered cache bytes are disposable; source data remains authoritative.
            store.clearMemory()
            val cached = store.cacheFile(frame, "", true)
            val broken = cached.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            cached.writeBytes(broken)
            store.acquire(frame, "", true, 1280).use { assertArrayEquals(expected, pixels(it.pixels()), 0f) }
            assertEquals(2, store.decodeCount)

            // Size/mtime identity invalidates a source independently of look revisions.
            store.clearMemory()
            writeFloat(context, file, 2, 1, samples.map { it + .125f }.toFloatArray())
            file.setLastModified(file.lastModified() + 2000)
            store.acquire(frame, "", true, 1280).use { assertTrue(it.pixels().asFloatBuffer()[0] > expected[0]) }
            assertEquals(3, store.decodeCount)
        } finally {
            store.clearMemory()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete(); file.delete()
        }
    }

    @Test fun swatchesAndWorkbenchShareOneNativeDecodeButCropAndLensSettingsInvalidate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(FilmEngine.start(context))
        val directory = File(context.cacheDir, "proxy-shared-${System.nanoTime()}")
        val file = File(context.cacheDir, "proxy-shared-${System.nanoTime()}.tif")
        val store = PhotoProxyStore(context, directory)
        try {
            writeFloat(context, file, 256, 128, FloatArray(256 * 128 * 3) { (it % 103) / 100f - .01f })
            val frame = frame(file, 256, 128)
            store.acquire(frame, "", true, 128).use { assertEquals(128, it.width); assertEquals(64, it.height) }
            store.acquire(frame, "", true, 1280).use { assertEquals(256, it.width); assertEquals(128, it.height) }
            assertEquals(1, store.decodeCount)
            store.acquire(frame, "", false, 128).use { }
            assertEquals(2, store.decodeCount)
            store.acquire(frame, "1:1", false, 128).use { assertEquals(it.width, it.height) }
            assertEquals(3, store.decodeCount)
        } finally {
            store.clearMemory()
            directory.listFiles()?.forEach { it.delete() }
            directory.delete(); file.delete()
        }
    }

    @Test fun restoredRawFallbackCannotBecomeAnExportSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(FilmEngine.start(context))
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(floatArrayOf(-.01f, .2f, 2f, -.02f, .3f, 3f))
        }
        FilmEngine.restoreProxy(2, 1, buffer, true, 3).use { source ->
            assertTrue(source.previewOnly)
            assertEquals(3, source.lensOpcodes)
            source.scaled(1).use { assertTrue(it.previewOnly); assertEquals(3, it.lensOpcodes) }
            val look = Look()
            try {
                FilmEngine.developFloat(source, look.photoGrade(), look.photoOrder(1), look.finish(), look.finishTable()).close()
                fail("An embedded RAW preview must still be refused after a cache restore")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun frame(file: File, width: Int, height: Int) = Frame(-8877, -1, 1, file, file.name, false,
        width, height, 0L, "", "", 0, 0.0, 0.0, 0.0, 0f, Look(), file, 1)

    private fun pixels(buffer: ByteBuffer) = FloatArray(buffer.remaining() / 4).also { buffer.asFloatBuffer().get(it) }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun writeFloat(context: Context, file: File, width: Int, height: Int, samples: FloatArray) {
        FilmEngine.outputPixels(width, height, samples, PhotoModel(outputColorSpace = "sRGB", outputCctfEncoding = false)).use {
            file.outputStream().use { output -> FloatTiff.write(context, it, output) }
        }
    }
}
