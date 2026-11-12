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
 * history, where the picture sits on the screen, and the GPU preview that
 * draws it.
 *
 * The look changes on every step of a ruler ([change]); a step becomes an undo
 * entry and is saved only when the ruler comes to rest ([commit]).
 *
 * The film outside the frame and the date are exposure on the negative, drawn
 * by the engine: the GPU cannot show them. While either is on, the picture on
 * screen is a [proof] -- a small print the engine makes after every change.
 */
class DarkroomState(
    context: Context,
    private val library: Library,
    val frame: Frame,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext as LatentApp
    private val main = Handler(Looper.getMainLooper())
    val preview = FilmPreview(context)

    var look by mutableStateOf(frame.look)
        private set
    private var settled = frame.look
    private val undone = ArrayDeque<Look>()
    private val redone = ArrayDeque<Look>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** The GPU has drawn this photograph at least once. Until then the thumbnail stands in. */
    var ready by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** Held down: the picture as it came, without the film. */
    var comparing by mutableStateOf(false)
        private set
    var placement by mutableStateOf(Placement())
        private set

    /** The engine's small print of the current look, with the film's edge and the date; null when neither is on. */
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
    @Volatile private var loadedAspect: String? = null
    @Volatile private var proofSource: LinearImage? = null
    private var proofJob: Job? = null
    // declared before init: the collector started there reads it at once
    private data class Want(val look: Look, val sharp: Boolean)
    private val wants = MutableStateFlow<Want?>(null)
    @Volatile private var disposed = false

    /** Width and height of what is on screen: the proof, or the frame cut to its shape. */
    val shownSize: IntArray
        get() = proof?.let { intArrayOf(it.width, it.height) } ?: look.frameSize(frame.width, frame.height)

    init {
        preview.onDrawn = { main.post { ready = true } }
        preview.onError = { reason -> main.post { failure = reason } }
        proofJob = scope.launch(Dispatchers.Default) { proofs() }
        load(look.aspect)
    }

    /** Reads the original, cut to [aspect], for the GPU and for proofs. */
    private fun load(aspect: String) {
        loading?.cancel()
        loading = scope.launch(Dispatchers.IO) {
            try {
                Decoder.decode(frame.file, PREVIEW_EDGE, look.lensShading, aspect).use { image ->
                    if (disposed) return@use
                    preview.setImage(image)
                    val small = image.scaled(PROOF_EDGE)
                    val old = proofSource
                    proofSource = small
                    old?.close()
                    loadedAspect = aspect
                }
                main.post { show() }
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
        look = next
        if (reshaped) {
            placement = placement.copy(scale = 1f, panX = 0f, panY = 0f)
            load(next.aspect)
        }
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
        val reshaped = l.aspect != look.aspect
        settled = l
        look = l
        history()
        if (reshaped) load(l.aspect)
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
        preview.show(
            PreviewFrame(
                look = look,
                meteredEv = frame.meteredEv,
                mode = if (comparing) 1 else 0,
                placement = placement,
                printLongEdge = look.frameSize(frame.width, frame.height).max(),
                seed = (frame.id % 1000).toFloat() + 1f,
            ),
        )
        wantProof()
    }

    // ---- the proof

    private fun rebate(l: Look) = l.rebate(shooting, Library.frameSeed(frame), frame.number)

    /** Asks for a proof of the current look; the newest request wins, an older one under way is stopped. */
    private fun wantProof() {
        val l = look
        if (rebate(l).idle) {
            wants.value = null
            if (proof != null) proof = null
            proofing = false
            FilmEngine.cancelProof()
            return
        }
        // sharper once the hand has let go; smaller, and so quicker, while a ruler is still moving
        val want = Want(l, l == settled)
        if (wants.value == want) return
        proofing = true
        wants.value = want
        FilmEngine.cancelProof()
    }

    private suspend fun proofs() = wants.collectLatest { want ->
        if (want == null) return@collectLatest
        // the original may still be being read, or read again in a new shape
        while (proofSource == null || loadedAspect != want.look.aspect) delay(60)
        val source = proofSource ?: return@collectLatest
        try {
            val l = want.look
            val bitmap = source.scaled(if (want.sharp) PROOF_EDGE else DRAFT_EDGE).use { copy ->
                FilmEngine.develop(
                    copy, l.grade(frame.meteredEv),
                    l.order(seed = frame.id * 7919L + 1L, shooting = shooting, frameSeed = Library.frameSeed(frame), number = frame.number),
                    l.finish(), l.finishTable(), lane = FilmEngine.PROOF,
                )
            }
            val image = bitmap.asImageBitmap()
            main.post {
                if (!disposed && wants.value != null) {
                    proof = image
                    placement = clamp(placement)
                    if (wants.value == want) proofing = false
                }
            }
        } catch (e: DevelopCancelled) {
            // a newer look is waiting
        } catch (e: Exception) {
            if (proofSource === source && !disposed) {
                Journal.w(TAG, "no proof", e)
                main.post { proofing = false; failure = e.message ?: "片边排不出来" }
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
        FilmEngine.cancelProof()
        val toSave = settled
        if (toSave != frame.look) app.scope.launch { library.saveLook(frame.id, toSave) }
        preview.release()
        val source = proofSource
        // the proof loop may still hold it for a moment: it is closed once that loop has ended
        app.scope.launch(Dispatchers.Default) {
            proofJob?.join()
            source?.close()
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
