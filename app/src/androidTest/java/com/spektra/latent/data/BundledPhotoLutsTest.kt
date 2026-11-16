package com.spektra.latent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoPreviewLut
import com.spektra.latent.model.Look
import com.spektra.latent.model.WhitePoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BundledPhotoLutsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun defaultMaterialAndWhiteModesUseValidOfflineCubes() {
        assertNull(FilmEngine.start(context))
        for (white in WhitePoint.entries) {
            val look = Look(whitePoint = white)
            val order = look.photoOrder(0)
            val bytes = BundledPhotoLuts.read(context, order, 33)
            assertNotNull("Missing default $white preview", bytes)
            PhotoPreviewLut.fromBuffer(33, bytes!!).use { bundled ->
                PhotoPreviewLut.bake(order, 33).use { runtime ->
                    val input = FloatArray(8 * 8 * 3) { i ->
                        val value = (i / 3) / 63f
                        when (i % 3) { 0 -> value * 1.3f - .015f; 1 -> value; else -> value * .75f }
                    }
                    FilmEngine.fromLinearRgb(8, 8, input).use { source ->
                        val actual = bundled.render(source, look.photoGrade(), 0f, look.finish(), look.finishTable())
                        val expected = runtime.render(source, look.photoGrade(), 0f, look.finish(), look.finishTable())
                        try {
                            val a = IntArray(64); val b = IntArray(64)
                            actual.getPixels(a, 0, 8, 0, 0, 8, 8)
                            expected.getPixels(b, 0, 8, 0, 0, 8, 8)
                            for (i in a.indices) for (shift in listOf(0, 8, 16))
                                assertTrue("Offline cube must match the current engine at pixel $i",
                                    abs((a[i] ushr shift and 255) - (b[i] ushr shift and 255)) <= 1)
                        } finally { actual.recycle(); expected.recycle() }
                    }
                }
            }
        }
    }

    @Test fun customPointwiseRecipesCannotUseDefaultMaterialCubes() {
        assertNull(FilmEngine.start(context))
        val look = Look()
        assertNotNull(BundledPhotoLuts.read(context, look.copy(exposure = 2f).photoOrder(0), 33))
        assertNull(BundledPhotoLuts.read(context, look.copy(printExposure = 1f).photoOrder(0), 33))
        assertNull(BundledPhotoLuts.read(context, look.copy(filterM = 5f).photoOrder(0), 33))
        assertNull(BundledPhotoLuts.read(context, look.copy(interLayer = 70f).photoOrder(0), 33))
        assertNull(BundledPhotoLuts.read(context, look.copy(model = look.model.copy(
            inputGamutCompression = false)).photoOrder(0), 33))
        assertNull(BundledPhotoLuts.read(context, look.copy(model = look.model.copy(
            outputColorSpace = "Display P3")).photoOrder(0), 33))
        assertNull(BundledPhotoLuts.read(context, look.photoOrder(0), 65))
    }
}
