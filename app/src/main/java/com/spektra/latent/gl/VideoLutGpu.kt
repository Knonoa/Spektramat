package com.spektra.latent.gl

import android.opengl.GLES30
import com.spektra.latent.engine.VideoLut
import java.nio.ByteBuffer

/** One atomically replaced recording LUT. Accessed only on the GL thread. */
class VideoLutGpu {
    private var texture = 0
    private var size = 0
    val ready get() = texture != 0

    fun upload(lut: VideoLut) {
        upload(lut.size, lut.pixels())
    }

    private fun upload(n: Int, pixels: ByteBuffer) {
        val next = newTexture(GLES30.GL_TEXTURE_3D, GLES30.GL_NEAREST)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, next)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F,
            n, n, n, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, pixels)
        if (!glCheck("complete film LUT $n^3")) {
            deleteTexture(next)
            error("无法上传视频胶片 LUT")
        }
        deleteTexture(texture)
        texture = next
        size = n
    }

    fun bind(program: Program) {
        bindUnit(Tex.SCAN, GLES30.GL_TEXTURE_3D, texture)
        program.setInt("u_video_lut", Tex.SCAN)
        program.set("u_video_lut_n", size.toFloat())
    }

    fun release() { deleteTexture(texture); texture = 0; size = 0 }
}
