package com.spektra.latent.model

import com.spektra.latent.data.PhotoLutCache
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.engine.PrintOrder
import com.spektra.latent.engine.Rebate
import org.junit.Assert.*
import org.junit.Test

class PhotoPreviewCacheTest {
    private fun order() = PrintOrder(Look.DEFAULT_FILM, Look.DEFAULT_PAPER, false)

    @Test fun canonicalKeysAreStableAcrossNewObjectsAndSceneExposureChanges() {
        val key = PhotoLutCache.key(order(), 33)
        repeat(10) { assertEquals(key, PhotoLutCache.key(order(), 33)) }
        val changed = order().copy(exposureEv = 2f, seed = 987L,
            rebate = Rebate(edge = true, frameNumber = 20), grainAmount = 2f, halationAmount = 1.5f)
        assertEquals("Display grade, geometry and spatial stages must not rebake colour", key, PhotoLutCache.key(changed, 33))
    }

    @Test fun materialWhiteAndConversionSettingsCannotReuseTheWrongCube() {
        val base = order()
        val key = PhotoLutCache.key(base, 33)
        assertNotEquals(key, PhotoLutCache.key(base.copy(printExposure = 2f), 33))
        assertNotEquals(key, PhotoLutCache.key(base.copy(interLayerAmount = .5f), 33))
        assertNotEquals(key, PhotoLutCache.key(base.copy(model = PhotoModel(scannerWhiteCorrection = true,
            scannerWhiteLevel = 1f, scannerFullWhiteExtension = true)), 33))
        assertNotEquals(key, PhotoLutCache.key(base, 65))
        assertNotEquals(key, PhotoLutCache.key(base.copy(model = base.model.copy(outputColorSpace = "ProPhoto RGB")), 33))
    }
}
