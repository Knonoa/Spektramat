package com.spektra.latent.ui.darkroom

import com.spektra.latent.gl.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoViewportTest {
    @Test fun fitKeepsTheWholePhotographAndItsAspect() {
        for ((viewW, viewH) in listOf(360f to 440f, 580f to 240f, 1200f to 800f)) {
            for ((imageW, imageH) in listOf(4000 to 3000, 3000 to 4000, 6000 to 1000, 1000 to 6000)) {
                val f = PhotoViewport.fit(viewW, viewH, imageW, imageH)
                assertTrue(f.width <= viewW + .001f && f.height <= viewH + .001f)
                assertEquals(imageW.toFloat() / imageH, f.width / f.height, .0001f)
                assertTrue(kotlin.math.abs(f.width - viewW) < .001f || kotlin.math.abs(f.height - viewH) < .001f)
            }
        }
    }

    @Test fun atFitNeitherAxisCanBeDragged() {
        for ((imageW, imageH) in listOf(4000 to 3000, 3000 to 4000, 6000 to 1000)) {
            val p = PhotoViewport.clamp(Placement(panX = 900f, panY = -900f), 360f, 440f, imageW, imageH)
            assertEquals(0f, p.panX, 0f)
            assertEquals(0f, p.panY, 0f)
        }
    }

    @Test fun zoomedWidePhotoStillCannotMoveAlongItsLetterboxedAxis() {
        val p = PhotoViewport.transform(Placement(), 360f, 440f, 4000, 1000, 180f, 220f, 1000f, 1000f, 2f)
        assertEquals(2f, p.scale, 0f)
        assertEquals(180f, p.panX, .001f)
        assertEquals(0f, p.panY, 0f)
    }

    @Test fun draggingStopsAtEachOfTheFourImageEdges() {
        for ((imageW, imageH) in listOf(4000 to 3000, 3000 to 4000, 6000 to 1000)) {
            for (sign in listOf(-1f, 1f)) {
                val p = PhotoViewport.clamp(Placement(scale = 8f, panX = sign * 1e6f, panY = sign * 1e6f), 360f, 440f, imageW, imageH)
                val f = PhotoViewport.fit(360f, 440f, imageW, imageH)
                assertEquals(sign * ((f.width * 8f - 360f) / 2f).coerceAtLeast(0f), p.panX, .001f)
                assertEquals(sign * ((f.height * 8f - 440f) / 2f).coerceAtLeast(0f), p.panY, .001f)
            }
        }
    }

    @Test fun zoomPreservesThePointUnderTheFingersWhenEdgesPermitIt() {
        val before = Placement(scale = 2f, panX = 20f, panY = -10f)
        val centroidX = 230f
        val centroidY = 180f
        val after = PhotoViewport.transform(before, 400f, 400f, 4000, 4000, centroidX, centroidY, 0f, 0f, 1.5f)
        val beforeX = (centroidX - 200f - before.panX) / before.scale
        val beforeY = (centroidY - 200f - before.panY) / before.scale
        assertEquals(beforeX, (centroidX - 200f - after.panX) / after.scale, .001f)
        assertEquals(beforeY, (centroidY - 200f - after.panY) / after.scale, .001f)
    }

    @Test fun zoomLimitsAndReturnToFitRestoreCenteredPlacement() {
        val inwards = PhotoViewport.transform(Placement(), 360f, 440f, 4000, 3000, 220f, 200f, 100f, 100f, 100f)
        assertEquals(8f, inwards.scale, 0f)
        val outwards = PhotoViewport.transform(inwards, 360f, 440f, 4000, 3000, 220f, 200f, 100f, 100f, .001f)
        assertEquals(1f, outwards.scale, 0f)
        assertEquals(0f, outwards.panX, 0f)
        assertEquals(0f, outwards.panY, 0f)
    }

    @Test fun rotationReclampsTheOffsetToTheNewViewport() {
        val portrait = Placement(scale = 3f, panX = 200f, panY = 200f)
        val landscape = PhotoViewport.clamp(portrait, 620f, 220f, 3000, 4000)
        assertEquals(0f, landscape.panX, 0f)
        assertEquals(200f, landscape.panY, .001f)
        assertEquals(0f, landscape.insetTop, 0f)
    }

    @Test fun missingDimensionsAndInvalidGestureValuesNeverProduceNonFiniteGeometry() {
        assertEquals(PhotoFit(0f, 0f), PhotoViewport.fit(0f, 440f, 4000, 3000))
        assertEquals(PhotoFit(0f, 0f), PhotoViewport.fit(360f, Float.NaN, 4000, 3000))
        assertEquals(PhotoFit(0f, 0f), PhotoViewport.fit(360f, 440f, 0, 3000))
        for (viewport in listOf(360f to 440f, 0f to 0f, Float.POSITIVE_INFINITY to Float.NaN)) {
            val p = PhotoViewport.transform(
                Placement(scale = Float.NaN, panX = Float.POSITIVE_INFINITY, panY = Float.NaN),
                viewport.first, viewport.second, 4000, 3000,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NEGATIVE_INFINITY, Float.NaN,
            )
            assertTrue(p.scale.isFinite() && p.panX.isFinite() && p.panY.isFinite())
            assertTrue(p.scale in 1f..PhotoViewport.MAX_ZOOM)
        }
    }
}
