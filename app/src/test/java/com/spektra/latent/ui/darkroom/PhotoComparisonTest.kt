package com.spektra.latent.ui.darkroom

import org.junit.Assert.*
import org.junit.Test

class PhotoComparisonTest {
    @Test fun sourceCropKeepsTopLeftClockwiseOrderInBothOrientations() {
        val landscape = PhotoComparison.identity().apply { this[1] = .1f; this[3] = .9f }
        assertArrayEquals(floatArrayOf(0f, 80f, 1200f, 80f, 1200f, 720f, 0f, 720f), PhotoComparison.from(landscape)!!.sourceCorners(1200, 800), .001f)
        val portrait = PhotoComparison.identity().apply { this[0] = .1f; this[2] = .9f }
        assertArrayEquals(floatArrayOf(80f, 0f, 720f, 0f, 720f, 1200f, 80f, 1200f), PhotoComparison.from(portrait)!!.sourceCorners(800, 1200), .001f)
    }

    @Test fun rotatedPrintCoordinatesKeepTheirOriginalCornersWithoutABoundingBox() {
        val raw = floatArrayOf(0f, 0f, 1f, 1f, .15f, .09f, .91f, .15f, .85f, .91f, .09f, .85f)
        assertArrayEquals(floatArrayOf(150f, 72f, 910f, 120f, 850f, 728f, 90f, 680f), PhotoComparison.from(raw)!!.printCorners(1000f, 800f), .001f)
    }

    @Test fun screenDividerStaysFixedWhenBothPicturesAreZoomedAndPanned() {
        for (fraction in listOf(0f, .2f, .5f, 1f)) {
            for (scale in listOf(1f, 3f, 8f)) {
                for (pan in listOf(-240f, 0f, 240f)) {
                    val imageX = PhotoComparison.imageSplitX(fraction, 800f, 600f, scale, pan)
                    val screenX = 400f + pan + (imageX - 300f) * scale
                    assertEquals(fraction * 800f, screenX, .001f)
                }
            }
        }
    }

    @Test fun invalidGeometryIsRejectedAndDividerValuesStayFinite() {
        assertNull(PhotoComparison.from(floatArrayOf()))
        assertNull(PhotoComparison.from(PhotoComparison.identity().apply { this[2] = 0f }))
        assertNull(PhotoComparison.from(PhotoComparison.identity().apply { this[4] = Float.NaN }))
        assertNull(PhotoComparison.from(floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f)))
        assertEquals(0f, PhotoComparison.fraction(-1f), 0f)
        assertEquals(1f, PhotoComparison.fraction(2f), 0f)
        assertEquals(.5f, PhotoComparison.fraction(Float.NaN), 0f)
    }
}
