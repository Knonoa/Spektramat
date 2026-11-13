package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import com.spektra.latent.Journal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

/** One print on order: which frame, with which look, and how far along it is. */
class PrintJob internal constructor(val id: Long, val frame: Frame, val look: Look, val longEdge: Int,
                                    val format: PrintFormat = PrintFormat.AUTO) {
    sealed interface State {
        data object Waiting : State
        data object Working : State
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

    @Volatile
    internal var stopped = false
}

/**
 * Where prints are made: in the background, one after another, on the app's
 * own scope. Ordering a print never takes over a screen -- the person keeps
 * working, in the darkroom or anywhere else, and a small indicator shows that
 * a print is under way.
 */
class PrintDesk(context: Context, private val scope: CoroutineScope, private val library: Library) {
    private val app = context.applicationContext
    private val gate = Mutex()
    private var nextId = 1L

    /** Every order that has not been dismissed, oldest first. Read and changed on the main thread. */
    val jobs = mutableStateListOf<PrintJob>()

    init {
        scope.launch {
            library.rolls.collect { rolls ->
                val alive = rolls.flatMap { it.frames }.mapTo(HashSet()) { it.id }
                for (job in jobs.toList()) {
                    if (job.frame.id !in alive && (job.state is PrintJob.State.Waiting || job.state is PrintJob.State.Working)) stop(job)
                }
            }
        }
    }

    fun order(frame: Frame, look: Look, longEdge: Int, format: PrintFormat = PrintFormat.AUTO): PrintJob {
        val job = PrintJob(nextId++, frame, look, longEdge, format)
        if (library.frame(frame.id) == null) {
            job.stopped = true
            job.state = PrintJob.State.Failed("这张照片已从片库移除")
            return job
        }
        jobs += job
        scope.launch { run(job) }
        return job
    }

    /** Stops a print that is waiting or under way. Nothing is saved. */
    fun stop(job: PrintJob) {
        job.stopped = true
        if (job.state is PrintJob.State.Working) Printer.cancel()
        jobs.remove(job)
    }

    /** Takes a finished (or failed) order off the desk. The print itself stays in the gallery. */
    fun dismiss(job: PrintJob) {
        // its screen-sized copy is left to the garbage collector: the indicator may still be fading out with it
        jobs.remove(job)
    }

    private suspend fun run(job: PrintJob) = gate.withLock {
        if (job.stopped) return@withLock
        if (library.frame(job.frame.id) == null) {
            stop(job)
            return@withLock
        }
        job.state = PrintJob.State.Working
        // the big pixels for the indicator: cheap, and independent of the print itself
        scope.launch(Dispatchers.IO) {
            try {
                val (before, after) = bigPixels(job.frame, job.look)
                if (!job.stopped) {
                    job.before = before
                    job.after = after
                }
            } catch (e: Exception) {
                Journal.w(TAG, "no big pixels for ${job.frame.name}", e)
            }
        }
        try {
            val printed = Printer.print(
                app, library, job.frame, job.look, job.longEdge, job.format,
                shouldStop = { job.stopped || library.frame(job.frame.id) == null },
            ) { job.stage = it }
            if (job.stopped || library.frame(job.frame.id) == null) printed.shown.recycle() else job.state = PrintJob.State.Done(printed)
        } catch (e: DevelopCancelled) {
            // stopped by the person: already off the desk
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            job.state = PrintJob.State.Failed("内存不足，无法完成原片渲染；请释放内存后重试。")
        } catch (e: Exception) {
            job.state = PrintJob.State.Failed(e.message ?: "冲印没有完成")
        }
    }

    private suspend fun bigPixels(frame: Frame, look: Look): Pair<IntArray, IntArray> = withContext(Dispatchers.IO) {
        Decoder.decode(frame.file, 96, look.lensShading, look.aspect).use { small ->
            val original = FilmEngine.display(small)
            val order = look.photoOrder(seed = frame.id * 7919L + 1L, shooting = Shooting.of(frame),
                frameSeed = Library.frameSeed(frame), number = frame.number)
            val film = FilmEngine.develop(small, look.photoGrade(), order,
                look.finish(), look.finishTable(), lane = FilmEngine.THUMB)
            try {
                mosaic(original) to mosaic(film)
            } finally {
                original.recycle()
                film.recycle()
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
        private const val TAG = "latent-print"
        /** The indicator shows the photograph as this many large pixels on a side. */
        const val GRID = 5
    }
}
