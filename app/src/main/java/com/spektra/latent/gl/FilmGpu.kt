package com.spektra.latent.gl

import android.opengl.GLES30
import com.spektra.latent.engine.FilmTables
import com.spektra.latent.engine.LinearImage
import java.nio.Buffer

/** Texture units used by the film shaders. */
object Tex {
    const val SOURCE = 0
    const val TC = 1
    const val CURVES = 2
    const val ENLARGER = 3
    const val SCAN = 4
    const val HALO = 5
}

fun newTexture(target: Int, filter: Int): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    GLES30.glBindTexture(target, ids[0])
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    if (target == GLES30.GL_TEXTURE_3D) GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
    return ids[0]
}

fun deleteTexture(id: Int) {
    if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
}

/**
 * The film's tables as textures, kept in step with a [FilmTables]: [sync]
 * uploads only the groups whose version moved. GL thread only.
 *
 * Formats: the curves and both colour tables stay 32-bit float and are
 * interpolated by the shader itself -- the print's densities need more than
 * the 11 bits of a half float, and not every GPU filters float 3D textures.
 * Only the chromaticity table is a half float read with the GPU's own filter.
 */
class FilmGpu {
    private var tc = 0
    private var curves = 0
    private var enlarger = 0
    private var scan = 0
    private var filmVersion = -1
    private var printVersion = -1
    private var scanVersion = -1
    private var curveWidths = FloatArray(5)
    private var tcN = 0f
    private var enlargerN = 0f
    private var scanN = 0f

    fun sync(t: FilmTables) {
        val filmChanged = t.filmVersion != filmVersion
        val printChanged = t.printVersion != printVersion
        if (filmChanged) {
            t.chromaticity()?.let {
                if (tc == 0) tc = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
                upload2d(tc, GLES30.GL_RGB16F, t.tcSize, t.tcSize, it)
                tcN = t.tcSize.toFloat()
            }
            filmVersion = t.filmVersion
        }
        if (filmChanged || printChanged) {
            t.curves()?.let {
                if (curves == 0) curves = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
                upload2d(curves, GLES30.GL_RGB32F, t.curvesWidth, 5, it)
                val film = t.filmWidth.toFloat()
                curveWidths = floatArrayOf(film, film, maxOf(t.paperWidth, 2).toFloat(), film, film)
            }
        }
        if (printChanged) {
            t.enlarger()?.let {
                if (enlarger == 0) enlarger = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
                upload3d(enlarger, GLES30.GL_RGB32F, t.enlargerSize, it)
                enlargerN = t.enlargerSize.toFloat()
            }
            printVersion = t.printVersion
        }
        if (t.scanVersion != scanVersion) {
            t.scan()?.let {
                if (scan == 0) scan = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
                upload3d(scan, GLES30.GL_RGB32F, t.scanSize, it)
                scanN = t.scanSize.toFloat()
            }
            scanVersion = t.scanVersion
        }
    }

    val ready get() = tc != 0 && curves != 0 && scan != 0

    /** Binds the tables to their units and sets the samplers and sizes on [program] (already in use). */
    fun bind(program: Program) {
        // a film that is scanned directly never made an enlarger table: any 3D texture satisfies the sampler
        val enlargerOrScan = if (enlarger != 0) enlarger else scan
        bindUnit(Tex.TC, GLES30.GL_TEXTURE_2D, tc)
        bindUnit(Tex.CURVES, GLES30.GL_TEXTURE_2D, curves)
        bindUnit(Tex.ENLARGER, GLES30.GL_TEXTURE_3D, enlargerOrScan)
        bindUnit(Tex.SCAN, GLES30.GL_TEXTURE_3D, scan)
        program.setInt("u_tc", Tex.TC)
        program.setInt("u_curves", Tex.CURVES)
        program.setInt("u_enlarger", Tex.ENLARGER)
        program.setInt("u_scan", Tex.SCAN)
        program.set("u_tc_n", tcN)
        program.set("u_enlarger_n", maxOf(enlargerN, 2f))
        program.set("u_scan_n", scanN)
        GLES30.glUniform1fv(program.loc("u_curve_w"), 5, curveWidths, 0)
    }

    fun release() {
        deleteTexture(tc); deleteTexture(curves); deleteTexture(enlarger); deleteTexture(scan)
        tc = 0; curves = 0; enlarger = 0; scan = 0
        filmVersion = -1; printVersion = -1; scanVersion = -1
    }

    private fun upload2d(texture: Int, format: Int, w: Int, h: Int, data: Buffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, format, w, h, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, data)
        glCheck("table ${w}x$h")
    }

    private fun upload3d(texture: Int, format: Int, n: Int, data: Buffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, format, n, n, n, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, data)
        glCheck("table $n^3 (${data.capacity()} bytes)")
    }
}

fun bindUnit(unit: Int, target: Int, texture: Int) {
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
    GLES30.glBindTexture(target, texture)
}

/** A decoded picture as a half-float texture (scene-linear). GL thread only. */
class SourceTexture {
    var id = 0; private set
    var width = 0; private set
    var height = 0; private set

    fun upload(image: LinearImage) {
        if (id == 0) id = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB16F, image.width, image.height, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, image.pixels(),
        )
        width = image.width
        height = image.height
    }

    fun release() {
        deleteTexture(id)
        id = 0; width = 0; height = 0
    }
}

/** Logs and clears pending GL errors; returns whether there were none. */
fun glCheck(what: String): Boolean {
    var clean = true
    while (true) {
        val e = GLES30.glGetError()
        if (e == GLES30.GL_NO_ERROR) break
        clean = false
        android.util.Log.e("latent-gl", "GL error 0x${Integer.toHexString(e)} after $what")
    }
    return clean
}

/** A half-float colour target to render into and then read as a texture. GL thread only. */
class FloatTarget {
    var texture = 0; private set
    var width = 0; private set
    var height = 0; private set
    private var fbo = 0

    /** Makes the target [w] x [h]; false when this GPU cannot render to half floats. */
    fun ensure(w: Int, h: Int): Boolean {
        if (texture != 0 && w == width && h == height) return true
        if (texture == 0) {
            texture = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        width = w
        height = h
        if (!complete) release()
        return complete
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        deleteTexture(texture)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        texture = 0; fbo = 0; width = 0; height = 0
    }
}
