package com.spektra.latent.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import com.spektra.latent.data.PhotoLutCache
import com.spektra.latent.engine.LinearImage
import com.spektra.latent.engine.PhotoPreviewLut
import com.spektra.latent.model.Look
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** GPU work is confined to the proxy. Readback lets the existing crop, comparison and histogram share one image. */
class PhotoPreviewRenderer(context: Context) {
    private val app = context.applicationContext
    private val finish = FinishGpu()
    private var program: Program? = null
    private var sourceTexture = 0
    private var sourceKey: String? = null
    private var cubeKey: String? = null
    private var cubeTexture = 0
    private var target = 0
    private var framebuffer = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private var staging: ByteBuffer? = null
    @Volatile private var released = false
    @Volatile internal var lastRenderUsedGpu = false
        private set

    /** Caller keeps source open until this returns. Only a display bitmap crosses back to Compose. */
    fun render(source: LinearImage, identity: String, look: Look, cube: PhotoLutCache.Cube?,
               adjustmentsOnly: Boolean, maxEdge: Int): Bitmap {
        check(!released) { "Photo preview has been released" }
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        var failure: Throwable? = null
        val gpu = GlCore.await {
            try {
                check(!released)
                check(GlCore.makeCurrent(null))
                val p = program ?: Program(ShaderText.QUAD_VERTEX, ShaderText.photoProxyFragment(app)).also { program = it }
                if (sourceKey != identity) {
                    if (sourceTexture == 0) sourceTexture = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
                    bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, sourceTexture)
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB32F, source.width, source.height,
                        0, GLES30.GL_RGB, GLES30.GL_FLOAT, source.pixels())
                    check(glCheck("float32 photo proxy"))
                    sourceKey = identity
                }
                if (cube != null && cubeKey != cube.key) {
                    if (cubeTexture == 0) cubeTexture = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
                    bindUnit(Tex.SCAN, GLES30.GL_TEXTURE_3D, cubeTexture)
                    GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, cube.size, cube.size, cube.size,
                        0, GLES30.GL_RGB, GLES30.GL_FLOAT, cube.buffer())
                    check(glCheck("photo LUT ${cube.size}"))
                    cubeKey = cube.key
                }
                if (cubeTexture == 0) {
                    cubeTexture = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, cubeTexture)
                    GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, 2, 2, 2,
                        0, GLES30.GL_RGB, GLES30.GL_FLOAT, null)
                }
                ensureTarget(width, height)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
                GLES30.glViewport(0, 0, width, height)
                GLES30.glDisable(GLES30.GL_BLEND)
                p.use()
                p.set("u_rect", -1f, -1f, 2f, 2f)
                p.set("u_uv", 0f, 0f, 1f, 1f)
                bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, sourceTexture)
                p.setInt("u_src", Tex.SOURCE)
                p.setStruct("u_grade", ShaderText.GRADE, if (adjustmentsOnly) look.grade(0f) else look.photoGrade())
                p.set("u_photo_ev", if (adjustmentsOnly) 1f else 2f.pow(look.exposure))
                p.setInt("u_photo_film", if (!adjustmentsOnly && cube != null) 1 else 0)
                val display = PhotoPreviewLut.displayMatrix(if (adjustmentsOnly || cube == null) "sRGB" else look.model.outputColorSpace)
                p.set("u_photo_to_display_r", display[0], display[1], display[2])
                p.set("u_photo_to_display_g", display[3], display[4], display[5])
                p.set("u_photo_to_display_b", display[6], display[7], display[8])
                bindUnit(Tex.SCAN, GLES30.GL_TEXTURE_3D, cubeTexture)
                p.setInt("u_preview_lut", Tex.SCAN)
                p.set("u_preview_lut_n", (cube?.size ?: 2).toFloat())
                finish.bind(p, look)
                Quad.draw()
                val bytes = staging!!
                bytes.clear()
                GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
                check(glCheck("photo proxy render"))
                bytes.rewind()
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(bytes) }
            } catch (error: Throwable) {
                failure = error
                null
            } finally { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }
        }
        lastRenderUsedGpu = gpu != null
        if (gpu != null) return gpu
        check(!released) { "Photo preview has been released" }
        // The fallback samples the same small floating proxy and cube, never the full photo engine.
        com.spektra.latent.Journal.w("latent-photo-preview", "GPU unavailable, using proxy sampler", failure)
        return source.scaled(maxEdge).use { small ->
            if (adjustmentsOnly || cube == null) com.spektra.latent.engine.FilmEngine.adjustPhoto(small, look.grade(0f), look.finish(), look.finishTable())
            else PhotoPreviewLut.fromBuffer(cube.size, cube.buffer(), look.model.outputColorSpace).use { lut ->
                lut.render(small, look.photoGrade(), look.exposure, look.finish(), look.finishTable())
            }
        }
    }

    private fun ensureTarget(width: Int, height: Int) {
        if (targetWidth == width && targetHeight == height && framebuffer != 0) return
        if (target == 0) target = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        if (framebuffer == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
        targetWidth = width; targetHeight = height
        staging = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }

    fun release() {
        released = true
        GlCore.post {
            GlCore.makeCurrent(null)
            deleteTexture(sourceTexture); deleteTexture(cubeTexture); deleteTexture(target)
            if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            program?.let { GLES30.glDeleteProgram(it.id) }
            finish.release()
            sourceTexture = 0; cubeTexture = 0; target = 0; framebuffer = 0; staging = null
        }
    }
}
