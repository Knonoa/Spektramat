package com.spektra.latent.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.VideoLut
import com.spektra.latent.model.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class VideoLutGpuTest {
    @Test fun recordingShaderCompilesAndGpuCubeMatchesCpuSampler() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val look = Look()
        val width = 16
        val height = 16
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val input = ByteBuffer.allocateDirect(source.byteCount).order(ByteOrder.nativeOrder())
        val random = java.util.Random(7919L)
        repeat(width * height) {
            repeat(3) { input.put(random.nextInt(256).toByte()) }
            input.put(255.toByte())
        }
        input.rewind()
        source.copyPixelsFromBuffer(input)
        try {
            VideoLut.bake(look.order(0L), look.grade(0f)).use { lut ->
                val expected = lut.render(source, look.finish(), look.finishTable())
                try {
                    val cpu = ByteBuffer.allocateDirect(expected.byteCount).order(ByteOrder.nativeOrder())
                    expected.copyPixelsToBuffer(cpu)
                    val gpu = checkNotNull(GlCore.await {
                        runCatching {
                            check(GlCore.makeCurrent(null))
                            // Compile the production external-camera program as well as
                            // its 2D input adaptation used to read deterministic pixels.
                            val actual = Program(ShaderText.CAMERA_VERTEX, ShaderText.videoFragment(context))
                            val fragment = ShaderText.videoFragment(context)
                                .replace("#extension GL_OES_EGL_image_external_essl3 : require\n", "")
                                .replace("samplerExternalOES u_camera", "sampler2D u_camera")
                            val program = Program(ShaderText.CAMERA_VERTEX, fragment)
                            val cube = VideoLutGpu()
                            val finish = FinishGpu()
                            val texture = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
                            val target = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
                            val fbo = IntArray(1)
                            GLES30.glGenFramebuffers(1, fbo, 0)
                            try {
                                cube.upload(lut)
                                input.rewind()
                                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                                    width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, input)
                                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target)
                                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                                    width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                                    GLES30.GL_TEXTURE_2D, target, 0)
                                assertEquals(GLES30.GL_FRAMEBUFFER_COMPLETE, GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER))
                                GLES30.glViewport(0, 0, width, height)
                                program.use()
                                val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
                                GLES30.glUniformMatrix4fv(program.loc("u_tex"), 1, false, identity, 0)
                                bindUnit(Tex.SOURCE, GLES30.GL_TEXTURE_2D, texture)
                                program.setInt("u_camera", Tex.SOURCE)
                                cube.bind(program)
                                finish.bind(program, look)
                                Quad.draw()
                                val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                                GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                                assertTrue(glCheck("video LUT GPU parity"))
                                ByteArray(pixels.capacity()).also { pixels.rewind(); pixels.get(it) }
                            } finally {
                                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                                GLES30.glDeleteFramebuffers(1, fbo, 0)
                                deleteTexture(texture)
                                deleteTexture(target)
                                cube.release()
                                finish.release()
                                GLES30.glDeleteProgram(actual.id)
                                GLES30.glDeleteProgram(program.id)
                            }
                        }
                    }) { GlCore.failure ?: "GL validation did not run" }.getOrThrow()
                    var maximum = 0
                    var squared = 0.0
                    repeat(width * height) { pixel ->
                        repeat(3) { channel ->
                            val i = pixel * 4 + channel
                            val error = abs((gpu[i].toInt() and 255) - (cpu.get(i).toInt() and 255))
                            maximum = maxOf(maximum, error)
                            squared += error * error
                        }
                    }
                    val rms = sqrt(squared / (width * height * 3))
                    android.util.Log.i("video-lut-test", "GPU/CPU max=$maximum RMS=$rms codes")
                    assertTrue("GPU/CPU maximum code difference $maximum", maximum <= 1)
                    assertTrue("GPU/CPU RMS difference $rms", rms <= .25)
                } finally { expected.recycle() }
            }
        } finally { source.recycle() }
    }
}
