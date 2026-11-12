package com.spektra.latent.ui.darkroom

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.spektra.latent.Journal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.spektra.latent.LatentApp
import com.spektra.latent.data.Decoder
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.LinearImage
import com.spektra.latent.gl.FilmPreview
import com.spektra.latent.gl.Placement
import com.spektra.latent.gl.PreviewFrame
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * One photograph on the bench: its look while it is being worked on, the undo
 * history, where the picture sits on the screen, and its full-engine proof.
 *
 * The look changes on every step of a ruler ([change]); a step becomes an undo
 * entry and is saved only when the ruler comes to rest ([commit]).
 *
 * Every processed view is a [proof], a small print made after each change.
 * The GPU surface only displays the untouched source for comparison.
 */
class DarkroomState(
    context: Context,
    private val library: Library,
    val frame: Frame,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext as LatentApp
    private val main = Handler(Looper.getMainLooper())
    val preview = FilmPreview()

    var look by mutableStateOf(frame.look)
        private set
    private var settled = frame.look
    private val undone = ArrayDeque<Look>()
    private val redone = ArrayDeque<Look>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** A full-engine proof has been published. Until then the thumbnail stands in. */
    var ready by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** Held down: the picture as it came, without the film. */
    var comparing by mutableStateOf(false)
        private set
    var placement by mutableStateOf(Placement())
        private set

    /** The engine's small print, including the film edge and date when requested. */
    var proof by mutableStateOf<ImageBitmap?>(null)
        private set
    /** A proof is being made for a look newer than the one [proof] shows. */
    var proofing by mutableStateOf(false)
        private set

    /** What the photograph's file says about how it was taken. */
    val shooting: Shooting by lazy { Shooting.of(frame) }

    private var viewW = 0f
    private var viewH = 0f
    private var saving: Job? = null
    private var loading: Job? = null
    private data class SourceKey(val aspect: String, val lensShading: Boolean)
    private val sourceLock = Any()
    @Volatile private var loadedSource: SourceKey? = null
    private var proofSource: LinearImage? = null
    @Volatile private var loadGeneration = 0L
    private var proofJob: Job? = null
    // declared before init: the collector started there reads it at once
    private data class Want(val look: Look, val sharp: Boolean)
    private val wants = MutableStateFlow<Want?>(null)
    @Volatile private var disposed = false

    /** Width and height of what is on screen: the proof, or the frame cut to its shape. */
    val shownSize: IntArray
        get() = if (comparing) look.frameSize(frame.width, frame.height)
            else proof?.let { intArrayOf(it.width, it.height) } ?: look.frameSize(frame.width, frame.height)

    init {
        preview.onError = { reason -> main.post { failure = reason } }
        proofJob = scope.launch(Dispatchers.Default) { proofs() }
        load(look)
    }

    /** Reads the original, cut to [aspect], for the GPU and for proofs. */
    private fun load(l: Look) {
        loading?.cancel()
        val generation = ++loadGeneration
        val key = SourceKey(l.aspect, l.lensShading)
        loading = scope.launch(Dispatchers.IO) {
            try {
                Decoder.decode(frame.file, PREVIEW_EDGE, key.lensShading, key.aspect).use { image ->
                    if (disposed || generation != loadGeneration) return@use
                    preview.setImage(image)
                    val small = image.scaled(PROOF_EDGE)
                    synchronized(sourceLock) {
                        if (disposed || generation != loadGeneration) {
                            small.close()
                        } else {
                            proofSource?.close()
                            proofSource = small
                            loadedSource = key
                        }
                    }
                }
                main.post { if (!disposed && generation == loadGeneration) show() }
            } catch (e: Exception) {
                main.post { failure = e.message ?: "这张照片读不出来" }
            } catch (e: OutOfMemoryError) {
                main.post { failure = "内存不足，读不了这张照片" }
            }
        }
    }

    // ---- the look

    fun change(next: Look) {
        if (next == look) return
        val reshaped = next.aspect != look.aspect
        val reload = reshaped || next.lensShading != look.lensShading
        look = next
        if (reshaped) {
            placement = placement.copy(scale = 1f, panX = 0f, panY = 0f)
        }
        if (reload) load(next)
        show()
    }

    fun commit() {
        if (look == settled) return
        undone.addLast(settled)
        if (undone.size > 200) undone.removeFirst()
        redone.clear()
        settled = look
        history()
        save()
        wantProof()   // the settled look deserves the sharper proof
    }

    fun undo() {
        val previous = undone.removeLastOrNull() ?: return
        redone.addLast(settled)
        apply(previous)
    }

    fun redo() {
        val next = redone.removeLastOrNull() ?: return
        undone.addLast(settled)
        apply(next)
    }

    private fun apply(l: Look) {
        val reshaped = l.aspect != look.aspect || l.lensShading != look.lensShading
        settled = l
        look = l
        history()
        if (reshaped) load(l)
        show()
        save()
    }

    private fun history() {
        canUndo = undone.isNotEmpty()
        canRedo = redone.isNotEmpty()
    }

    /** Saved a moment after the last change, on the app's scope: leaving the darkroom does not lose it. */
    private fun save() {
        saving?.cancel()
        val toSave = settled
        saving = app.scope.launch {
            delay(700)
            library.saveLook(frame.id, toSave)
        }
    }

    // ---- where the picture sits

    fun layout(width: Float, height: Float, top: Float, bottom: Float) {
        viewW = width
        viewH = height
        val p = placement.copy(insetTop = top, insetBottom = bottom)
        placement = clamp(p)
        show()
    }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val p = placement
        val scale = (p.scale * zoom).coerceIn(1f, MAX_ZOOM)
        val k = scale / p.scale
        // the point under the fingers stays under the fingers
        val cx = p.insetLeft + (viewW - p.insetLeft - p.insetRight) / 2f
        val cy = p.insetTop + (viewH - p.insetTop - p.insetBottom) / 2f
        val px = (p.panX - (centroid.x - cx)) * k + (centroid.x - cx) + pan.x
        val py = (p.panY - (centroid.y - cy)) * k + (centroid.y - cy) + pan.y
        placement = clamp(p.copy(scale = scale, panX = px, panY = py))
        show()
    }

    fun toggleZoom(at: Offset) {
        if (placement.scale > 1.01f) {
            placement = placement.copy(scale = 1f, panX = 0f, panY = 0f)
            show()
        } else {
            transform(at, Offset.Zero, 3f)
        }
    }

    fun compare(on: Boolean) {
        if (comparing == on) return
        comparing = on
        show()
    }

    /** The picture may be moved only as far as it overflows the space it is fitted into. */
    private fun clamp(p: Placement): Placement {
        if (viewW <= 0f || viewH <= 0f) return p
        val size = shownSize
        val r = p.copy(panX = 0f, panY = 0f).rect(viewW, viewH, size[0], size[1])
        val availW = viewW - p.insetLeft - p.insetRight
        val availH = viewH - p.insetTop - p.insetBottom
        val maxX = max(0f, (r[2] - availW) / 2f)
        val maxY = max(0f, (r[3] - availH) / 2f)
        return p.copy(panX = p.panX.coerceIn(-maxX, maxX), panY = p.panY.coerceIn(-maxY, maxY))
    }

    private fun show() {
        preview.show(PreviewFrame(placement = placement))
        wantProof()
    }

    // ---- the proof

    private fun rebate(l: Look) = l.rebate(shooting, Library.frameSeed(frame), frame.number)

    /** Asks for a proof of the current look; the newest request wins, an older one under way is stopped. */
    private fun wantProof() {
        val l = look
        // Every darkroom frame is a photo render, including a plain frame
        // without film edge/date. The previous exact proof stays visible while
        // the latest look is rendered.
        val want = Want(l, l == settled)
        if (wants.value == want) return
        proofing = true
        wants.value = want
        FilmEngine.cancelProof()
    }

    private suspend fun proofs() = wants.collectLatest { want ->
        if (want == null) return@collectLatest
        // the original may still be being read, or read again in a new shape
        val key = SourceKey(want.look.aspect, want.look.lensShading)
        while (loadedSource != key) delay(60)
        try {
            val l = want.look
            val source = synchronized(sourceLock) {
                if (loadedSource == key) proofSource?.scaled(if (want.sharp) PROOF_EDGE else DRAFT_EDGE) else null
            } ?: return@collectLatest
            val bitmap = source.use { copy ->
                FilmEngine.develop(
                    copy, l.photoGrade(),
                    l.order(seed = frame.id * 7919L + 1L, shooting = shooting, frameSeed = Library.frameSeed(frame), number = frame.number),
                    l.finish(), l.finishTable(), lane = FilmEngine.PROOF,
                )
            }
            val image = bitmap.asImageBitmap()
            main.post {
                if (!disposed && wants.value == want && loadedSource == key) {
                    proof = image
                    ready = true
                    failure = null
                    placement = clamp(placement)
                    proofing = false
                } else bitmap.recycle()
            }
        } catch (e: DevelopCancelled) {
            // a newer look is waiting
        } catch (e: Exception) {
            if (wants.value == want && !disposed) {
                Journal.w(TAG, "no proof", e)
                main.post { if (!disposed && wants.value == want) {
                    proofing = false; failure = e.message ?: "样张处理失败"
                } }
            }
        } catch (e: OutOfMemoryError) {
            main.post { proofing = false }
        }
    }

    fun dispose() {
        // whatever the ruler was doing when the person left counts as done
        commit()
        disposed = true
        saving?.cancel()
        loading?.cancel()
        proofJob?.cancel()
        FilmEngine.cancelProof()
        val toSave = settled
        if (toSave != frame.look) app.scope.launch { library.saveLook(frame.id, toSave) }
        preview.release()
        // the proof loop may still hold it for a moment: it is closed once that loop has ended
        app.scope.launch(Dispatchers.Default) {
            proofJob?.join()
            synchronized(sourceLock) {
                proofSource?.close()
                proofSource = null
                loadedSource = null
            }
        }
    }

    companion object {
        private const val TAG = "latent-darkroom"
        /** Long edge of the picture the preview works on. */
        const val PREVIEW_EDGE = 2048
        /** Long edge of the picture a settled proof is made from, and of one made while a ruler is moving. */
        const val PROOF_EDGE = 1100
        const val DRAFT_EDGE = 560
        const val MAX_ZOOM = 8f
    }
}
