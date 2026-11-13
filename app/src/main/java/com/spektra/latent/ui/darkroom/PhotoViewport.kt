package com.spektra.latent.ui.darkroom

import com.spektra.latent.gl.Placement
import kotlin.math.min

/** The unzoomed photograph fitted inside a preview viewport, in physical pixels. */
internal data class PhotoFit(val width: Float, val height: Float)

/** Geometry shared by rendering and gestures. Every coordinate is local to the clipped viewport. */
internal object PhotoViewport {
    const val MAX_ZOOM = 8f

    fun fit(width: Float, height: Float, imageW: Int, imageH: Int): PhotoFit {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f || imageW <= 0 || imageH <= 0) {
            return PhotoFit(0f, 0f)
        }
        val ratio = min(width.toDouble() / imageW, height.toDouble() / imageH)
        return PhotoFit((imageW * ratio).toFloat(), (imageH * ratio).toFloat())
    }

    /** At fit, dragging has no effect. After zooming only the overflowing axes may move. */
    fun clamp(p: Placement, width: Float, height: Float, imageW: Int, imageH: Int): Placement {
        val scale = if (p.scale.isFinite()) p.scale.coerceIn(1f, MAX_ZOOM) else 1f
        val fitted = fit(width, height, imageW, imageH)
        val maxX = if (fitted.width == 0f) 0f else ((fitted.width.toDouble() * scale - width) / 2).coerceAtLeast(0.0).toFiniteFloat()
        val maxY = if (fitted.height == 0f) 0f else ((fitted.height.toDouble() * scale - height) / 2).coerceAtLeast(0.0).toFiniteFloat()
        return p.copy(
            scale = scale,
            panX = p.panX.finiteOr(0f).coerceIn(-maxX, maxX),
            panY = p.panY.finiteOr(0f).coerceIn(-maxY, maxY),
            insetLeft = 0f, insetTop = 0f, insetRight = 0f, insetBottom = 0f,
        )
    }

    /** Keeps the image point under the gesture centroid fixed, subject to the viewport edges. */
    fun transform(
        p: Placement,
        width: Float,
        height: Float,
        imageW: Int,
        imageH: Int,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
    ): Placement {
        val current = clamp(p, width, height, imageW, imageH)
        val multiplier = if (zoom.isFinite() && zoom > 0f) zoom else 1f
        val scale = (current.scale.toDouble() * multiplier).coerceIn(1.0, MAX_ZOOM.toDouble()).toFloat()
        val k = scale.toDouble() / current.scale
        val cx = centroidX.finiteOr(width.finiteOr(0f) / 2f).toDouble() - width.finiteOr(0f).toDouble() / 2
        val cy = centroidY.finiteOr(height.finiteOr(0f) / 2f).toDouble() - height.finiteOr(0f).toDouble() / 2
        return clamp(
            current.copy(
                scale = scale,
                panX = ((current.panX - cx) * k + cx + panX.finiteOr(0f)).toFiniteFloat(),
                panY = ((current.panY - cy) * k + cy + panY.finiteOr(0f)).toFiniteFloat(),
            ),
            width, height, imageW, imageH,
        )
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    private fun Double.toFiniteFloat(): Float = coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
}
