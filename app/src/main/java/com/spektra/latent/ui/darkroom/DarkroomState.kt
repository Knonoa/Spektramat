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
import com.spektra.latent.data.Frame
import com.spektra.latent.data.HighlightCheck
import com.spektra.latent.data.HighlightReport
import com.spektra.latent.data.PrintFormat
import com.spektra.latent.data.Library
import com.spektra.latent.data.PhotoProxyStore
import com.spektra.latent.data.PhotoLutCache
import com.spektra.latent.data.CreativeSource
import com.spektra.latent.engine.DevelopCancelled
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.LinearImage
import com.spektra.latent.gl.Placement
import com.spektra.latent.gl.PhotoPreviewRenderer
import com.spektra.latent.model.Look
import com.spektra.latent.model.CreativeLook
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One photograph on the bench: its look while it is being worked on, the undo
 * history, where the picture sits on the screen, and its full-engine proof.
 *
 * The look changes on every step of a ruler ([change]); a step becomes an undo
 * entry and is saved only when the ruler comes to rest ([commit]).
 *
 * Interactive views use the shared float proxy and GPU colour cube. A settled,
 * low-priority full-engine proof adds spatial effects without blocking edits.
 */
class DarkroomState(
    context: Context,
    private val library: Library,
    val frame: Frame,
    private val scope: CoroutineScope,
    initialAdjustmentsOnly: Boolean = false,
) : LookHost {
    private val app = context.applicationContext as LatentApp
    private val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val renderer = PhotoPreviewRenderer(app)
    private val proxies = PhotoProxyStore.get(app)
    private var sourceIdentity = ""
    private var exactJob: Job? = null
    private var busy = false
    private var picking = false
    var exact by mutableStateOf(false)
        private set

    override var look by mutableStateOf(frame.look)
        private set
    private var settled = frame.look
    private val undone = ArrayDeque<Look>()
    private val redone = ArrayDeque<Look>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** A proxy result has been published. Until then the thumbnail stands in. */
    var ready by mutableStateOf(false)
        private set
    /** The adjustment tab shows source corrections independently of the film simulation. */
    var adjustmentsOnly by mutableStateOf(initialAdjustmentsOnly)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** Held down: the picture as it came, without the film. */
    var comparing by mutableStateOf(false)
        private set
    internal var comparisonMode by mutableStateOf(
        runCatching { ComparisonMode.valueOf(appearance.getString("photoComparison", "Hold")!!) }.getOrDefault(ComparisonMode.Hold),
    )
        private set
    internal var comparisonFraction by mutableStateOf(.5f)
        private set
    var placement by mutableStateOf(Placement())
        private set

    /** The engine's small print, including the film edge and date when requested. */
    var proof by mutableStateOf<ImageBitmap?>(null)
        private set
    var highlights by mutableStateOf<HighlightReport?>(null)
        private set
    var highlightOverlay by mutableStateOf<ImageBitmap?>(null)
        private set
    private var warningFormat: PrintFormat? = null

    fun setHighlightWarning(format: PrintFormat?) {
        if (warningFormat == format) return
        warningFormat = format
        highlights = null
        highlightOverlay = null
        if (format != null) requestExact()
    }

    var proofGate by mutableStateOf(floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f))
        private set
    var proofSourceQuad by mutableStateOf(PhotoComparison.identity())
        private set
    var proofDateBounds by mutableStateOf(floatArrayOf())
        private set
    var original by mutableStateOf<ImageBitmap?>(null)
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
    private data class CreativeKey(val source: String, val creative: CreativeLook)
    private val creativeLock = Mutex()
    private var creativeKey: CreativeKey? = null
    private var creativeSource: LinearImage? = null
    @Volatile private var loadGeneration = 0L
    private var proofJob: Job? = null
    // declared before init: the collector started there reads it at once
    private data class Want(val look: Look, val sharp: Boolean, val adjustmentsOnly: Boolean, val viewRevision: Long)
    private var viewRevision = 0L
    private val wants = MutableStateFlow<Want?>(null)
    @Volatile private var disposed = false

    /** Width and height of what is on screen: the proof, or the frame cut to its shape. */
    val shownSize: IntArray
        get() = if (comparing) look.frameSize(frame.width, frame.height)
            else proof?.let { intArrayOf(it.width, it.height) } ?: look.frameSize(frame.width, frame.height)

    init {
        proofJob = scope.launch(Dispatchers.Default) { proofs() }
        load(look)
    }

    /** Acquires the undecorated float source proxy for both GPU and exact proofs. */
    private fun load(l: Look) {
        loading?.cancel()
        val generation = ++loadGeneration
        val key = SourceKey(l.aspect, l.lensShading)
        loading = scope.launch(Dispatchers.IO) {
            try {
                proxies.acquire(frame, key.aspect, key.lensShading, PREVIEW_EDGE).use { image ->
                    if (disposed || generation != loadGeneration) return@use
                    val display = FilmEngine.display(image)
                    main.post {
                        if (!disposed && generation == loadGeneration) original = display.asImageBitmap()
                        else display.recycle()
                    }
                    val small = image.scaled(PROOF_EDGE)
                    synchronized(sourceLock) {
                        if (disposed || generation != loadGeneration) {
                            small.close()
                        } else {
                            proofSource?.close()
                            creativeSource?.close()
                            creativeSource = null
                            creativeKey = null
                            proofSource = small
                            sourceIdentity = "${frame.file.path}:${frame.file.length()}:${frame.file.lastModified()}:$key:$generation"
                            loadedSource = key
                        }
                    }
                }
                main.post { if (!disposed && generation == loadGeneration) show() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                main.post { if (!disposed && generation == loadGeneration) failure = e.message ?: "这张照片读不出来" }
            } catch (e: OutOfMemoryError) {
                main.post { if (!disposed && generation == loadGeneration) failure = "内存不足，读不了这张照片" }
            }
        }
    }

    // ---- the look

    fun showAdjustmentsOnly(on: Boolean) {
        if (on == adjustmentsOnly) return
        adjustmentsOnly = on
        viewRevision++
        comparing = false
        // Do not display a stale film result in the adjustment workspace.
        proof = null
        highlights = null
        highlightOverlay = null
        ready = false
        proofGate = WHOLE_GATE.copyOf()
        proofSourceQuad = PhotoComparison.identity()
        proofDateBounds = floatArrayOf()
        placement = clamp(Placement())
        show()
    }

    override fun change(next: Look) {
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

    override fun commit() {
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

    /** Dimensions are local to the clipped photo viewport, never to the whole screen. */
    fun layout(width: Float, height: Float) {
        if (viewW == width && viewH == height) return
        viewW = width
        viewH = height
        // Rotation and opening/closing the tray both start with the entire photograph visible.
        placement = clamp(Placement())
        show()
    }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val size = shownSize
        placement = PhotoViewport.transform(
            placement, viewW, viewH, size[0], size[1],
            centroid.x, centroid.y, pan.x, pan.y, zoom,
        )
        show()
    }

    fun transformLayer(id: String, dx: Float, dy: Float, zoom: Float, rotation: Float) {
        val current = look
        val creative = current.creative
        change(current.copy(creative = creative.copy(layers = creative.layers.map { layer ->
            if (id != layer.id) layer else {
                val p = layer.placement
                layer.copy(placement = p.copy(x = (p.x + dx).coerceIn(-1f, 1f), y = (p.y + dy).coerceIn(-1f, 1f),
                    scale = (p.scale * zoom).coerceIn(.25f, 3f), rotation = ((p.rotation + rotation + 540f) % 360f) - 180f))
            }
        })))
    }

    fun fit() {
        placement = Placement()
        show()
    }

    fun toggleZoom(at: Offset) {
        if (placement.scale > 1.01f) {
            fit()
        } else {
            transform(at, Offset.Zero, 3f)
        }
    }

    fun compare(on: Boolean) {
        if (comparing == on) return
        comparing = on
        placement = clamp(placement)
        show()
    }

    internal fun comparisonMode(mode: ComparisonMode) {
        comparisonMode = mode
        appearance.edit().putString("photoComparison", mode.name).apply()
        compare(false)
    }

    internal fun comparisonFraction(fraction: Float) {
        comparisonFraction = PhotoComparison.fraction(fraction)
    }

    /** The picture may be moved only as far as it overflows the space it is fitted into. */
    private fun clamp(p: Placement): Placement {
        val size = shownSize
        return PhotoViewport.clamp(p, viewW, viewH, size[0], size[1])
    }

    private fun show() {
        wantProof()
    }

    /** Candidate browsing and export take priority over automatic exact proofs. */
    fun previewActivity(pickingStocks: Boolean, exporting: Boolean) {
        if (picking == pickingStocks && busy == exporting) return
        picking = pickingStocks; busy = exporting
        exactJob?.cancel()
        FilmEngine.cancelProof()
        if ((!picking || warningFormat != null) && !busy && look == settled && !adjustmentsOnly) scheduleExact(Want(look, true, false, viewRevision))
    }

    // ---- the proof

    private fun rebate(l: Look) = l.rebate(shooting, Library.frameSeed(frame), frame.number)

    /** Asks for a proof of the current look; the newest request wins, an older one under way is stopped. */
    private fun wantProof() {
        val l = look
        // Every darkroom frame is a photo render, including a plain frame
        // without film edge/date. The previous exact proof stays visible while
        // the latest look is rendered.
        val want = Want(l, l == settled, adjustmentsOnly, viewRevision)
        if (wants.value == want) return
        proofing = true
        exact = false
        highlights = null
        highlightOverlay = null
        exactJob?.cancel()
        // Stop the previous render before publishing its replacement. Publishing
        // first could let the worker start the new render and cancel that one.
        FilmEngine.cancelProof()
        wants.value = want
    }

    private suspend fun proofs() = wants.collectLatest { want ->
        if (want == null) return@collectLatest
        // the original may still be being read, or read again in a new shape
        val key = SourceKey(want.look.aspect, want.look.lensShading)
        while (loadedSource != key) delay(60)
        while (!disposed && wants.value == want) {
            currentCoroutineContext().ensureActive()
            try {
                delay(16) // coalesce events faster than the display refresh
                makeProof(want, key)
                break
            } catch (e: DevelopCancelled) {
                // A leaving screen can also cancel the shared proof lane. If
                // this is still the latest request, retry instead of leaving
                // the last picture on screen with proofing stuck forever.
                currentCoroutineContext().ensureActive()
                delay(24)
            }
        }
    }

    private suspend fun makeProof(want: Want, key: SourceKey) {
        try {
            val l = want.look
            val order = l.photoOrder(seed = 0L)
            val cube = if (want.adjustmentsOnly) null else
                PhotoLutCache.ready(order, PhotoLutCache.WORK_SIZE)
                    ?: PhotoLutCache.get(app, order, PhotoLutCache.SAMPLE_SIZE)
            currentCoroutineContext().ensureActive()
            val identity = synchronized(sourceLock) { sourceIdentity }
            val source = inputFor(l, key, if (want.sharp) PROOF_EDGE else DRAFT_EDGE, want.adjustmentsOnly) ?: return
            val bitmap = source.use {
                // Scene sliders reuse the same composed float proxy and GPU upload.
                renderer.render(it, "$identity:${if (want.adjustmentsOnly) "original" else l.creative}:${it.width}x${it.height}", l, cube,
                    want.adjustmentsOnly, if (want.sharp) PROOF_EDGE else DRAFT_EDGE)
            }
            val image = bitmap.asImageBitmap()
            main.post {
                if (!disposed && wants.value == want && loadedSource == key) {
                    proof = image
                    proofGate = WHOLE_GATE.copyOf()
                    proofSourceQuad = PhotoComparison.identity()
                    proofDateBounds = floatArrayOf()
                    ready = true
                    failure = null
                    placement = clamp(placement)
                    proofing = false
                    exact = false
                } else bitmap.recycle()
            }
            if (want.sharp && !want.adjustmentsOnly) scheduleExact(want)
        } catch (e: DevelopCancelled) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (wants.value == want && !disposed) {
                Journal.w(TAG, "no proof", e)
                main.post { if (!disposed && wants.value == want) {
                    proofing = false; failure = e.message ?: "样张处理失败"
                } }
            }
        } catch (e: OutOfMemoryError) {
            main.post { if (!disposed && wants.value == want) proofing = false }
        }
    }

    fun requestExact() {
        if (!busy && !adjustmentsOnly) scheduleExact(Want(look, look == settled, false, viewRevision), force = true)
    }

    private fun scheduleExact(want: Want, force: Boolean = false) {
        if (disposed || wants.value != want) return
        exactJob?.cancel()
        val checkFormat = warningFormat
        if ((!force && picking && checkFormat == null) || busy || disposed) return
        exactJob = scope.launch(Dispatchers.Default) {
            try {
                delay(650)
                if (disposed || wants.value != want || (!force && picking && checkFormat == null) || busy) return@launch
                val l = want.look
                val key = SourceKey(l.aspect, l.lensShading)
                // Refine the cube once per conversion. Ordinary scene adjustments reuse it.
                PhotoLutCache.get(app, l.photoOrder(0L), PhotoLutCache.WORK_SIZE, interactive = false)
                currentCoroutineContext().ensureActive()
                val source = inputFor(l, key, EXACT_EDGE, false) ?: return@launch
                val gate = rebate(l).pictureGate(source.width, source.height)
                val quad = rebate(l).pictureSource(source.width, source.height)
                val dates = rebate(l).dateBounds(source.width, source.height, l.formatUm / 1000f)
                val renderContext = currentCoroutineContext()
                var inspection: com.spektra.latent.data.HighlightMask? = null
                val bitmap = source.use { copy ->
                    val order = l.photoOrder(frame.id * 7919L + 1L, shooting, Library.frameSeed(frame), frame.number)
                    if (checkFormat == null) FilmEngine.develop(copy, l.photoGrade(), order,
                        l.finish(), l.finishTable(), lane = FilmEngine.PROOF)
                    else FilmEngine.proofFloat(copy, l.photoGrade(), order, l.finish(), l.finishTable()).use { pixels ->
                        val preview = pixels.display(0)
                        try {
                            inspection = HighlightCheck.inspect(pixels, checkFormat, gate, dates, drawMask = true) { renderContext.ensureActive() }
                            preview
                        } catch (e: Throwable) { preview.recycle(); throw e }
                    }
                }
                if (!renderContext.isActive) { bitmap.recycle(); inspection?.overlay?.recycle(); return@launch }
                main.post {
                    if (!disposed && wants.value == want && loadedSource == key && (force || !picking || checkFormat != null) && !busy) {
                        proof = bitmap.asImageBitmap(); proofGate = gate; proofSourceQuad = quad
                        proofDateBounds = dates; exact = true; placement = clamp(placement)
                        if (warningFormat == checkFormat) {
                            highlights = inspection?.report
                            highlightOverlay = inspection?.overlay?.asImageBitmap()
                        } else inspection?.overlay?.recycle()
                    } else { bitmap.recycle(); inspection?.overlay?.recycle() }
                }
            } catch (_: DevelopCancelled) {
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Journal.w(TAG, "exact proof deferred; GPU proxy remains available", e)
            } catch (_: OutOfMemoryError) { /* keep the inexpensive proxy */ }
        }
    }

    /** Keeps creative compositing independent of ordinary scene/film slider changes. */
    private suspend fun inputFor(l: Look, key: SourceKey, edge: Int, originalOnly: Boolean): LinearImage? {
        if (originalOnly || !l.creative.active) return synchronized(sourceLock) {
            if (loadedSource == key) proofSource?.scaled(edge) else null
        }
        return creativeLock.withLock {
            val identity = synchronized(sourceLock) { sourceIdentity }
            val wanted = CreativeKey(identity, l.creative)
            val cached = synchronized(sourceLock) {
                val required = minOf(edge, proofSource?.let { maxOf(it.width, it.height) } ?: edge)
                if (loadedSource == key && creativeKey == wanted &&
                    (creativeSource?.let { maxOf(it.width, it.height) } ?: 0) >= required)
                    creativeSource?.scaled(edge) else null
            }
            if (cached != null) return@withLock cached
            val base = synchronized(sourceLock) {
                if (loadedSource == key) proofSource?.scaled(edge) else null
            } ?: return@withLock null
            var prepared: LinearImage? = null
            try {
                prepared = base.use { CreativeSource(app).apply(it, l, edge) }
                currentCoroutineContext().ensureActive()
                synchronized(sourceLock) {
                    if (disposed || loadedSource != key || sourceIdentity != identity) return@synchronized null
                    creativeSource?.close()
                    creativeSource = prepared
                    creativeKey = wanted
                    prepared = null
                    creativeSource?.scaled(edge)
                }
            } finally { prepared?.close() }
        }
    }

    fun dispose() {
        // whatever the ruler was doing when the person left counts as done
        commit()
        disposed = true
        saving?.cancel()
        loading?.cancel()
        exactJob?.cancel()
        proofJob?.cancel()
        FilmEngine.cancelProof()
        val toSave = settled
        if (toSave != frame.look) app.scope.launch { library.saveLook(frame.id, toSave) }
        renderer.release()
        // the proof loop may still hold it for a moment: it is closed once that loop has ended
        app.scope.launch(Dispatchers.Default) {
            proofJob?.join()
            exactJob?.join()
            synchronized(sourceLock) {
                proofSource?.close()
                creativeSource?.close()
                creativeSource = null
                creativeKey = null
                proofSource = null
                loadedSource = null
            }
        }
    }

    companion object {
        private const val TAG = "latent-darkroom"
        private val WHOLE_GATE = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        /** Long edge of the picture the preview works on. */
        const val PREVIEW_EDGE = 1280
        /** Long edge of the picture a settled proof is made from, and of one made while a ruler is moving. */
        const val PROOF_EDGE = 1280
        const val DRAFT_EDGE = 720
        const val EXACT_EDGE = 960
        const val MAX_ZOOM = 8f
    }
}
