package com.spektra.latent.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.FilmTables
import com.spektra.latent.engine.LinearImage
import com.spektra.latent.model.Look
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Where a picture sits inside a view: fitted into the space the insets leave, then zoomed and moved. */
data class Placement(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val insetLeft: Float = 0f,
    val insetTop: Float = 0f,
    val insetRight: Float = 0f,
    val insetBottom: Float = 0f,
) {
    /** left, top, width, height of the picture in view pixels. */
    fun rect(viewW: Float, viewH: Float, imageW: Int, imageH: Int): FloatArray {
        val availW = max(1f, viewW - insetLeft - insetRight)
        val availH = max(1f, viewH - insetTop - insetBottom)
        val fit = min(availW / imageW, availH / imageH)
        val w = imageW * fit * scale
        val h = imageH * fit * scale
        val cx = insetLeft + availW / 2f + panX
        val cy = insetTop + availH / 2f + panY
        return floatArrayOf(cx - w / 2f, cy - h / 2f, w, h)
    }
}

/** Everything one frame of the darkroom preview depends on. Immutable: hand a new one to [FilmPreview.show]. */
data class PreviewFrame(
    val look: Look,
    val meteredEv: Float,
    /** 0: through the film. 1: the picture as it came. */
    val mode: Int = 0,
    val placement: Placement = Placement(),
    /** Long edge of the print this picture will make, in pixels: grain is shown as it will look there. */
    val printLongEdge: Int = 4000,
    val seed: Float = 1f,
)

/**
 * The darkroom's picture, drawn by the GPU through the film tables.
 *
 * Any thread may call [show]; the newest frame wins and at most one draw is
 * queued, so a slider that fires faster than the screen refreshes never builds
 * a backlog. Everything else happens on the GL thread.
 */
class FilmPreview(context: Context) {
    private val app = context.applicationContext
    private val tables = FilmTables()
    private val gpu = FilmGpu()
    private val source = SourceTexture()
    private var program: Program? = null
    // halation: the film's exposure, small, blurred
    private var exposure: Program? = null
    private var blur: Program? = null
    private val haloA = FloatTarget()
    private val haloB = FloatTarget()
    private var haloFailed = false
    private var surface: EGLSurface? = null
    private var width = 0
    private var height = 0
    private val queued = AtomicBoolean(false)

    @Volatile
    private var frame: PreviewFrame? = null

    /** Called on the GL thread after each frame that drew a picture. */
    @Volatile
    var onDrawn: (() -> kotlin.Unit)? = null

    /** Called on the GL thread when the tables for a look could not be made. */
    @Volatile
    var onError: ((String) -> kotlin.Unit)? = null

    fun attach(window: SurfaceTexture, w: Int, h: Int) = GlCore.post {
        surface?.let { GlCore.release(it) }
        surface = GlCore.windowSurface(window)
        width = w
        height = h
        draw()
    }

    fun resize(w: Int, h: Int) = GlCore.post {
        width = w
        height = h
        draw()
    }

    /** Blocks until the surface is gone, so the caller may then free the window. */
    fun detach() {
        GlCore.await {
            surface?.let { GlCore.release(it) }
            surface = null
        }
    }

    /** Uploads [image] (blocking: the caller may close it right after) or clears the picture. */
    fun setImage(image: LinearImage?) {
        GlCore.await {
            GlCore.makeCurrent(surface)
            if (image == null) source.release() else source.upload(image)
        }
        schedule()
    }

    fun show(next: PreviewFrame) {
        frame = next
        schedule()
    }

    fun release() = GlCore.post {
        surface?.let { GlCore.release(it) }
        surface = null
        GlCore.makeCurrent(null)
        gpu.release()
        source.release()
        haloA.release()
        haloB.release()
        tables.close()
    }

    private fun schedule() {
        if (queued.compareAndSet(false, true)) {
            GlCore.post {
                queued.set(false)
                draw()
            }
        }
    }

    private fun draw() {
        val target = surface ?: return
        if (width <= 0 || height <= 0 || !GlCore.makeCurrent(target)) return
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(CURTAIN, CURTAIN, CURTAIN, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val f = frame
        var drew = false
        if (f != null && source.id != 0) {
            try {
                drew = drawPicture(f)
            } catch (t: Throwable) {
                Log.e("latent-gl", "preview failed", t)
                onError?.invoke(t.message ?: "preview failed")
            }
        }
        GlCore.swap(target)
        if (drew) onDrawn?.invoke()
    }

    private fun drawPicture(f: PreviewFrame): Boolean {
        val look = f.look
        tables.bake(
            look.film, look.paper, look.scanFilm,
            interLayer = look.interLayer > 0f, interLayerAmount = look.interLayer / 100f,
            yShift = look.filterY, mShift = look.filterM,
        )
        gpu.sync(tables)
        if (!gpu.ready) return false
        val grade = look.grade(f.meteredEv)
        val halation = FloatArray(3) { tables.halation[it] * look.halation / 100f }
        val halo = f.mode == 0 && halation.any { it > 0f } && makeHalo(grade, tables.halation[3])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)

        val p = program ?: Program(ShaderText.QUAD_VERTEX, ShaderText.previewFragment(app)).also { program = it }
        val r = f.placement.rect(width.toFloat(), height.toFloat(), source.width, source.height)
        p.use()
        // clip space: x right, y up; the picture's first row is its top
        p.set("u_rect", r[0] / width * 2f - 1f, 1f - r[1] / height * 2f, r[2] / width * 2f, -r[3] / height * 2f)
        p.set("u_uv", 0f, 0f, 1f, 1f)
        bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, source.id)
        p.setInt("u_src", Tex.SOURCE)
        p.setInt("u_mode", f.mode)
        gpu.bind(p)
        bindUnit(Tex.HALO, GLES30.GL_TEXTURE_2D, if (halo) haloA.texture else source.id)
        p.setInt("u_halo", Tex.HALO)
        if (halo) p.set("u_halation", halation[0], halation[1], halation[2]) else p.set("u_halation", 0f, 0f, 0f)
        p.setStruct("u_grade", ShaderText.GRADE, grade)
        // grain as this screen shows it: one cell per screen pixel of the picture
        val shownLong = max(r[2], r[3])
        val shownUm = FILM_LONG_EDGE_UM / max(shownLong, 1f)
        val printUm = FILM_LONG_EDGE_UM / max(f.printLongEdge, 1)
        val amount = look.grain / 100f
        val film = tables.filmBlock(1f, look.printGain, amount, FilmEngine.grainScale(amount, shownUm, printUm), f.seed)
        p.setStruct("u_film", ShaderText.FILM, film)
        p.set("u_cells", r[2], r[3])
        Quad.draw()
        glCheck("preview draw")
        return true
    }

    /**
     * Halation as the engine applies it between exposing and developing: each
     * layer's exposure, blurred, is added back to it. Here the exposure is
     * rendered small into a float target and blurred there in two passes; the
     * picture's own pass reads it. The engine's three bounces (radius r,
     * r * sqrt 2, r * sqrt 3, weights 4:2:1) are drawn as the one Gaussian
     * with their combined variance, 1.25 r.
     * False when this GPU cannot render to float targets: the preview then
     * shows no halation, the finished print still has it.
     */
    private fun makeHalo(grade: FloatArray, sigmaUm: Float): Boolean {
        if (haloFailed || !GlCore.floatTargets) return false
        val k = HALO_LONG_EDGE.toFloat() / max(source.width, source.height)
        val w = max(1, (source.width * min(k, 1f)).toInt())
        val h = max(1, (source.height * min(k, 1f)).toInt())
        if (!haloA.ensure(w, h) || !haloB.ensure(w, h)) {
            haloFailed = true
            return false
        }
        val e = exposure ?: Program(ShaderText.QUAD_VERTEX, ShaderText.exposureFragment(app)).also { exposure = it }
        haloA.bind()
        e.use()
        e.set("u_rect", -1f, -1f, 2f, 2f)
        e.set("u_uv", 0f, 0f, 1f, 1f)
        bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, source.id)
        e.setInt("u_src", Tex.SOURCE)
        gpu.bind(e)
        e.setStruct("u_grade", ShaderText.GRADE, grade)
        e.setStruct("u_film", ShaderText.FILM, tables.filmBlock(1f, 1f, 0f, 0f, 0f))
        Quad.draw()

        val b = blur ?: Program(ShaderText.QUAD_VERTEX, ShaderText.BLUR_FRAGMENT).also { blur = it }
        val sigma = (1.25f * sigmaUm / FILM_LONG_EDGE_UM * max(w, h)).coerceIn(0.3f, 2.6f)
        b.use()
        b.set("u_rect", -1f, -1f, 2f, 2f)
        b.set("u_uv", 0f, 0f, 1f, 1f)
        b.setInt("u_src", Tex.SOURCE)
        b.set("u_sigma", sigma)
        haloB.bind()
        bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, haloA.texture)
        b.set("u_step", 1f / w, 0f)
        Quad.draw()
        haloA.bind()
        bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, haloB.texture)
        b.set("u_step", 0f, 1f / h)
        Quad.draw()
        return glCheck("halation")
    }

    companion object {
        /** Long edge of the small picture halation is blurred on. */
        private const val HALO_LONG_EDGE = 512
        /** The curtain behind every picture, as a linear grey level for glClearColor (#0C0C0C). */
        const val CURTAIN = 12f / 255f
        /** The long edge of a 35 mm frame as the engine counts it (film_format_mm = 35). */
        const val FILM_LONG_EDGE_UM = 35000f
    }
}
