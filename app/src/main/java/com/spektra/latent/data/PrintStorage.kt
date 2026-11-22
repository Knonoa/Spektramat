package com.spektra.latent.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
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
import org.json.JSONObject
import org.json.JSONArray

/** A complete file, either published in MediaStore or retained in app storage. */
internal class SavedPrint(val uri: Uri, val inGallery: Boolean, val location: String? = null)

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

    data class Output(val key: String, val width: Int, val height: Int, val highlights: HighlightReport?)

    private fun checkpoint(context: Context, key: String) = AtomicFile(File(context.filesDir, "prints/.$key.json"))
    private fun staging(dir: File, key: String) = File(dir, ".$key.pending")

    fun clearCheckpoint(context: Context, key: String) = checkpoint(context, key).delete()

    /** A published row wins over a replay, including when the app died just after publication. */
    fun recover(context: Context, key: String, mime: String, shouldStop: () -> Boolean): Printed? {
        val record = checkpoint(context, key)
        if (!record.baseFile.exists()) return null
        val data = JSONObject(record.readFully().toString(Charsets.UTF_8))
        val name = data.getString("name")
        val dir = record.baseFile.parentFile!!
        val local = File(dir, name)
        val staging = staging(dir, key)
        val found = if (Build.VERSION.SDK_INT >= 29) findGallery(context, name) else null
        val saved = if (found != null && !found.second) {
            local.delete()
            SavedPrint(found.first, true, "Pictures/$APP_NAME/$name")
        } else {
            // Do not leave a half-written MediaStore row behind when restarting.
            found?.let { context.contentResolver.delete(it.first, null, null) }
            staging.delete()
            if (shouldStop()) { local.delete(); return null }
            if (!local.isFile) return null
            publish(context, gallery(context), local, mime) { if (shouldStop()) throw DevelopCancelled() }
        }
        val highlights = data.optJSONArray("highlights")?.let { HighlightReport(it.getInt(0), it.getInt(1), it.getInt(2)) }
        return Printed(saved.uri, data.getInt("width"), data.getInt("height"), null, saved.inGallery, highlights, saved.location)
    }

    @android.annotation.TargetApi(29)
    @Suppress("DEPRECATION")
    private fun findGallery(context: Context, name: String): Pair<Uri, Boolean>? {
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING)
        val where = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(name, "${Environment.DIRECTORY_PICTURES}/$APP_NAME/")
        val resolver = context.contentResolver
        val rows = if (Build.VERSION.SDK_INT >= 30) resolver.query(media, columns, Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }, null) else resolver.query(MediaStore.setIncludePending(media), columns, where, args, null)
        return (rows ?: throw IOException("无法检查已保存的相册成片")).use {
            if (it.moveToFirst()) ContentUris.withAppendedId(media, it.getLong(0)) to (it.getInt(1) != 0) else null
        }
    }

    fun save(
        context: Context,
        name: String,
        mime: String,
        shouldStop: () -> Boolean = { false },
        metadata: (File) -> Unit = {},
        gallery: Gallery? = gallery(context),
        output: Output? = null,
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
        val local = File(dir, "${stem}_${output?.key ?: UUID.randomUUID()}.$extension")
        val pending = if (output != null) {
            val data = JSONObject().put("name", local.name).put("width", output.width).put("height", output.height)
            output.highlights?.let { data.put("highlights", JSONArray(listOf(it.total, it.near, it.over))) }
            val record = checkpoint(context, output.key)
            val stream = record.startWrite()
            try {
                stream.write(data.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                record.finishWrite(stream)
            } catch (e: Exception) { record.failWrite(stream); throw e }
            staging(dir, output.key)
        } else File.createTempFile(".print-", ".pending", dir)
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
            return publish(context, gallery, local, mime, ::checkStopped).also { completed = true }
        } finally {
            pending.delete()
            if (!completed) local.delete()
        }
    }

    private fun publish(context: Context, gallery: Gallery?, local: File, mime: String, checkStopped: () -> Unit): SavedPrint {
        val uri = gallery?.let { copyToGallery(it, local, mime, checkStopped = checkStopped) }
        if (uri != null) {
            if (!local.delete()) Journal.w(TAG, "published print's redundant local copy could not be removed")
            return SavedPrint(uri, true, "Pictures/$APP_NAME/${local.name}")
        }
        checkStopped()
        val shared = FileProvider.getUriForFile(context, context.packageName + ".files", local)
        return SavedPrint(shared, false, "应用内 / ${local.name}")
    }

    internal fun copyToGallery(gallery: Gallery, file: File, mime: String, name: String = file.name, checkStopped: () -> Unit): Uri? {
        var pending: Uri? = null
        var published = false
        try {
            checkStopped()
            val uri = gallery.insert(name, mime) ?: return null
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

    internal fun gallery(context: Context, relativePath: String = "${Environment.DIRECTORY_PICTURES}/$APP_NAME"): Gallery? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        return object : Gallery {
            override fun insert(name: String, mime: String): Uri? = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
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
