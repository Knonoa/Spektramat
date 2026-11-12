package com.spektra.latent.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import com.spektra.latent.Journal
import com.spektra.latent.engine.LinearImage
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
    val placement: Placement = Placement(),
)

/**
 * The untouched source for the darkroom's hold-to-compare view. Processed
 * photographs are asynchronous full-engine proofs, displayed by the screen.
 * This display shader cannot request or evaluate a film LUT.
 *
 * Any thread may call [show]; the newest frame wins and at most one draw is
 * queued, so a slider that fires faster than the screen refreshes never builds
 * a backlog. Everything else happens on the GL thread.
 */
class FilmPreview {
    private val source = SourceTexture()
    private var program: Program? = null
    private var surface: EGLSurface? = null
    private var width = 0
    private var height = 0
    private val queued = AtomicBoolean(false)

    @Volatile
    private var frame: PreviewFrame? = null

    /** Called on the GL thread after each frame that drew a picture. */
    @Volatile
    var onDrawn: (() -> kotlin.Unit)? = null

    /** Called on the GL thread when the source could not be displayed. */
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
        source.release()
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
                Journal.e("latent-gl", "preview failed", t)
                onError?.invoke(t.message ?: "preview failed")
            }
        }
        GlCore.swap(target)
        if (drew) onDrawn?.invoke()
    }

    private fun drawPicture(f: PreviewFrame): Boolean {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        val p = program ?: Program(ShaderText.QUAD_VERTEX, ORIGINAL_FRAGMENT).also { program = it }
        val r = f.placement.rect(width.toFloat(), height.toFloat(), source.width, source.height)
        p.use()
        // clip space: x right, y up; the picture's first row is its top
        p.set("u_rect", r[0] / width * 2f - 1f, 1f - r[1] / height * 2f, r[2] / width * 2f, -r[3] / height * 2f)
        p.set("u_uv", 0f, 0f, 1f, 1f)
        bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, source.id)
        p.setInt("u_src", Tex.SOURCE)
        Quad.draw()
        glCheck("preview draw")
        return true
    }

    companion object {
        private const val ORIGINAL_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D u_src;
in vec2 v_uv;
out vec4 o_color;
void main() {
    vec3 linear = texture(u_src, v_uv).rgb;
    vec3 encoded = mix(12.92 * linear,
        1.055 * pow(max(linear, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        step(vec3(0.0031308), linear));
    o_color = vec4(encoded, 1.0);
}
"""
        /** The curtain behind every picture, as a linear grey level for glClearColor (#0C0C0C). */
        const val CURTAIN = 12f / 255f
    }
}
