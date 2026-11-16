package com.spektra.latent.gl

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.data.PhotoLutCache
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoPreviewLut
import com.spektra.latent.model.Look
import com.spektra.latent.model.WhitePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhotoProxyGpuTest {
    @Test fun selectedOutputSpaceIsConvertedOnlyAfterFinishingForDisplay() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val renderer = PhotoPreviewRenderer(context)
        val samples = floatArrayOf(.3f, .4f, .7f)
        val data = ByteBuffer.allocateDirect(17 * 17 * 17 * 12).order(ByteOrder.nativeOrder())
        data.asFloatBuffer().apply { repeat(17 * 17 * 17) { put(samples) } }
        try {
            FilmEngine.fromLinearRgb(16, 12, FloatArray(16 * 12 * 3) { .18f }).use { source ->
                for (space in listOf("Display P3", "Adobe RGB (1998)", "ProPhoto RGB")) {
                    val look = Look(model = com.spektra.latent.engine.PhotoModel(outputColorSpace = space, outputCctfEncoding = false))
                    val expected = FilmEngine.outputPixels(16, 12,
                        FloatArray(16 * 12 * 3) { samples[it % 3] }, look.model).use { it.display(0) }
                    val cube = PhotoLutCache.Cube(space, 17, data)
                    PhotoPreviewLut.fromBuffer(17, data, space).use { lut ->
                        val cpu = lut.render(source, look.photoGrade(), 0f, look.finish(), look.finishTable())
                        val gpu = renderer.render(source, "wide-source", look, cube, false, 128)
                        try {
                            assertTrue("Selected-space preview must use the GPU", renderer.lastRenderUsedGpu)
                            assertClose(expected, cpu, 1)
                            assertClose(expected, gpu, 1)
                        } finally { expected.recycle(); cpu.recycle(); gpu.recycle() }
                    }
                }
            }
        } finally { renderer.release() }
    }

    @Test fun sourceOnlyFirstFrameAndExposureUseGpuWithoutAnyFilmCube() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val renderer = PhotoPreviewRenderer(context)
        val input = FloatArray(16 * 12 * 3) { i ->
            val values = floatArrayOf(-.02f, 0f, .005f, .03f, .18f, .4f, .8f, 1.2f, 3f)
            values[(i / 3 + i % 3) % values.size]
        }
        try {
            FilmEngine.fromLinearRgb(16, 12, input).use { source ->
                for (ev in listOf(-1.25f, 0f, .75f)) {
                    val look = Look(exposure = ev, temperature = 14f, contrast = 8f, vignette = -12f)
                    val expected = FilmEngine.adjustPhoto(source, look.grade(0f), look.finish(), look.finishTable())
                    val actual = renderer.render(source, "float-source", look, null, true, 128)
                    try {
                        assertTrue("Source-only branch must run on GPU, including its first frame", renderer.lastRenderUsedGpu)
                        assertClose(expected, actual, 1)
                    } finally { expected.recycle(); actual.recycle() }
                }
                assertArrayEquals("Display rendering must not modify the float source", input,
                    FloatArray(input.size).also { source.pixels().asFloatBuffer().get(it) }, 0f)
            }
        } finally { renderer.release() }
    }

    @Test fun signedHdrProxyCubeMatchesCpuAndSceneEditsReuseTheCube() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
        val renderer = PhotoPreviewRenderer(context)
        val base = Look(whitePoint = WhitePoint.Expanded)
        val order = base.photoOrder(0L)
        val key = PhotoLutCache.key(order, 33)
        val input = FloatArray(16 * 12 * 3) { i ->
            val x = (i / 3) % 16 / 15f
            when (i % 3) { 0 -> x * 3f - .04f; 1 -> x * .9f; else -> x * .5f + .02f }
        }
        try {
            PhotoPreviewLut.bake(order, 33).use { lut ->
                val cube = PhotoLutCache.Cube(key, lut.size, lut.pixels())
                FilmEngine.fromLinearRgb(16, 12, input).use { source ->
                    for (ev in listOf(-2f, 0f, 1.25f)) {
                        val look = base.copy(exposure = ev, temperature = 9f, contrast = 12f)
                        assertEquals("Scene controls must not rebake a film conversion", key,
                            PhotoLutCache.key(look.photoOrder(0L), 33))
                        val expected = lut.render(source, look.photoGrade(), look.exposure, look.finish(), look.finishTable())
                        val actual = renderer.render(source, "signed-HDR", look, cube, false, 128)
                        try {
                            assertTrue("Film proxy must sample its cube on GPU", renderer.lastRenderUsedGpu)
                            assertClose(expected, actual, 2)
                        } finally { expected.recycle(); actual.recycle() }
                    }
                }
            }
            assertNotEquals("White mapping must be part of the conversion cache key", key,
                PhotoLutCache.key(base.copy(whitePoint = WhitePoint.Original).photoOrder(0L), 33))
        } finally { renderer.release() }
    }

    private fun assertClose(expected: Bitmap, actual: Bitmap, tolerance: Int) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        val a = IntArray(actual.width * actual.height)
        val b = IntArray(a.size)
        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
        expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
        var maximum = 0
        for (i in a.indices) for (shift in listOf(0, 8, 16))
            maximum = maxOf(maximum, abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255)))
        assertTrue("GPU/CPU difference $maximum codes exceeds $tolerance", maximum <= tolerance)
    }
}
