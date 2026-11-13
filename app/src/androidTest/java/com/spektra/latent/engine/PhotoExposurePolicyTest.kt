package com.spektra.latent.engine

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.model.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Regression coverage for already-exposed photographs, rather than live scene metering. */
@RunWith(AndroidJUnit4::class)
class PhotoExposurePolicyTest {
    @Before fun startEngine() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(FilmEngine.start(context))
    }

    @Test fun whiteSurroundingsDoNotDarkenTheSamePhotographedSubject() {
        val look = cleanLook()
        val dark = subjectPixels(look, surround = 0xff202020.toInt(), savedPhoto = true)
        val white = subjectPixels(look, surround = 0xfff8f8f8.toInt(), savedPhoto = true)
        val maximumDifference = dark.indices.maxOf { maxRgbDifference(dark[it], white[it]) }
        assertTrue(
            "A saved subject must retain its exposure when only its surroundings change; " +
                "maximum difference=$maximumDifference codes",
            maximumDifference <= 1,
        )

        // Verify this fixture really distinguishes scene metering from a fixed
        // exposure. The unchanged reference/live API must still meter scenes.
        val meteredDark = brightness(subjectPixels(look, 0xff202020.toInt(), savedPhoto = false))
        val meteredWhite = brightness(subjectPixels(look, 0xfff8f8f8.toInt(), savedPhoto = false))
        assertTrue(
            "The metered control must reveal high-key darkening: dark=$meteredDark white=$meteredWhite",
            meteredDark - meteredWhite > 8.0 / 255.0,
        )
    }

    @Test fun manualExposureBrightensHighKeyPhotosWithoutPrintCompensation() {
        // Start with the legacy enabled flags: older saved recipes must also
        // obey the photograph policy without first moving an exposure slider.
        val look = cleanLook()
        val values = listOf(-1f, 0f, 1f).map { ev ->
            brightness(subjectPixels(look.copy(exposure = ev), 0xfff8f8f8.toInt(), savedPhoto = true))
        }
        assertTrue(
            "EV -1/0/+1 must visibly brighten the subject: $values",
            values.zipWithNext().all { (low, high) -> high - low > 3.0 / 255.0 },
        )
        assertTrue("Two stops should produce a visible change: $values", values.last() - values.first() > .06)
    }

    @Test fun restoredRecipeKeepsReferenceDefaultsButSavedPhotoUsesFixedExposure() {
        val original = cleanLook().copy(exposure = .75f,
            model = PhotoModel(autoExposure = true, autoExposureMethod = "matrix",
                printExposureCompensation = true, normalizePrintExposure = true))
        val restored = Look.fromJson(original.toJson().toString())
        val reference = restored.order(7919L)
        val photo = restored.photoOrder(7919L)
        assertTrue("Live/reference metering must remain enabled", reference.model.autoExposure)
        assertTrue("Reference print compensation must remain available", reference.model.printExposureCompensation)
        assertEquals("matrix", reference.model.autoExposureMethod)
        assertFalse("Saved photos must not be metered again", photo.model.autoExposure)
        assertFalse("Manual photo EV must not be cancelled by print compensation", photo.model.printExposureCompensation)
        assertTrue("Stock/paper calibration must remain enabled", photo.model.normalizePrintExposure)
        assertEquals(.75f, photo.exposureEv, 0f)
        assertEquals("Only the exposure flags in the physical model should change", reference.model,
            photo.model.copy(autoExposure = true, printExposureCompensation = true))
        // Rebate is an immutable value wrapper with reference identity equality.
        assertEquals("Only the two exposure policy flags should change", reference,
            photo.copy(model = reference.model, rebate = reference.rebate))
        assertEquals("Rendering policy must not rewrite a restored recipe", original.model, restored.model)
    }

    private fun cleanLook() = Look(
        halation = 0f, grain = 0f, interLayer = 0f, glare = 0f,
        model = PhotoModel(autoExposure = true, printExposureCompensation = true),
    )

    private fun subjectPixels(look: Look, surround: Int, savedPhoto: Boolean): IntArray {
        val side = 64
        // The centre 8x8 subject has identical RGB in both images. A broad
        // surround changes the meter but cannot affect these pixels when
        // optical, diffusion, grain and scanner sharpening effects are off.
        val pixels = IntArray(side * side) { i ->
            if (i % side in 28..35 && i / side in 28..35) 0xff808080.toInt() else surround
        }
        val source = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        try {
            FilmEngine.fromBitmap(source).use { image ->
                val order = (if (savedPhoto) look.photoOrder(7919L) else look.order(7919L))
                    .copy(scatter = 0f, sharpen = false)
                val output = FilmEngine.develop(image, look.photoGrade(), order,
                    look.finish(), look.finishTable(), lane = FilmEngine.THUMB)
                try {
                    return IntArray(16).also { output.getPixels(it, 0, 4, 30, 30, 4, 4) }
                } finally { output.recycle() }
            }
        } finally { source.recycle() }
    }

    private fun brightness(pixels: IntArray) = pixels.map { argb ->
        (.2126 * ((argb ushr 16) and 255) + .7152 * ((argb ushr 8) and 255) +
            .0722 * (argb and 255)) / 255.0
    }.average()

    private fun maxRgbDifference(a: Int, b: Int) = maxOf(
        abs(((a ushr 16) and 255) - ((b ushr 16) and 255)),
        abs(((a ushr 8) and 255) - ((b ushr 8) and 255)),
        abs((a and 255) - (b and 255)),
    )
}
