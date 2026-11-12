package com.spektra.latent.gl

import android.opengl.GLES30
import com.spektra.latent.engine.PreviewLut

/** Scene-linear, log-shaped viewfinder cube. The photograph never uses it. */
class PreviewLutGpu {
    private var texture = 0
    private var size = 0
    val ready get() = texture != 0

    fun upload(lut: PreviewLut) {
        val next = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, next)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F,
            lut.size, lut.size, lut.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, lut.pixels())
        if (!glCheck("preview LUT ${lut.size}^3")) {
            deleteTexture(next)
            error("无法上传胶片预览 LUT")
        }
        deleteTexture(texture)
        texture = next
        size = lut.size
    }

    fun bind(program: Program) {
        bindUnit(Tex.SCAN, GLES30.GL_TEXTURE_3D, texture)
        program.setInt("u_preview_lut", Tex.SCAN)
        program.set("u_preview_lut_n", size.toFloat())
        program.set("u_preview_scale", PreviewLut.SHAPER_SCALE)
        program.set("u_preview_log_range", PreviewLut.SHAPER_LOG_RANGE)
    }

    fun release() { deleteTexture(texture); texture = 0; size = 0 }
}
