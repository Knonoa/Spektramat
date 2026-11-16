package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.spektra.latent.APP_NAME
import com.spektra.latent.Journal
import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoPixels
import com.spektra.latent.engine.StageListener
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** A finished print: where it was saved, and a screen-sized copy to show. */
class Printed(val uri: Uri, val width: Int, val height: Int, val shown: Bitmap, val inGallery: Boolean)

/**
 * Makes the finished print of a frame: the original is read again at the
 * native size, corrected with the look's grade and sent through the full engine.
 * Only the finished float32 pixels are resized to the requested output size.
 * The selected encoder receives the full finished pixels, never a preview.
 *
 * The stages reported while it works are real ones:
 * 0 reading the original, 1 film exposed, 2 film developed, 3 printed,
 * 4 scanned, 5 saved.
 */
object Printer {
    private const val TAG = "latent-print"
    const val STAGES = 5
    private val cancellationGuard = Any()
    private var renderingOriginal = false

    suspend fun print(
        context: Context,
        library: Library,
        frame: Frame,
        look: Look,
        longEdge: Int,
        format: PrintFormat = PrintFormat.JPEG,
        shouldStop: () -> Boolean = { false },
        onStage: (Int) -> Unit,
    ): Printed = withContext(Dispatchers.IO) {
        PrintStorage.renderGate.withLock {
            val exportContext = currentCoroutineContext()
            fun stopRequested(): Boolean { exportContext.ensureActive(); return shouldStop() }
            if (stopRequested()) throw DevelopCancelled()
            onStage(0)
            val shooting = if (look.edge.on || look.date.on) Shooting.of(frame) else null
            val nativeFrameEdge = look.frameSize(frame.width, frame.height).max()
            val print = Decoder.decode(frame.file, 0, look.lensShading, look.aspect).use { original ->
                if (stopRequested()) throw DevelopCancelled()
                // the engine's grain and halation are sized on the film: the same frame, whatever the print's size
                synchronized(cancellationGuard) { renderingOriginal = true }
                try {
                    FilmEngine.developFloat(
                        original, look.photoGrade(),
                        look.photoOrder(seed = frame.id * 7919L + 1L, shooting = shooting, frameSeed = Library.frameSeed(frame), number = frame.number),
                        look.finish(), look.finishTable(),
                        object : StageListener {
                            override fun onStage(stage: Int) = onStage(stage)
                        },
                    )
                } finally { synchronized(cancellationGuard) { renderingOriginal = false } }
            }
            try {
                // Cancellation can arrive during decoding, before the native
                // engine resets its cancellation flag, or just after rendering.
                if (stopRequested()) throw DevelopCancelled()
                val target = if (longEdge in 1 until nativeFrameEdge)
                    (maxOf(print.width, print.height).toDouble() * longEdge / nativeFrameEdge).roundToInt().coerceAtLeast(1)
                else 0
                val resized = if (target > 0) print.scaled(target) else null
                try {
                    val output = resized ?: print
                    val name = fileName(frame, look, format)
                    // save() also carries the shooting data while it still owns the
                    // row / the file; a published gallery row may refuse "rw" on
                    // some phones.
                    // Allocate the display before committing a file, so display OOM cannot make a
                    // successful save appear to have failed. A small thumbnail is an acceptable fallback.
                    val shown = try { output.display() } catch (_: OutOfMemoryError) { output.display(320) }
                    var delivered = false
                    try {
                        val saved = save(context, output, name, format, frame.file, ::stopRequested)
                        withContext(NonCancellable) {
                            try {
                                val registered = library.addPrint(frame.id, saved.uri.toString(), output.width, output.height, look.film)
                                if (registered == -1L) Journal.w(TAG, "print saved; catalogue entry was unavailable")
                            } catch (failure: Exception) {
                                Journal.w(TAG, "print saved; catalogue registration failed", failure)
                            }
                        }
                        onStage(STAGES)
                        delivered = true
                        Printed(saved.uri, output.width, output.height, shown, saved.inGallery)
                    } finally { if (!delivered) shown.recycle() }
                } finally { resized?.close() }
            } finally {
                print.close()
            }
        }
    }

    // A print waiting for renderGate must not cancel a contact-sheet export
    // that currently owns the same native lane. Decode/save use shouldStop.
    fun cancel() = synchronized(cancellationGuard) { if (renderingOriginal) FilmEngine.cancelDevelop() }

    private fun fileName(frame: Frame, look: Look, format: PrintFormat): String {
        val base = frame.name.substringBeforeLast('.').ifBlank { "frame-${frame.number}" }
        val film = look.film.substringAfter('_').replace('_', '-')
        val stamp = System.currentTimeMillis().toString()
        val extension = format.extension
        return "${base}_${film}_$stamp.$extension".replace(Regex("[^\\w.\\-\\u4e00-\\u9fff]"), "_")
    }

    /**
     * The finished pixels as a file the gallery (or, when the gallery refuses,
     * the app's own storage) will keep. The shooting data of [original] rides
     * along while this function still owns the bytes.
     */
    private fun save(context: Context, pixels: PhotoPixels, name: String, format: PrintFormat, original: File,
                     shouldStop: () -> Boolean): SavedPrint = PrintStorage.save(
        context, name, format.mime, shouldStop,
        metadata = { if (format == PrintFormat.JPEG) copyExif(original, it) },
    ) { output ->
        when (format) {
            PrintFormat.PNG -> PhotoPng.write(context, pixels, output, shouldStop)
            PrintFormat.DNG -> LinearDng.write(context, pixels, output, shouldStop)
            PrintFormat.JPEG -> {
                val srgb = pixels.display(0)
                try {
                    if (!srgb.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw IOException("成片编码失败")
                } finally { srgb.recycle() }
            }
        }
    }
    /** When, with what and how the original was taken travels with the print. Its location does not. */
    private fun copyExif(original: File, print: File) {
        try {
            val from = ExifInterface(original)
            run {
                val to = ExifInterface(print)
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
