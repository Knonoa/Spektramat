package com.spektra.latent.ui.darkroom

internal enum class ComparisonMode(val label: String) {
    Hold("长按查看"), Split("滑动对比")
}

/** Exact source crop and its four corners in a print; all coordinates are normalized. */
internal class PhotoComparison private constructor(private val geometry: FloatArray) {
    fun sourceCorners(width: Int, height: Int) = floatArrayOf(
        geometry[0] * width, geometry[1] * height,
        geometry[2] * width, geometry[1] * height,
        geometry[2] * width, geometry[3] * height,
        geometry[0] * width, geometry[3] * height,
    )

    fun printCorners(width: Float, height: Float) = FloatArray(8) { i ->
        geometry[4 + i] * if (i % 2 == 0) width else height
    }

    companion object {
        fun identity() = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

        fun from(raw: FloatArray): PhotoComparison? {
            if (raw.size != 12 || raw.any { !it.isFinite() }) return null
            if (raw.take(4).any { it !in 0f..1f } || raw[0] >= raw[2] || raw[1] >= raw[3]) return null
            // A source must keep its handedness. Reject collapsed or mirrored quads.
            val cross = (raw[6] - raw[4]) * (raw[11] - raw[5]) - (raw[7] - raw[5]) * (raw[10] - raw[4])
            if (cross <= 0f) return null
            return PhotoComparison(raw.copyOf())
        }

        fun fraction(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else .5f

        /** A stationary screen divider expressed in the zoomed image's local coordinates. */
        fun imageSplitX(portion: Float, viewportWidth: Float, fitWidth: Float, scale: Float, panX: Float): Float {
            if (!viewportWidth.isFinite() || !fitWidth.isFinite() || !scale.isFinite() || !panX.isFinite() || scale <= 0f) return 0f
            return ((fraction(portion) - .5f) * viewportWidth - panX) / scale + fitWidth / 2f
        }
    }
}
