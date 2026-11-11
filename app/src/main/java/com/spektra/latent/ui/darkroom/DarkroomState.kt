package com.spektra.latent.ui.darkroom

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.spektra.latent.LatentApp
import com.spektra.latent.data.Decoder
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.gl.FilmPreview
import com.spektra.latent.gl.Placement
import com.spektra.latent.gl.PreviewFrame
import com.spektra.latent.model.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * One photograph on the bench: its look while it is being worked on, the undo
 * history, where the picture sits on the screen, and the GPU preview that
 * draws it.
 *
 * The look changes on every step of a ruler ([change]); a step becomes an undo
 * entry and is saved only when the ruler comes to rest ([commit]).
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

    private var viewW = 0f
    private var viewH = 0f
    private var saving: Job? = null

    init {
        preview.onDrawn = { main.post { ready = true } }
        preview.onError = { reason -> main.post { failure = reason } }
        scope.launch(Dispatchers.IO) {
            try {
                Decoder.decode(frame.file, PREVIEW_EDGE, look.lensShading).use { preview.setImage(it) }
                show()
            } catch (e: Exception) {
                failure = e.message ?: "这张照片读不出来"
            } catch (e: OutOfMemoryError) {
                failure = "内存不足，读不了这张照片"
            }
        }
    }

    // ---- the look

    fun change(next: Look) {
        if (next == look) return
        look = next
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
        settled = l
        look = l
        history()
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
        val r = p.copy(panX = 0f, panY = 0f).rect(viewW, viewH, frame.width, frame.height)
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
                printLongEdge = frame.longEdge,
                seed = (frame.id % 1000).toFloat() + 1f,
            ),
        )
    }

    fun dispose() {
        // whatever the ruler was doing when the person left counts as done
        commit()
        saving?.cancel()
        val toSave = settled
        if (toSave != frame.look) app.scope.launch { library.saveLook(frame.id, toSave) }
        preview.release()
    }

    companion object {
        /** Long edge of the picture the preview works on. */
        const val PREVIEW_EDGE = 2048
        const val MAX_ZOOM = 8f
    }
}
