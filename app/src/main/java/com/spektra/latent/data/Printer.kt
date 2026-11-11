package com.spektra.latent.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.StageListener
import com.spektra.latent.model.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** A finished print: where it was saved, and a screen-sized copy to show. */
class Printed(val uri: Uri, val width: Int, val height: Int, val shown: Bitmap)

/**
 * Makes the finished print of a frame: the original is read again at the
 * ordered size, corrected with the look's grade, sent through the full engine,
 * and saved as a JPEG in the phone's gallery.
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
        onStage: (Int) -> Unit,
    ): Printed = withContext(Dispatchers.IO) {
        onStage(0)
        val edge = if (longEdge >= frame.longEdge) 0 else longEdge
        val print: Bitmap = Decoder.decode(frame.file, edge, look.lensShading).use { original ->
            // the engine's grain and halation are sized on the film: the same frame, whatever the print's size
            FilmEngine.develop(
                original, look.grade(frame.meteredEv), look.order(seed = frame.id * 7919L + 1L),
                object : StageListener {
                    override fun onStage(stage: Int) = onStage(stage)
                },
            )
        }
        try {
            val name = fileName(frame, look)
            val uri = save(context, print, name)
            copyExif(context, frame.file, uri)
            library.addPrint(frame.id, uri.toString(), print.width, print.height, look.film)
            onStage(STAGES)
            val k = min1(1600f / max(print.width, print.height))
            val shown = if (k < 1f) {
                Bitmap.createScaledBitmap(print, (print.width * k).roundToInt(), (print.height * k).roundToInt(), true)
            } else {
                print.copy(Bitmap.Config.ARGB_8888, false)
            }
            Printed(uri, print.width, print.height, shown)
        } finally {
            print.recycle()
        }
    }

    fun cancel() = FilmEngine.cancelDevelop()

    private fun min1(v: Float) = if (v < 1f) v else 1f

    private fun fileName(frame: Frame, look: Look): String {
        val base = frame.name.substringBeforeLast('.').ifBlank { "frame-${frame.number}" }
        val film = look.film.substringAfter('_').replace('_', '-')
        val stamp = (System.currentTimeMillis() / 1000 % 100000).toString().padStart(5, '0')
        return "${base}_${film}_$stamp.jpg".replace(Regex("[^\\w.\\-\\u4e00-\\u9fff]"), "_")
    }

    private fun save(context: Context, bitmap: Bitmap, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Latent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("相册没有接受这张成片")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("成片写不进相册")).use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw IOException("成片编码失败")
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        // Android 8 and 9: the gallery needs a storage permission this app does not ask for;
        // the print is kept in the app's own pictures folder and can be shared from there.
        val dir = File(context.filesDir, "prints").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw IOException("成片编码失败")
        }
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    /** When, with what and how the original was taken travels with the print. Its location does not. */
    private fun copyExif(context: Context, original: File, print: Uri) {
        try {
            val from = ExifInterface(original)
            context.contentResolver.openFileDescriptor(print, "rw")?.use { fd ->
                val to = ExifInterface(fd.fileDescriptor)
                for (tag in TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
                to.setAttribute(ExifInterface.TAG_SOFTWARE, "Latent")
                to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                to.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "the print was saved without its shooting data", e)
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
