package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.engine.PhotoPixels
import com.spektra.latent.model.FilmStrip
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import com.spektra.latent.model.StripFrame
import com.spektra.latent.model.StripLayout
import com.spektra.latent.model.StripRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Preview and export share the same gate, sprockets and sleeve geometry. */
internal object StripArt {
    private val film = Color.rgb(39, 25, 24)
    private val perforation = Color.rgb(10, 9, 11)
    private val print = Color.rgb(213, 151, 105)
    private val sleeve = Color.rgb(39, 44, 51)

    fun draw(canvas: Canvas, layout: StripLayout, strip: FilmStrip, image: (Int) -> Pair<StripFrame, Bitmap>?) {
        val s = layout.scale.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun rect(r: StripRect, color: Int) {
            p.color = color
            canvas.drawRect(r.x.toFloat(), r.y.toFloat(), r.right.toFloat(), r.bottom.toFloat(), p)
        }
        if (layout.sleeve) {
            canvas.drawColor(Color.rgb(23, 27, 32))
            p.color = Color.rgb(216, 226, 237)
            p.textSize = 3.8f * s
            p.typeface = Typeface.MONOSPACE
            canvas.save()
            canvas.clipRect(6f * s, 0f, layout.width - 6f * s, 15f * s)
            canvas.drawText("135  /  ${strip.name}", 6f * s, 9f * s, p)
            p.color = Color.rgb(147, 166, 185)
            p.textSize = 2.5f * s
            canvas.drawText("SPEKTRAMAT  ·  ${strip.rollCode}  ·  ${layout.cells.size}F", 6f * s, 13f * s, p)
            canvas.restore()
        } else canvas.drawColor(perforation)

        repeat(layout.rows) { row ->
            val rail = layout.rail(row)
            if (layout.sleeve) {
                rect(StripRect(rail.x - (2 * s).toInt(), rail.y - s.toInt(), rail.w + (4 * s).toInt(), rail.h + (2 * s).toInt()), sleeve)
                p.color = Color.rgb(106, 120, 137)
                p.strokeWidth = .16f * s
                canvas.drawLine(rail.x - s, rail.y - s, rail.right + s, rail.y - s, p)
                canvas.drawLine(rail.x - s, rail.bottom + s, rail.right + s, rail.bottom + s, p)
            }
            canvas.save()
            // Draw the rails in roll coordinates. The pictures themselves are never rotated.
            canvas.translate(rail.x.toFloat(), rail.y.toFloat())
            if (layout.vertical) { canvas.translate(rail.w.toFloat(), 0f); canvas.rotate(90f) }
            val length = layout.occupiedLength(row).toFloat()
            val track = 30f * s
            val continuous = !layout.sleeve
            val silhouette = Path().apply {
                if (continuous) {
                    moveTo(0f, 10f * s); lineTo(5f * s, 10f * s); lineTo(10f * s, 0f)
                    lineTo(length - 3f * s, 0f); lineTo(length, 3f * s); lineTo(length, track - 3f * s)
                    lineTo(length - 3f * s, track); lineTo(0f, track); close()
                } else addRect(0f, 0f, length, track, Path.Direction.CW)
            }
            canvas.clipPath(silhouette)
            p.color = film
            canvas.drawPath(silhouette, p)
            p.color = Color.rgb(86, 54, 42)
            p.strokeWidth = .16f * s
            canvas.drawLine(0f, 4.6f * s, length, 4.6f * s, p)
            canvas.drawLine(0f, 25.4f * s, length, 25.4f * s, p)
            p.color = perforation
            var u = s
            while (u + 2.3f * s < length) {
                canvas.drawRoundRect(u, .85f * s, u + 2.15f * s, 2.7f * s, .36f * s, .36f * s, p)
                canvas.drawRoundRect(u, 27.3f * s, u + 2.15f * s, 29.15f * s, .36f * s, .36f * s, p)
                u += 3.875f * s
            }
            p.typeface = Typeface.MONOSPACE
            p.color = print
            p.textSize = 1.48f * s
            repeat(layout.occupiedIn(row)) { col ->
                val i = row * layout.columns + col
                val x = layout.leadLength() + col * StripLayout.PITCH * s
                val number = (i + 1).toString().padStart(2, '0')
                canvas.drawText("$number  ▸   SPEKTRAMAT 135", x + .45f * s, 4.26f * s, p)
                canvas.drawText("${strip.rollCode}  ·  $number", x + 15f * s, 27f * s, p)
            }
            if (continuous) {
                canvas.drawText("135 / START", 2f * s, 17f * s, p)
                canvas.save()
                canvas.clipRect(2f * s, 17.5f * s, layout.leadLength() - s, 24f * s)
                canvas.drawText(strip.name, 2f * s, 21f * s, p)
                canvas.restore()
                canvas.drawText("END", length - layout.tailLength() + 2f * s, 17f * s, p)
            }
            canvas.restore()
        }
        layout.cells.forEachIndexed { i, r ->
            rect(r, Color.rgb(17, 16, 19))
            image(i)?.let { (setting, bitmap) ->
                val src = setting.crop(bitmap.width, bitmap.height, layout.vertical)
                p.color = Color.WHITE
                canvas.drawBitmap(bitmap, Rect(src.x, src.y, src.right, src.bottom), Rect(r.x, r.y, r.right, r.bottom), p)
            }
        }
    }
}

internal object StripPrinter {
    private val model = PhotoModel(outputColorSpace = "sRGB", outputCctfEncoding = true)

    private fun look(frame: Frame, recipe: Look?) =
        (if (recipe == null) frame.look else frame.look.withRecipe(recipe)).let {
            it.copy(format = "135", edge = it.edge.copy(on = false),
                model = it.model.copy(outputColorSpace = model.outputColorSpace, outputCctfEncoding = true))
        }

    suspend fun proxy(context: Context, frame: Frame, recipe: Look?): Bitmap = withContext(Dispatchers.IO) {
        val look = look(frame, recipe)
        val edge = 320
        val decoded = PhotoProxyStore.get(context).acquire(frame, look.aspect, look.lensShading, edge)
        val prepared = if (look.creative.active) decoded.use { CreativeSource(context).apply(it, look, edge) } else decoded
        prepared.use {
            currentCoroutineContext().ensureActive()
            FilmEngine.proofFloat(it, look.photoGrade(), look.photoOrder(frame.id * 7919L + 1L, Shooting.of(frame),
                Library.frameSeed(frame), frame.number), look.finish(), look.finishTable()).use { pixels -> pixels.display(0) }
        }
    }

    suspend fun print(context: Context, strip: FilmStrip, frames: List<Frame>, format: PrintFormat,
                      checkHighlights: Boolean, shouldStop: () -> Boolean, onProgress: (Int) -> Unit,
                      outputKey: String? = null): Printed =
        withContext(Dispatchers.IO) {
            require(format == PrintFormat.JPEG || format == PrintFormat.PNG)
            require(frames.map { it.id } == strip.frames.map { it.id })
            val job = currentCoroutineContext()
            fun stopRequested(): Boolean { job.ensureActive(); return shouldStop() }
            fun check() { if (stopRequested()) throw DevelopCancelled() }
            PrintStorage.renderGate.withLock {
                check()
                val layout = StripLayout.output(strip)
                val isPng = format == PrintFormat.PNG
                val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
                var temp: File? = null
                try {
                    val canvas = Canvas(bitmap)
                    StripArt.draw(canvas, layout, strip) { null }
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    if (isPng) {
                        // No other strip owns the render lane; a spool left by a killed process has no reader.
                        context.cacheDir.listFiles { f -> f.name.startsWith("strip-float-") && f.name.endsWith(".tmp") }
                            ?.forEach { it.delete() }
                        temp = File.createTempFile("strip-float-", ".tmp", context.cacheDir)
                    }
                    val spool = temp?.let { RandomAccessFile(it, "rw") }
                    spool.use { raw ->
                        var total = 0; var near = 0; var over = 0
                        for ((i, frame) in frames.withIndex()) {
                            check()
                            try {
                                val look = look(frame, strip.recipe)
                                val target = layout.cells[i]
                                val decoded = Decoder.decode(frame.file, 0, look.lensShading, look.aspect)
                                val prepared = if (look.creative.active) decoded.use { CreativeSource(context).apply(it, look, 0) } else decoded
                                val order = look.photoOrder(frame.id * 7919L + 1L, Shooting.of(frame), Library.frameSeed(frame), frame.number)
                                val rendered = prepared.use { original ->
                                    Printer.withNativeCancellation {
                                        check()
                                        FilmEngine.developFloat(original, look.photoGrade(), order, look.finish(), look.finishTable())
                                    }
                                }
                                rendered.use { output ->
                                    check()
                                    val crop = strip.frames[i].crop(output.width, output.height, layout.vertical)
                                    output.cropScaled(crop.x, crop.y, crop.w, crop.h, target.w, target.h).use { pixels ->
                                        if (checkHighlights) {
                                            val dates = order.rebate.dateBounds(output.width, output.height, look.formatUm / 1000f)
                                            for (j in dates.indices) dates[j] = if (j % 2 == 0)
                                                (dates[j] * output.width - crop.x) / crop.w else (dates[j] * output.height - crop.y) / crop.h
                                            val report = HighlightCheck.inspect(pixels, format, floatArrayOf(), dates, false, ::check).report
                                            total += report.total; near += report.near; over += report.over
                                        }
                                        if (raw != null) writeCell(raw, pixels, target, ::check)
                                        else {
                                            val photo = pixels.display(0)
                                            try { canvas.drawBitmap(photo, null, Rect(target.x, target.y, target.right, target.bottom), paint) }
                                            finally { photo.recycle() }
                                        }
                                    }
                                }
                            } catch (e: IOException) {
                                if (e is DevelopCancelled) throw e
                                throw IOException("第 ${i + 1} 格 · ${frame.name}：${e.message ?: "无法渲染"}", e)
                            } catch (e: IllegalArgumentException) {
                                throw IOException("第 ${i + 1} 格 · ${frame.name}：${e.message ?: "无法渲染"}", e)
                            }
                            onProgress(i + 1)
                        }
                        check()
                        val report = if (checkHighlights) HighlightReport(total, near, over) else null
                        val name = "FilmStrip_${strip.name}_${System.currentTimeMillis()}.${format.extension}"
                        val token = outputKey?.let { PrintStorage.Output(it, layout.width, layout.height, report) }
                        val saved = PrintStorage.save(context, name, format.mime, shouldStop = ::stopRequested, output = token) { output ->
                            if (raw == null) {
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw IOException("胶片编码失败")
                            } else {
                                val reader = Scanlines(layout, bitmap, raw)
                                PhotoPng.writeRows(context, layout.width, layout.height, model, output, ::stopRequested, reader::fill)
                            }
                        }
                        Printed(saved.uri, layout.width, layout.height, null, saved.inGallery, report, saved.location)
                    }
                } finally { bitmap.recycle(); temp?.delete() }
            }
        }

    /** The matte may be 8 bit artwork; the individual photos in a PNG never pass through it. */
    private fun writeCell(file: RandomAccessFile, pixels: PhotoPixels, target: StripRect, check: () -> Unit) {
        val src = pixels.pixels().asFloatBuffer()
        val row = ByteBuffer.allocate(target.w * 12).order(ByteOrder.LITTLE_ENDIAN)
        val values = row.asFloatBuffer()
        for (y in 0 until target.h) {
            if (y % 32 == 0) check()
            val start = y * target.w * 3
            for (x in 0 until target.w * 3) values.put(x, src[start + x])
            file.write(row.array())
        }
    }

    private class Scanlines(val layout: StripLayout, val bitmap: Bitmap, val raw: RandomAccessFile) {
        private val native = IntArray(layout.width)
        private val rowBytes = layout.cells.first().w * 12
        private val cellBytes = rowBytes.toLong() * layout.cells.first().h
        private val bytes = ByteArray(rowBytes)
        private val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        fun fill(y: Int, samples: FloatArray) {
            bitmap.getPixels(native, 0, layout.width, 0, y, layout.width, 1)
            for (x in native.indices) {
                val p = native[x]
                samples[3 * x] = (p shr 16 and 255) / 255f
                samples[3 * x + 1] = (p shr 8 and 255) / 255f
                samples[3 * x + 2] = (p and 255) / 255f
            }
            layout.cells.forEachIndexed { i, cell ->
                if (y in cell.y until cell.bottom) {
                    raw.seek(i * cellBytes + (y - cell.y).toLong() * rowBytes)
                    raw.readFully(bytes)
                    floats.position(0)
                    floats.get(samples, cell.x * 3, cell.w * 3)
                }
            }
        }
    }
}
