package com.spektra.latent.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.spektra.latent.APP_NAME
import com.spektra.latent.Journal
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoPixels
import com.spektra.latent.engine.StageListener
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** A finished print: where it was saved, and a screen-sized copy to show. */
class Printed(val uri: Uri, val width: Int, val height: Int, val shown: Bitmap, val inGallery: Boolean)

/** Where the bytes ended up: the system gallery, or the app's own storage. */
internal class SavedPrint(val uri: Uri, val inGallery: Boolean)

/**
 * Makes the finished print of a frame: the original is read again at the
 * native size, corrected with the look's grade and sent through the full engine.
 * Only the finished float32 pixels are resized to the requested output size.
 * TIFF preserves these samples; JPEG is an explicit 8-bit sharing conversion.
 *
 * The stages reported while it works are real ones:
 * 0 reading the original, 1 film exposed, 2 film developed, 3 printed,
 * 4 scanned, 5 saved.
 */
object Printer {
    private const val TAG = "latent-print"
    const val STAGES = 5

    suspend fun print(
        context: Context,
        library: Library,
        frame: Frame,
        look: Look,
        longEdge: Int,
        format: PrintFormat = PrintFormat.AUTO,
        shouldStop: () -> Boolean = { false },
        onStage: (Int) -> Unit,
    ): Printed = withContext(Dispatchers.IO) {
        onStage(0)
        val shooting = if (look.edge.on || look.date.on) Shooting.of(frame) else null
        val nativeFrameEdge = look.frameSize(frame.width, frame.height).max()
        val print = Decoder.decode(frame.file, 0, look.lensShading, look.aspect).use { original ->
            if (shouldStop()) throw DevelopCancelled()
            // the engine's grain and halation are sized on the film: the same frame, whatever the print's size
            FilmEngine.developFloat(
                original, look.photoGrade(),
                look.photoOrder(seed = frame.id * 7919L + 1L, shooting = shooting, frameSeed = Library.frameSeed(frame), number = frame.number),
                look.finish(), look.finishTable(),
                object : StageListener {
                    override fun onStage(stage: Int) = onStage(stage)
                },
            )
        }
        try {
            // Cancellation can arrive during decoding, before the native
            // engine resets its cancellation flag, or just after rendering.
            if (shouldStop()) throw DevelopCancelled()
            val target = if (longEdge in 1 until nativeFrameEdge)
                (maxOf(print.width, print.height).toDouble() * longEdge / nativeFrameEdge).roundToInt().coerceAtLeast(1)
            else 0
            val resized = if (target > 0) print.scaled(target) else null
            try {
                val output = resized ?: print
                val floating = format != PrintFormat.JPEG
                val name = fileName(frame, look, floating)
                // save() also carries the shooting data while it still owns the
                // row / the file; a published gallery row may refuse "rw" on
                // some phones.
                val saved = save(context, output, name, floating, frame.file)
                library.addPrint(frame.id, saved.uri.toString(), output.width, output.height, look.film)
                onStage(STAGES)
                Printed(saved.uri, output.width, output.height, output.display(), saved.inGallery)
            } finally { resized?.close() }
        } finally {
            print.close()
        }
    }

    fun cancel() = FilmEngine.cancelDevelop()

    private fun fileName(frame: Frame, look: Look, floating: Boolean): String {
        val base = frame.name.substringBeforeLast('.').ifBlank { "frame-${frame.number}" }
        val film = look.film.substringAfter('_').replace('_', '-')
        val stamp = (System.currentTimeMillis() / 1000 % 100000).toString().padStart(5, '0')
        val extension = if (floating) "tif" else "jpg"
        return "${base}_${film}_$stamp.$extension".replace(Regex("[^\\w.\\-\\u4e00-\\u9fff]"), "_")
    }

    /**
     * The finished pixels as a file the gallery (or, when the gallery refuses,
     * the app's own storage) will keep. The shooting data of [original] rides
     * along while this function still owns the bytes.
     */
    private fun save(context: Context, pixels: PhotoPixels, name: String, floating: Boolean, original: File): SavedPrint {
        fun encode(output: java.io.OutputStream) {
            if (floating) FloatTiff.write(context, pixels, output)
            else {
                val srgb = pixels.display(0)
                try {
                    if (!srgb.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw IOException("成片编码失败")
                } finally { srgb.recycle() }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val gallery = saveGallery(context, pixels, name, floating, original, ::encode)
            if (gallery != null) return gallery
            // The phone's media store refused this print (some makers deny
            // access to a row the app itself just inserted). The print is not
            // lost: it goes to the app's own folder and can be shared from
            // there, exactly as on Android 8 and 9.
        }
        val dir = File(context.filesDir, "prints").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use {
            encode(it)
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        if (!floating) copyExif(context, original, uri)
        return SavedPrint(uri, false)
    }

    /** One print into the system gallery, or null when the phone's media store
     * will not take it -- never an exception out of a failure the pixels did
     * not cause. */
    private fun saveGallery(
        context: Context, pixels: PhotoPixels, name: String, floating: Boolean, original: File,
        encode: (java.io.OutputStream) -> Unit,
    ): SavedPrint? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (floating) "image/tiff" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/${APP_NAME}")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Journal.w(TAG, "the gallery did not accept the print", e)
            return null
        } ?: return null
        try {
            // A few phones need a moment before the row they returned can be
            // opened; one retry keeps their race from becoming a lost print.
            val stream = try {
                resolver.openOutputStream(uri)
            } catch (e: SecurityException) {
                Thread.sleep(400)
                resolver.openOutputStream(uri)
            } ?: throw IOException("成片写不进相册")
            stream.use { encode(it) }
            // still pending: the row belongs to this app, and every phone lets
            // its owner write its own pending row. Published rows are the ones
            // some makers lock.
            if (!floating) copyExif(context, original, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return SavedPrint(uri, true)
        } catch (e: Exception) {
            Journal.w(TAG, "the gallery refused the print; keeping it in the app's own storage", e)
            // The cleanup must not replace the reason the print failed.
            runCatching { resolver.delete(uri, null, null) }
                .onFailure { Journal.w(TAG, "the refused gallery row could not be removed either", it) }
            return null
        }
    }

    /** When, with what and how the original was taken travels with the print. Its location does not. */
    private fun copyExif(context: Context, original: File, print: Uri) {
        try {
            val from = ExifInterface(original)
            context.contentResolver.openFileDescriptor(print, "rw")?.use { fd ->
                val to = ExifInterface(fd.fileDescriptor)
                for (tag in TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
                to.setAttribute(ExifInterface.TAG_SOFTWARE, APP_NAME)
                to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                to.saveAttributes()
            }
        } catch (e: Exception) {
            Journal.w(TAG, "the print was saved without its shooting data", e)
        }
    }

    private val TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH,
    )
}
