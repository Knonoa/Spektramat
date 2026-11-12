package com.spektra.latent.engine

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Viewfinder approximation of the exact photo pipeline's pointwise stages.
 * Scene grade and live metering precede this cube on the GPU. Saved photos
 * always use FilmEngine.develop(), including spatial and random effects. */
class PreviewLut private constructor(
    private val id: Long,
    val size: Int,
    val metadata: JSONObject,
) : AutoCloseable {
    private var closed = false

    fun pixels(): ByteBuffer {
        check(!closed)
        return checkNotNull(Native.videoLutBuffer(id)).order(ByteOrder.nativeOrder())
    }

    /** Deterministic CPU sampler for validation; live camera frames use GLES. */
    fun render(source: Bitmap, grade: FloatArray, autoEv: Float,
               finish: FloatArray, finishTable: FloatArray): Bitmap {
        check(!closed)
        require(grade.size == FilmEngine.GRADE_FLOATS && grade.all { it.isFinite() } && autoEv.isFinite())
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Native.renderPreviewLut(id, source, grade, autoEv, finish, finishTable, out)?.let {
            out.recycle(); throw IOException(it)
        }
        return out
    }

    override fun close() {
        if (!closed) { closed = true; Native.freeVideoLut(id) }
    }

    companion object {
        const val SIZE = 97
        const val SHAPER_SCALE = 65536f
        const val SHAPER_LOG_RANGE = 24f

        /** No scene grade is baked: it must run before live metering and the
         * logarithmic shaper. Manual EV remains in the full film/print kernel. */
        fun bake(order: PrintOrder, size: Int = SIZE): PreviewLut {
            val id = Native.newVideoLut()
            try {
                Native.bakePreviewLut(id, order.film, order.paper, order.scanFilm,
                    order.floats(), order.model.floats(), size)?.let { throw IOException(it) }
                val metadata = JSONObject()
                    .put("mode", "PHOTO_VIEWFINDER_LUT")
                    .put("reference_commit", PhotoModel.REFERENCE_COMMIT)
                    .put("asset_key", FilmEngine.renderCacheKey)
                    .put("input", "graded scene-linear sRGB after live metering")
                    .put("output", "linear sRGB")
                    .put("shaper", "log2(1 + linear * 65536) / 24")
                    .put("size", size).put("film", order.film).put("paper", order.paper)
                    .put("exposure_ev", order.exposureEv.toDouble())
                    .put("print_exposure_compensation", order.model.printExposureCompensation)
                return PreviewLut(id, size, metadata)
            } catch (t: Throwable) { Native.freeVideoLut(id); throw t }
        }

        /** The reference's selected metering method after the same scene grade
         * used by the GPU. Source is a small, unprocessed camera snapshot. */
        fun meter(source: Bitmap, grade: FloatArray, model: PhotoModel): Float {
            if (!model.autoExposure) return 0f
            require(grade.size == 11 && grade.all { it.isFinite() })
            val method = PhotoModel.meteringMethods.indexOf(model.autoExposureMethod)
            require(method >= 0) { "Unknown metering method: ${model.autoExposureMethod}" }
            return FilmEngine.fromBitmap(source).use { Native.meterPreview(it.id, grade, method) }
        }

        /** Tightly packed, top-row-first encoded sRGB RGBA8 from a small raw
         * camera FBO; avoids allocating a Bitmap in the GL readback path. */
        fun meter(rgba: ByteBuffer, width: Int, height: Int, grade: FloatArray, model: PhotoModel): Float {
            if (!model.autoExposure) return 0f
            require(rgba.isDirect && width > 0 && height > 0 &&
                width.toLong() * height * 4 <= rgba.capacity())
            require(grade.size == 11 && grade.all { it.isFinite() })
            val method = PhotoModel.meteringMethods.indexOf(model.autoExposureMethod)
            require(method >= 0) { "Unknown metering method: ${model.autoExposureMethod}" }
            return Native.meterPreviewRgba(rgba, width, height, grade, method)
        }
    }
}
