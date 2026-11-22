package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min

/** Numbered contact sheets. Only one full-resolution original occupies the print lane at a time. */
internal object ContactSheet {
    data class Result(val pages: List<SavedPrint>, val error: String? = null)
    private const val COLUMNS = 4
    private const val PER_PAGE = 24
    private const val IMAGE_W = 496
    private const val IMAGE_H = 340
    private const val GAP = 24
    private const val TOP = 120
    private const val ROW = 400
    private const val WIDTH = COLUMNS * IMAGE_W + (COLUMNS + 1) * GAP

    suspend fun export(context: Context, project: String, selected: List<Frame>, onProgress: suspend (Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            require(selected.isNotEmpty()) { "先选几张照片" }
            val pages = ArrayList<SavedPrint>()
            val parts = selected.sortedBy { it.number }.chunked(PER_PAGE)
            val stamp = System.currentTimeMillis()
            val job = currentCoroutineContext()
            var done = 0
            try {
                for ((index, frames) in parts.withIndex()) {
                    val saved = PrintStorage.renderGate.withLock {
                        job.ensureActive()
                        val rows = (frames.size + COLUMNS - 1) / COLUMNS
                        val page = Bitmap.createBitmap(WIDTH, TOP + rows * ROW, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(page)
                            canvas.drawColor(Color.rgb(16, 16, 19))
                            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textSize = 40f }
                            canvas.drawText(fit(project.ifBlank { "Spektramat" }, text, WIDTH - 3f * GAP), GAP.toFloat(), 51f, text)
                            text.textSize = 23f
                            text.color = Color.LTGRAY
                            canvas.drawText("SPEKTRAMAT  /  ${selected.size} 张  /  ${index + 1} / ${parts.size}", GAP.toFloat(), 90f, text)
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                            for ((cell, frame) in frames.withIndex()) {
                                job.ensureActive()
                                val left = (GAP + (cell % COLUMNS) * (IMAGE_W + GAP)).toFloat()
                                val top = (TOP + (cell / COLUMNS) * ROW).toFloat()
                                val look = frame.look
                                val decoded = Decoder.decode(frame.file, 0, look.lensShading, look.aspect)
                                val prepared = if (look.creative.active) decoded.use { CreativeSource(context).apply(it, look, 0) } else decoded
                                val photo = prepared.use { source ->
                                    job.ensureActive()
                                    FilmEngine.developFloat(source, look.photoGrade(),
                                        look.photoOrder(frame.id * 7919L + 1L, Shooting.of(frame), Library.frameSeed(frame), frame.number),
                                        look.finish(), look.finishTable()).use { it.display(IMAGE_W) }
                                }
                                try {
                                    val scale = min(IMAGE_W.toFloat() / photo.width, IMAGE_H.toFloat() / photo.height)
                                    val w = scale * photo.width; val h = scale * photo.height
                                    val x = left + (IMAGE_W - w) / 2; val y = top + (IMAGE_H - h) / 2
                                    canvas.drawBitmap(photo, null, RectF(x, y, x + w, y + h), paint)
                                } finally { photo.recycle() }
                                canvas.drawText(fit("${frame.number.toString().padStart(2, '0')}  ${frame.name}", text, IMAGE_W.toFloat()), left, top + IMAGE_H + 38, text)
                                onProgress(++done)
                            }
                            PrintStorage.save(context, "ContactSheet_${stamp}_${index + 1}.jpg", "image/jpeg",
                                shouldStop = { job.ensureActive(); false }) { output ->
                                if (!page.compress(Bitmap.CompressFormat.JPEG, 94, output)) throw IOException("接触印样编码失败")
                            }
                        } finally { page.recycle() }
                    }
                    pages += saved
                }
                Result(pages)
            } catch (e: CancellationException) { throw e
            } catch (e: OutOfMemoryError) { Result(pages, "内存不足，无法完成接触印样")
            } catch (e: Exception) { Result(pages, e.message ?: "接触印样没有完成") }
        }

    private fun fit(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val end = paint.breakText(text, true, width - paint.measureText("…"), null)
        return text.take(end) + "…"
    }
}
