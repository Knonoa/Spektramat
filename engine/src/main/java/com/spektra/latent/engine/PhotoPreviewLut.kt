package com.spektra.latent.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Display-only approximation of the full photo engine's pointwise stages.
 * Input remains scene-linear float32; source grade and manual EV run before
 * this cube. Export always uses developFloat() on the original instead. */
class PhotoPreviewLut private constructor(private val id: Long, val size: Int, val outputColorSpace: String) : AutoCloseable {
    private var closed = false

    fun pixels(): ByteBuffer {
        check(!closed)
        return checkNotNull(Native.videoLutBuffer(id)).order(ByteOrder.nativeOrder())
    }

    fun render(source: LinearImage, grade: FloatArray, exposureEv: Float,
               finish: FloatArray, finishTable: FloatArray): Bitmap {
        check(!closed)
        require(grade.size == FilmEngine.GRADE_FLOATS && grade.all { it.isFinite() } && exposureEv.isFinite())
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Native.renderPhotoPreviewLut(id, source.id, grade, exposureEv, finish, finishTable, displayMatrix(outputColorSpace), output)?.let {
            output.recycle(); throw IOException(it)
        }
        return output
    }

    override fun close() {
        if (!closed) { closed = true; Native.freeVideoLut(id) }
    }

    companion object {
        const val SHAPER_SCALE = 16f
        const val SHAPER_LOG_RANGE = 12f
        const val SHAPER_GLSL = "(vec3(0.5) + 0.5 * sign(linear) * (log2(vec3(0.0625) + abs(linear)) + 4.0) / 12.0)"
        private val NO_REBATE = Rebate()
        private val displayMatrices = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()

        /** A cache identity contains only stages actually baked into the cube.
         * Float scene corrections, manual EV, geometry and spatial effects do
         * not invalidate it. Material-reference white mapping does. */
        fun canonicalOrder(order: PrintOrder): PrintOrder = order.copy(
            exposureEv = 0f, halation = false, halationAmount = 0f, scatter = 0f,
            grain = false, grainAmount = 0f, glare = false, glareAmount = 0f,
            sharpen = false, filmFormatMm = 35f, seed = 0L, rebate = NO_REBATE,
            model = order.model.copy(
                autoExposure = false, printExposureCompensation = false,
                cameraLensBlurUm = 0f, boostEv = 0f, dirDiffusionUm = 0f, dirTailUm = 0f,
                scatterSpatialScale = 1f, halationSpatialScale = 1f,
                scatterCoreUm = listOf(2.2f, 2f, 1.6f), scatterTailUm = listOf(9.3f, 9.7f, 9.1f),
                scatterTailWeight = listOf(.78f, .65f, .67f), boostRange = .3f, protectEv = 4f,
                stockHalation = true, halationStrength = listOf(.05f, .015f, 0f),
                halationSigmaUm = listOf(65f, 65f, 65f), halationBounces = 3,
                halationDecay = .5f, halationRenormalize = true,
                grainSubLayers = true, particleAreaUm2 = .2f,
                particleScale = listOf(1.6f, 1.6f, 3.2f), particleScaleLayers = listOf(2f, 1f, .5f),
                // Density minimum also participates in print/scan calibration
                // even with random grain disabled, so retain the user's value.
                uniformity = listOf(.97f, .99f, .97f),
                grainBlur = .65f, dyeCloudBlurUm = 1f, microBlurUm = .2f, microSigmaNm = 30f,
                grainLayerCount = 1, glarePercent = .03f, glareRoughness = .7f, glareBlur = .5f,
                dirTailWeight = .06f, unsharpSigma = .7f,
                scannerLensBlur = 0f, unsharpAmount = 0f,
                cameraDiffusion = DiffusionModel(), enlargerDiffusion = DiffusionModel(),
                outputCctfEncoding = false,
            ),
        )

        /** Stable across app restarts: Rebate is deliberately not a data class
         * and its default toString contains an allocation identity. */
        fun cacheIdentity(order: PrintOrder): String = canonicalOrder(order).let {
            listOf("photo-signed-log-v1", it.film, it.paper, it.scanFilm.toString(),
                it.floats().joinToString(","), it.model.floats().joinToString(","),
                it.model.outputColorSpace).joinToString("|")
        }

        fun bake(order: PrintOrder, size: Int = 33): PhotoPreviewLut {
            require(size in 17..129)
            val normalized = canonicalOrder(order)
            val id = Native.newVideoLut()
            try {
                Native.bakePhotoPreviewLut(id, normalized.film, normalized.paper, normalized.scanFilm,
                    normalized.floats(), normalized.model.floats(), normalized.model.outputColorSpace, size)?.let { throw IOException(it) }
                return PhotoPreviewLut(id, size, normalized.model.outputColorSpace)
            } catch (failure: Throwable) { Native.freeVideoLut(id); throw failure }
        }

        fun fromBuffer(size: Int, pixels: ByteBuffer, outputColorSpace: String = "sRGB"): PhotoPreviewLut {
            require(size in 17..129 && pixels.isDirect && pixels.remaining() == size * size * size * 12)
            require(outputColorSpace in PhotoModel.outputColorSpaces)
            val id = Native.newVideoLut()
            try {
                Native.loadPhotoPreviewLut(id, size, pixels.slice().order(ByteOrder.nativeOrder()))?.let { throw IOException(it) }
                return PhotoPreviewLut(id, size, outputColorSpace)
            } catch (failure: Throwable) { Native.freeVideoLut(id); throw failure }
        }

        /** Row-major linear conversion after finishing. Primaries and white
         * adaptation match Android's colour-managed full proof; no pixel is
         * sent through a clamping ColorSpace connector while deriving it. */
        fun displayMatrix(outputColorSpace: String): FloatArray = displayMatrices.getOrPut(outputColorSpace) {
            if (outputColorSpace == "sRGB") return@getOrPut floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            val source = ColorSpace.get(when (outputColorSpace) {
                "Display P3" -> ColorSpace.Named.DISPLAY_P3
                "Adobe RGB (1998)" -> ColorSpace.Named.ADOBE_RGB
                "ProPhoto RGB" -> ColorSpace.Named.PRO_PHOTO_RGB
                else -> error("Unsupported photo output colour space: $outputColorSpace")
            }) as ColorSpace.Rgb
            val target = ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
            val adapted = ColorSpace.adapt(source, target.whitePoint) as ColorSpace.Rgb
            val toXyz = adapted.transform
            val fromXyz = target.inverseTransform
            FloatArray(9) { i ->
                val row = i / 3; val column = i % 3
                (0..2).sumOf { k -> (fromXyz[row + 3 * k] * toXyz[k + 3 * column]).toDouble() }.toFloat()
            }
        }
    }
}
