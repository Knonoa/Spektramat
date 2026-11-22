package com.spektra.latent.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.DevelopCancelled
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Atomic local files, controlled failures, and real MediaStore recovery. */
@RunWith(AndroidJUnit4::class)
class PrintStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory get() = File(context.filesDir, "prints")
    private val prefix = "storage-test-${UUID.randomUUID()}"
    private val payload = ByteArray(150_000) { (it % 251).toByte() }

    @Test fun zeroRowPublishKeepsExactlyEncodedBytesAndCleansThePendingRow() = cleaning {
        val gallery = FakeGallery().apply { publishCount = 0; failCleanup = true }
        var encodes = 0
        val saved = PrintStorage.save(context, "$prefix.tif", "image/tiff", gallery = gallery) {
            encodes++; it.write(payload)
        }
        assertFalse(saved.inGallery)
        assertEquals(1, encodes)
        assertTrue(gallery.deleted)
        assertArrayEquals(payload, gallery.output.toByteArray())
        context.contentResolver.openInputStream(saved.uri)!!.use { assertArrayEquals(payload, it.readBytes()) }
        assertEquals(1, ownFiles().size)
    }

    @Test fun repeatedLocalExportsNeverOverwriteAndGalleryDenialDoesNotReencode() = cleaning {
        val gallery = FakeGallery().apply { denyInsert = true }
        val first = PrintStorage.save(context, "$prefix.jpg", "image/jpeg", gallery = gallery) { it.write(payload) }
        val second = PrintStorage.save(context, "$prefix.jpg", "image/jpeg", gallery = null) { it.write(byteArrayOf(3, 2, 1)) }
        assertFalse(first.inGallery); assertFalse(second.inGallery)
        assertNotEquals(first.uri, second.uri)
        assertEquals(2, ownFiles().size)
        context.contentResolver.openInputStream(first.uri)!!.use { assertArrayEquals(payload, it.readBytes()) }
        context.contentResolver.openInputStream(second.uri)!!.use { assertArrayEquals(byteArrayOf(3, 2, 1), it.readBytes()) }
    }

    @Test fun encodingFailurePropagatesAndNeverTouchesTheGallery() = cleaning {
        val gallery = FakeGallery()
        val expected = IOException("encoder failed")
        try {
            PrintStorage.save(context, "$prefix.tif", "image/tiff", gallery = gallery) {
                it.write(payload, 0, 100); throw expected
            }
            fail("Encoder errors must propagate")
        } catch (actual: IOException) { assertSame(expected, actual) }
        assertFalse(gallery.inserted)
        assertTrue(ownFiles().isEmpty())
    }

    @Test fun cancellationWhileCopyingDeletesBothPendingOutputs() = cleaning {
        var stopped = false
        val gallery = FakeGallery().apply { afterWrite = { stopped = true } }
        try {
            PrintStorage.save(context, "$prefix.tif", "image/tiff", { stopped }, gallery = gallery) { it.write(payload) }
            fail("An unfinished save must cancel")
        } catch (_: DevelopCancelled) { }
        assertTrue(gallery.deleted)
        assertFalse(gallery.published)
        assertTrue(ownFiles().isEmpty())
    }

    @Test fun cancellationAfterSuccessfulPublicationReturnsTheCommittedPrint() = cleaning {
        var stopped = false
        val gallery = FakeGallery().apply { afterPublish = { stopped = true } }
        val saved = PrintStorage.save(context, "$prefix.tif", "image/tiff", { stopped }, gallery = gallery) { it.write(payload) }
        assertTrue(saved.inGallery)
        assertEquals(gallery.uri, saved.uri)
        assertTrue(gallery.published)
        assertFalse(gallery.deleted)
        assertTrue(ownFiles().isEmpty())
    }

    @Test fun aGalleryStreamFailureRetainsACompleteLocalCopy() = cleaning {
        val gallery = FakeGallery().apply { failWrite = true }
        val saved = PrintStorage.save(context, "$prefix.tif", "image/tiff", gallery = gallery) { it.write(payload) }
        assertFalse(saved.inGallery)
        assertTrue(gallery.deleted)
        context.contentResolver.openInputStream(saved.uri)!!.use { assertArrayEquals(payload, it.readBytes()) }
    }

    @Test fun recoveryRecognizesAnAlreadyPublishedPrintEvenAfterCancellation() = cleaning {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val key = UUID.randomUUID().toString()
        val image = android.graphics.Bitmap.createBitmap(8, 6, android.graphics.Bitmap.Config.ARGB_8888)
        var published: Uri? = null
        try {
            val saved = PrintStorage.save(context, "$prefix.jpg", "image/jpeg",
                output = PrintStorage.Output(key, 8, 6, null)) {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it))
            }
            assertTrue(saved.inGallery)
            published = saved.uri
            val recovered = PrintStorage.recover(context, key, "image/jpeg", shouldStop = { true })!!
            assertEquals(published, recovered.uri)
            assertEquals(8, recovered.width)
            assertEquals(6, recovered.height)
        } finally {
            image.recycle()
            published?.let { context.contentResolver.delete(it, null, null) }
            PrintStorage.clearCheckpoint(context, key)
        }
    }

    private fun ownFiles() = directory.listFiles()?.filter { it.name.startsWith(prefix) }.orEmpty()
    private fun cleaning(block: () -> Unit) {
        val pendingBefore = directory.listFiles()?.filter { it.name.endsWith(".pending") }?.map { it.path }?.toSet().orEmpty()
        try { block() } finally {
            ownFiles().forEach { it.delete() }
            val leaked = directory.listFiles()?.filter { it.name.endsWith(".pending") && it.path !in pendingBefore }.orEmpty()
            leaked.forEach { it.delete() }
            assertTrue("No partial encoded files may survive", leaked.isEmpty())
        }
    }

    private class FakeGallery : PrintStorage.Gallery {
        val uri: Uri = Uri.parse("content://print-storage-test/1")
        val output = ByteArrayOutputStream()
        var inserted = false
        var published = false
        var deleted = false
        var denyInsert = false
        var failWrite = false
        var failCleanup = false
        var publishCount = 1
        var afterWrite: () -> Unit = {}
        var afterPublish: () -> Unit = {}
        override fun insert(name: String, mime: String): Uri {
            inserted = true
            if (denyInsert) throw SecurityException("gallery denied")
            return uri
        }
        override fun open(uri: Uri): OutputStream = object : OutputStream() {
            override fun write(b: Int) { output.write(b); afterWrite() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrite) throw IOException("gallery disk full")
                output.write(b, off, len); afterWrite()
            }
        }
        override fun publish(uri: Uri): Int { published = publishCount == 1; afterPublish(); return publishCount }
        override fun delete(uri: Uri) { deleted = true; if (failCleanup) throw SecurityException("cleanup denied") }
    }
}
