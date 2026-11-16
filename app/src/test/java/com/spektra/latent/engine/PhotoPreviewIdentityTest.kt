package com.spektra.latent.engine

import org.junit.Assert.*
import org.junit.Test

class PhotoPreviewIdentityTest {
    private fun order() = PrintOrder("kodak_portra_400", "kodak_portra_endura", false,
        model = PhotoModel(autoExposure = false, printExposureCompensation = false))

    @Test fun identityExcludesSceneExposureRandomnessAndSpatialControls() {
        val base = order()
        val changed = base.copy(exposureEv = 3f, seed = 789L, grainAmount = 0f,
            halationAmount = 2f, filmFormatMm = 60f,
            model = base.model.copy(cameraLensBlurUm = 30f, grainBlur = 2f, glareBlur = 8f,
                halationStrength = listOf(.8f, .4f, .1f), unsharpAmount = 2f,
                cameraDiffusion = DiffusionModel(true, strength = 1f)))
        assertEquals(PhotoPreviewLut.cacheIdentity(base), PhotoPreviewLut.cacheIdentity(changed))
        assertEquals(PhotoPreviewLut.cacheIdentity(base), PhotoPreviewLut.cacheIdentity(order()))
    }

    @Test fun whiteMappingAndCalibrationDensityArePartOfCubeIdentity() {
        val base = order()
        val key = PhotoPreviewLut.cacheIdentity(base)
        assertNotEquals(key, PhotoPreviewLut.cacheIdentity(base.copy(model = base.model.copy(
            scannerWhiteCorrection = true, scannerWhiteLevel = 1f, scannerFullWhiteExtension = true))))
        assertNotEquals(key, PhotoPreviewLut.cacheIdentity(base.copy(model = base.model.copy(
            densityMin = listOf(.1f, .1f, .1f)))))
        assertNotEquals(key, PhotoPreviewLut.cacheIdentity(base.copy(yShift = 10f)))
        assertNotEquals(key, PhotoPreviewLut.cacheIdentity(base.copy(interLayerAmount = .4f)))
        assertNotEquals(key, PhotoPreviewLut.cacheIdentity(base.copy(model = base.model.copy(outputColorSpace = "Display P3"))))
        assertEquals("Display P3", PhotoPreviewLut.canonicalOrder(base.copy(model = base.model.copy(outputColorSpace = "Display P3"))).model.outputColorSpace)
    }
}
