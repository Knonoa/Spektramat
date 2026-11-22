package com.spektra.latent.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import com.spektra.latent.APP_NAME
import com.spektra.latent.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Publish the camera file as-is, independently of the film render and its export settings. */
internal object CaptureOriginal {
    private const val TAG = "latent-capture-save"
    private const val ALBUM = "$APP_NAME Originals"

    @Suppress("DEPRECATION")
    suspend fun save(context: Context, source: File, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val mime = if (displayName.endsWith(".dng", ignoreCase = true)) "image/x-adobe-dng" else "image/jpeg"
        val base = File(displayName).name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val extension = if (mime == "image/jpeg") "jpg" else "dng"
        val name = "${base}_${UUID.randomUUID()}.$extension"
        val saved = try {
            if (!source.isFile || source.length() == 0L) false
            else if (Build.VERSION.SDK_INT >= 29) {
                val gallery = checkNotNull(PrintStorage.gallery(context, "${Environment.DIRECTORY_DCIM}/$ALBUM"))
                PrintStorage.copyToGallery(gallery, source, mime, name = name) {} != null
            } else if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM)
                check(dir.isDirectory || dir.mkdirs())
                val pending = File.createTempFile(".capture-", ".pending", dir)
                try {
                    source.copyTo(pending, overwrite = true)
                    val destination = File(dir, name)
                    check(pending.renameTo(destination))
                    MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mime), null)
                    true
                } finally { pending.delete() }
            } else false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Journal.w(TAG, "original album write failed", e)
            false
        }
        if (saved) Journal.i(TAG, "original published: DCIM/$ALBUM/$name (${source.length()} bytes)")
        else Journal.w(TAG, "original album unavailable; retaining the camera file in the app")
        saved
    }
}
