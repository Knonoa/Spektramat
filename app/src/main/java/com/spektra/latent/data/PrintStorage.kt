package com.spektra.latent.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.spektra.latent.APP_NAME
import com.spektra.latent.Journal
import com.spektra.latent.engine.DevelopCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** A complete file, either published in MediaStore or retained in app storage. */
internal class SavedPrint(val uri: Uri, val inGallery: Boolean)

/** Encode once, atomically retain a local file, then copy those same bytes to the gallery. */
internal object PrintStorage {
    private const val TAG = "latent-print-storage"
    /** Workbench and contact sheets share the native export lane and its memory budget. */
    val renderGate = Mutex()

    /** Small boundary lets tests exercise failed publication and cancellation without changing a real album. */
    internal interface Gallery {
        fun insert(name: String, mime: String): Uri?
        fun open(uri: Uri): OutputStream?
        fun publish(uri: Uri): Int
        fun delete(uri: Uri)
    }

    fun save(
        context: Context,
        name: String,
        mime: String,
        shouldStop: () -> Boolean = { false },
        metadata: (File) -> Unit = {},
        gallery: Gallery? = gallery(context),
        encode: (OutputStream) -> Unit,
    ): SavedPrint {
        fun checkStopped() { if (shouldStop()) throw DevelopCancelled() }
        checkStopped()
        val dir = File(context.filesDir, "prints")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建成片目录")
        val safe = name.replace(Regex("[^\\w.\\-\\u4e00-\\u9fff]"), "_")
        val extension = safe.substringAfterLast('.', "bin").take(8)
        val stem = safe.substringBeforeLast('.', safe).take(120).ifBlank { "print" }
        // Every request owns its destination. Repeating an export never replaces an earlier print.
        val local = File(dir, "${stem}_${UUID.randomUUID()}.$extension")
        val pending = File.createTempFile(".print-", ".pending", dir)
        var completed = false
        try {
            FileOutputStream(pending).use { stream ->
                try { encode(CheckedOutput(stream, ::checkStopped)) }
                catch (failure: Exception) { checkStopped(); throw failure }
                checkStopped()
                stream.fd.sync()
            }
            metadata(pending)
            checkStopped()
            if (pending.length() == 0L) throw IOException("成片文件为空")
            if (local.exists() || !pending.renameTo(local)) throw IOException("成片文件无法保存")
            checkStopped()
            val uri = if (gallery == null) null else copyToGallery(gallery, local, mime, ::checkStopped)
            if (uri != null) {
                // Publishing is the commit boundary: late cancellation does not delete a valid print.
                completed = true
                if (!local.delete()) Journal.w(TAG, "published print's redundant local copy could not be removed")
                return SavedPrint(uri, true)
            }
            checkStopped()
            val shared = FileProvider.getUriForFile(context, context.packageName + ".files", local)
            completed = true
            return SavedPrint(shared, false)
        } finally {
            pending.delete()
            if (!completed) local.delete()
        }
    }

    private fun copyToGallery(gallery: Gallery, file: File, mime: String, checkStopped: () -> Unit): Uri? {
        var pending: Uri? = null
        var published = false
        try {
            checkStopped()
            val uri = gallery.insert(file.name, mime) ?: return null
            pending = uri
            checkStopped()
            (gallery.open(uri) ?: throw IOException("成片写不进相册")).use { output ->
                file.inputStream().use { input ->
                    val block = ByteArray(64 * 1024)
                    while (true) {
                        checkStopped()
                        val count = input.read(block)
                        if (count < 0) break
                        output.write(block, 0, count)
                    }
                    output.flush()
                }
            }
            checkStopped()
            if (gallery.publish(uri) != 1) throw IOException("相册没有发布成片")
            published = true
            return uri
        } catch (cancelled: DevelopCancelled) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Encoding has already succeeded. Only the independent gallery operation failed.
            Journal.w(TAG, "gallery write failed; retaining the complete app file", failure)
            return null
        } finally {
            if (!published) pending?.let { uri ->
                runCatching { gallery.delete(uri) }
                    .onFailure { Journal.w(TAG, "pending gallery row could not be removed", it) }
            }
        }
    }

    private class CheckedOutput(output: OutputStream, private val checkStopped: () -> Unit) : FilterOutputStream(output) {
        override fun write(b: Int) { checkStopped(); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { checkStopped(); out.write(b, off, len) }
        override fun flush() { checkStopped(); out.flush() }
    }

    private fun gallery(context: Context): Gallery? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        return object : Gallery {
            override fun insert(name: String, mime: String): Uri? = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$APP_NAME")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                })
            override fun open(uri: Uri): OutputStream? = resolver.openOutputStream(uri, "w")
            override fun publish(uri: Uri): Int = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            override fun delete(uri: Uri) { resolver.delete(uri, null, null) }
        }
    }
}
