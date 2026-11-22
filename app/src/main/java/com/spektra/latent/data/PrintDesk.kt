package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.spektra.latent.Journal
import com.spektra.latent.PrintBackground
import java.util.UUID
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.FilmStrip
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** One print on order: which frame, with which look, and how far along it is. */
class PrintJob internal constructor(val id: Long, val frame: Frame, val look: Look, val longEdge: Int,
                                    val format: PrintFormat = PrintFormat.JPEG,
                                    val checkHighlights: Boolean = false, val batch: String? = null,
                                    internal val persistent: Boolean = false,
                                    internal val strip: FilmStrip? = null,
                                    internal val stripLooks: List<Look> = emptyList(),
                                    internal val cameraCapture: Boolean = false,
                                    internal val outputKey: String? = null) {
    internal val animation = PrintAnimation()
    val title: String get() = strip?.let { "${it.mode.label} · ${it.name}" } ?: frame.name

    sealed interface State {
        data object Waiting : State
        data object Working : State
        data object Stopping : State
        data object Cancelled : State
        class Done(val printed: Printed) : State
        class Failed(val reason: String) : State
    }

    var state by mutableStateOf<State>(State.Waiting)
        internal set

    /** The engine's stage: 0 reading, 1 exposed, 2 developed, 3 printed, 4 scanned, 5 saved. */
    var stage by mutableIntStateOf(0)
        internal set

    /**
     * The photograph as [PrintDesk.GRID] x [PrintDesk.GRID] large pixels (ARGB),
     * before the film and after it. The indicator on screen is drawn from these.
     * Null until they have been read, a moment after the job starts.
     */
    var before by mutableStateOf<IntArray?>(null)
        internal set
    var after by mutableStateOf<IntArray?>(null)
        internal set

    internal var recovering = false

    @Volatile
    internal var stopped = false

    val active: Boolean get() = state == State.Waiting || state == State.Working || state == State.Stopping
    var badgeHidden by mutableStateOf(false)
        internal set
}

/**
 * Where prints are made: in the background, one after another, on the app's
 * own scope. Ordering a print never takes over a screen -- the person keeps
 * working, in the darkroom or anywhere else, and a small indicator shows that
 * a print is under way.
 */
class PrintDesk internal constructor(context: Context, private val scope: CoroutineScope, private val library: Library,
                                     private val background: PrintBackground? = null) {
    private val app = context.applicationContext
    private val gate = Mutex()
    private var nextId = 1L
    private val prefs = app.getSharedPreferences("print-queue", Context.MODE_PRIVATE)

    /** Every order that has not been dismissed, oldest first. Read and changed on the main thread. */
    val jobs = mutableStateListOf<PrintJob>()

    /** Reattaches a workbench to its outstanding order after navigating back. */
    internal fun workbenchJob(frameId: Long): PrintJob? =
        jobs.firstOrNull { it.strip == null && it.frame.id == frameId && !it.stopped && !it.animation.acknowledged }

    init {
        restore()
        scope.launch {
            library.rolls.collect { rolls ->
                val alive = rolls.flatMap { it.frames }.mapTo(HashSet()) { it.id }
                for (job in jobs.toList()) {
                    if (job.strip == null && job.frame.id !in alive && (job.state is PrintJob.State.Waiting || job.state is PrintJob.State.Working)) stop(job)
                }
            }
        }
    }

    fun order(frame: Frame, look: Look, longEdge: Int, format: PrintFormat = PrintFormat.JPEG,
              checkHighlights: Boolean = app.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean(HIGHLIGHT_PREF, false),
              batch: String? = null): PrintJob = enqueue(frame, look, longEdge, format, checkHighlights, batch)

    /** Automatic camera prints use the queue badge without taking over the workbench. */
    internal fun orderCapture(frame: Frame): PrintJob {
        val appearance = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        return enqueue(frame, frame.look, appearance.getInt("exportEdge", 0), PrintFormat.JPEG,
            appearance.getBoolean(HIGHLIGHT_PREF, false), batch = null, cameraCapture = true)
    }

    /** A batch is written to the queue once, then its members render in order. */
    fun orderBatch(frames: List<Frame>, longEdge: Int, format: PrintFormat, batch: String? = null) {
        val check = app.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean(HIGHLIGHT_PREF, false)
        val added = frames.map { PrintJob(nextId++, it, it.look, if (longEdge == 0) it.longEdge else longEdge, format, check, batch, persistent = true, outputKey = newOutputKey()) }
        jobs.addAll(added)
        persist()
        start(added)
    }

    internal fun orderStrip(strip: FilmStrip, format: PrintFormat): PrintJob? {
        val frames = strip.frames.map { library.frame(it.id) ?: return null }
        val check = app.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean(HIGHLIGHT_PREF, false)
        return enqueueStrip(strip, frames, frames.map { it.look }, format, check)
    }

    private fun enqueueStrip(strip: FilmStrip, frames: List<Frame>, looks: List<Look>, format: PrintFormat, check: Boolean): PrintJob {
        val job = PrintJob(nextId++, frames.first(), looks.first(), 0, format, check, persistent = true,
            strip = strip, stripLooks = looks, outputKey = newOutputKey())
        job.animation.acknowledged = true
        job.badgeHidden = true // the editor sends the user to the queue for strip progress
        jobs += job
        persist()
        start(listOf(job))
        return job
    }

    private fun enqueue(frame: Frame, look: Look, longEdge: Int, format: PrintFormat, check: Boolean, batch: String?,
                        cameraCapture: Boolean = false): PrintJob {
        val job = PrintJob(nextId++, frame, look, longEdge, format, check, batch, persistent = true, cameraCapture = cameraCapture, outputKey = newOutputKey())
        if (cameraCapture) job.animation.acknowledged = true
        jobs += job
        persist()
        start(listOf(job))
        return job
    }

    private fun newOutputKey(): String? = background?.let { UUID.randomUUID().toString() }

    internal fun holdForCapture(): () -> Unit = background?.holdForCapture() ?: {}

    private fun start(added: List<PrintJob>) {
        if (added.isEmpty()) return
        if (background != null) background.request()
        else added.forEach { job -> scope.launch { run(job) } }
    }

    internal fun nextWaiting(): PrintJob? = jobs.firstOrNull { it.state == PrintJob.State.Waiting }

    fun stop(job: PrintJob) {
        when (job.state) {
            PrintJob.State.Working -> {
                job.stopped = true
                job.state = PrintJob.State.Stopping
                Printer.cancel()
            }
            PrintJob.State.Waiting -> {
                job.stopped = true
                // A restarted order may already have published before its old process died.
                // Leave it queued for reconciliation, but don't resume rendering it.
                if (!job.recovering) job.state = PrintJob.State.Cancelled
                job.animation.acknowledged = true
            }
            else -> return
        }
        persist()
    }

    fun canRetry(job: PrintJob): Boolean = !job.active && job.state !is PrintJob.State.Done && (job.strip?.frames?.all { library.frame(it.id) != null } ?: (library.frame(job.frame.id) != null))

    /** Retry the original order, even if the workbench's current look has changed. */
    fun retry(job: PrintJob): PrintJob? {
        if (!canRetry(job) || job !in jobs) return null
        job.strip?.let { strip ->
            val frames = strip.frames.map { library.frame(it.id) ?: return null }
            jobs.remove(job)
            return enqueueStrip(strip, frames, job.stripLooks, job.format, job.checkHighlights)
        }
        val frame = library.frame(job.frame.id) ?: return null
        jobs.remove(job)
        return enqueue(frame, job.look, job.longEdge, job.format, job.checkHighlights, job.batch, job.cameraCapture)
    }

    internal fun dismiss(job: PrintJob) {
        jobs.remove(job)
        if (job.persistent) persist()
    }

    fun clearFinished() {
        val activeBatches = jobs.filter { it.active }.mapNotNull { it.batch }.toSet()
        jobs.removeAll { !it.active && it.batch !in activeBatches }
        persist()
    }

    private fun restore() {
        val records = runCatching { JSONArray(prefs.getString("orders", "[]")) }.getOrNull() ?: return
        val restored = HashSet<Long>()
        repeat(records.length()) { i ->
            try {
                val o = records.getJSONObject(i)
                val id = o.getLong("id")
                if (id <= 0 || id in restored) return@repeat
                nextId = maxOf(nextId, id + 1)
                val frameId = o.getLong("frame")
                val look = Look.fromJson(o.optString("look"))
                val frame = library.frame(frameId) ?: Frame(frameId, o.optLong("project"), 0, app.filesDir,
                    o.optString("name", "原片"), false, 1, 1, 0L, "", "", 0, 0.0, 0.0, 0.0, 0f, look, app.filesDir, 0)
                val strip = o.optJSONObject("strip")?.let(FilmStrip::fromJson)
                val stripLooks = if (strip != null) {
                    val looks = o.getJSONArray("stripLooks")
                    require(looks.length() == strip.frames.size)
                    List(looks.length()) { Look.fromJson(looks.getString(it)) }
                } else emptyList()
                val job = PrintJob(id, frame, look, o.optInt("edge"), PrintFormat.valueOf(o.getString("format")),
                    o.optBoolean("check"), o.optString("batch").ifBlank { null }, persistent = true, strip = strip, stripLooks = stripLooks,
                    cameraCapture = o.optBoolean("cameraCapture"),
                    outputKey = o.optString("outputKey").takeIf { KEY.matches(it) })
                job.state = when (o.optString("state")) {
                    "done" -> {
                        val report = o.optJSONArray("highlights")?.let { HighlightReport(it.getInt(0), it.getInt(1), it.getInt(2)) }
                        PrintJob.State.Done(Printed(Uri.parse(o.getString("uri")), o.getInt("width"), o.getInt("height"),
                            null, o.optBoolean("gallery"), report, o.optString("location").ifBlank { null }))
                    }
                    "failed" -> PrintJob.State.Failed(o.optString("reason", "冲印没有完成"))
                    "cancelled" -> PrintJob.State.Cancelled
                    "queued", "rendering", "stopping" -> if (job.outputKey != null) {
                        job.recovering = o.optString("state") != "queued"
                        job.stopped = o.optString("state") == "stopping"
                        PrintJob.State.Waiting
                    } else PrintJob.State.Failed("冲印中断，可以重试")
                    else -> PrintJob.State.Failed("上次退出时冲印未完成。请先查看相册，避免重复导出。")
                }
                job.animation.acknowledged = true
                job.badgeHidden = true
                jobs += job
                restored += id
            } catch (e: Exception) { Journal.w(TAG, "invalid queue record", e) }
        }
    }

    private fun persist(): Boolean {
        // Preserve all outstanding orders and a bounded history of finished work.
        val activeBatches = jobs.filter { it.active }.mapNotNull { it.batch }.toSet()
        val old = jobs.filter { !it.active && !it.animation.awaitingWorkbench && it.batch !in activeBatches }
        val keep = old.takeLast(HISTORY)
        val keepBatches = keep.mapNotNull { it.batch }.toSet()
        if (old.size > HISTORY) jobs.removeAll(old.dropLast(HISTORY).filter { it.batch !in keepBatches }.toSet())
        val records = JSONArray()
        for (j in jobs) {
            if (!j.persistent) continue
            val o = JSONObject().put("id", j.id).put("frame", j.frame.id).put("name", j.frame.name)
                .put("project", j.frame.rollId).put("look", j.look.toJson().toString()).put("edge", j.longEdge)
                .put("format", j.format.name).put("check", j.checkHighlights).put("batch", j.batch ?: "")
                .put("cameraCapture", j.cameraCapture)
            j.outputKey?.let { o.put("outputKey", it) }
            j.strip?.let {
                o.put("strip", it.toJson()).put("stripLooks", JSONArray(j.stripLooks.map { look -> look.toJson().toString() }))
            }
            when (val state = j.state) {
                is PrintJob.State.Done -> with(state.printed) {
                    o.put("state", "done").put("uri", uri.toString()).put("width", width).put("height", height)
                        .put("gallery", inGallery).put("location", location ?: "")
                    highlightReport?.let { o.put("highlights", JSONArray(listOf(it.total, it.near, it.over))) }
                }
                is PrintJob.State.Failed -> o.put("state", "failed").put("reason", state.reason)
                PrintJob.State.Cancelled -> o.put("state", "cancelled")
                else -> o.put("state", when {
                    j.outputKey == null -> "unfinished"
                    j.stopped -> "stopping"
                    state == PrintJob.State.Working || j.recovering -> "rendering"
                    else -> "queued"
                })
            }
            records.put(o)
        }
        val edit = prefs.edit().putString("orders", records.toString())
        if (background == null) { edit.apply(); return true }
        return edit.commit().also { if (!it) Journal.w(TAG, "unable to persist print queue") }
    }

    internal suspend fun run(job: PrintJob): Unit = gate.withLock {
        if (job.state != PrintJob.State.Waiting) return@withLock
        var indicator: kotlinx.coroutines.Job? = null
        job.state = PrintJob.State.Working
        try {
            if (!persist()) throw java.io.IOException("无法保存冲印队列")
            val restored = if (job.recovering && job.outputKey != null) withContext(Dispatchers.IO) {
                PrintStorage.recover(app, job.outputKey, job.format.mime, shouldStop = { job.stopped })
            } else null
            if (restored != null) {
                job.state = PrintJob.State.Done(restored)
                Journal.i(TAG, "print ${job.id} recovered: ${restored.location}")
                if (job.strip == null) Printer.register(library, job.frame, job.look, restored)
                return@withLock
            }
            if (job.stopped) throw DevelopCancelled()
            if (job.strip != null) { runStrip(job, job.strip); return@withLock }
            if (library.frame(job.frame.id) == null) {
                job.state = PrintJob.State.Failed("这张照片已从片库移除")
                return@withLock
            }
            // the big pixels for the indicator: cheap, and independent of the print itself
            if (background?.activityVisible != false) indicator = scope.launch(Dispatchers.IO) {
                try {
                    val (before, after) = bigPixels(job.frame, job.look)
                    if (!job.stopped) {
                        job.before = before
                        job.after = after
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    Journal.w(TAG, "no memory for print indicator; original export continues", e)
                } catch (e: Exception) {
                    Journal.w(TAG, "no big pixels for ${job.frame.name}", e)
                }
            }
            val printed = Printer.print(
                app, library, job.frame, job.look, job.longEdge, job.format,
                shouldStop = { job.stopped || library.frame(job.frame.id) == null },
                checkHighlights = job.checkHighlights, previewEdge = if (job.animation.attached) 640 else 128,
                outputKey = job.outputKey,
            ) { job.stage = it }
            // Printer returns only after its storage commit. A stop pressed during
            // publication must not turn that real saved file into a cancellation.
            job.state = PrintJob.State.Done(printed)
            Journal.i(TAG, "print ${job.id} saved: ${printed.location}")
        } catch (e: DevelopCancelled) {
            if (!currentCoroutineContext().isActive) {
                job.recovering = true
                job.state = PrintJob.State.Waiting
                throw CancellationException("foreground export paused")
            }
            job.state = if (job.stopped) PrintJob.State.Cancelled else PrintJob.State.Failed("冲印中断，可以重试")
            job.animation.acknowledged = true
        } catch (e: CancellationException) {
            if (job.active) {
                job.recovering = true
                job.state = PrintJob.State.Waiting
            }
            throw e
        } catch (e: OutOfMemoryError) {
            job.state = PrintJob.State.Failed("内存不足，无法完成原片渲染；请释放内存后重试。")
        } catch (e: Exception) {
            job.state = PrintJob.State.Failed(e.message ?: "冲印没有完成")
        } finally {
            indicator?.cancel()
            if (persist() && (job.state is PrintJob.State.Done || job.state == PrintJob.State.Cancelled))
                job.outputKey?.let { PrintStorage.clearCheckpoint(app, it) }
        }
    }

    private suspend fun runStrip(job: PrintJob, strip: FilmStrip) {
        val frames = strip.frames.mapIndexed { i, placement ->
            val frame = library.frame(placement.id) ?: throw java.io.IOException("第 ${i + 1} 格的原片已从片库移除")
            frame.copy(look = job.stripLooks[i])
        }
        val printed = StripPrinter.print(app, strip, frames, job.format, job.checkHighlights,
            shouldStop = { job.stopped || frames.any { !it.file.isFile } }, onProgress = { job.stage = it },
            outputKey = job.outputKey)
        job.state = PrintJob.State.Done(printed)
    }

    private suspend fun bigPixels(frame: Frame, look: Look): Pair<IntArray, IntArray> = withContext(Dispatchers.IO) {
        Decoder.decode(frame.file, 96, look.lensShading, look.aspect).use { small ->
            val original = FilmEngine.display(small)
            val order = look.photoOrder(seed = frame.id * 7919L + 1L, shooting = Shooting.of(frame),
                frameSeed = Library.frameSeed(frame), number = frame.number)
            try {
                val creative = if (look.creative.active) CreativeSource(app).apply(small, look, 96) else null
                val film = try {
                    FilmEngine.develop(creative ?: small, look.photoGrade(), order,
                        look.finish(), look.finishTable(), lane = FilmEngine.THUMB)
                } finally { creative?.close() }
                try { mosaic(original) to mosaic(film) } finally { film.recycle() }
            } finally {
                original.recycle()
            }
        }
    }

    /** The centre square of [bitmap] averaged down to GRID x GRID. */
    private fun mosaic(bitmap: Bitmap): IntArray {
        val side = min(bitmap.width, bitmap.height)
        val x0 = (bitmap.width - side) / 2
        val y0 = (bitmap.height - side) / 2
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, x0, y0, side, side)
        val out = IntArray(GRID * GRID)
        for (j in 0 until GRID) for (i in 0 until GRID) {
            val xa = i * side / GRID
            val xb = maxOf(xa + 1, (i + 1) * side / GRID)
            val ya = j * side / GRID
            val yb = maxOf(ya + 1, (j + 1) * side / GRID)
            var r = 0L
            var g = 0L
            var b = 0L
            for (y in ya until yb) for (x in xa until xb) {
                val p = pixels[y * side + x]
                r += p shr 16 and 0xff
                g += p shr 8 and 0xff
                b += p and 0xff
            }
            val n = ((xb - xa) * (yb - ya)).toLong()
            out[j * GRID + i] = (0xff shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
        return out
    }

    companion object {
        private val KEY = Regex("[a-f0-9-]{36}")
        private const val HISTORY = 80
        private const val TAG = "latent-print"
        /** The indicator shows the photograph as this many large pixels on a side. */
        const val GRID = 5
    }
}
