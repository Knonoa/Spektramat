package com.spektra.latent.engine

import org.json.JSONObject
import org.json.JSONArray
import android.graphics.Bitmap
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Recording-only cube sampled from the same exact engine as photographs. */
class VideoLut private constructor(
    private val id: Long,
    val size: Int,
    val metadata: JSONObject,
) : AutoCloseable {
    private var closed = false

    fun pixels(): ByteBuffer = checkNotNull(Native.videoLutBuffer(id)).order(ByteOrder.nativeOrder())

    /** CPU sampler for tether recording; ordinary monitoring uses develop(). */
    fun render(source: Bitmap, finish: FloatArray, finishTable: FloatArray): Bitmap {
        check(!closed)
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Native.renderVideoLut(id, source, finish, finishTable, out)?.let {
            out.recycle(); throw IOException(it)
        }
        return out
    }

    override fun close() {
        if (!closed) { closed = true; Native.freeVideoLut(id) }
    }

    companion object {
        const val SIZE = 65
        const val PRESET = "pointwise-exact-v1"

        /** Input is the camera's SDR sRGB signal; grade/exposure are baked before
         * the full film, exact print and exact scan. Spatial and stochastic
         * effects are explicitly disabled by this video preset. */
        fun bake(order: PrintOrder, grade: FloatArray, size: Int = SIZE): VideoLut {
            val id = Native.newVideoLut()
            try {
                Native.bakeVideoLut(id, order.film, order.paper, order.scanFilm,
                    order.floats(), grade, order.model.floats(), size)?.let { throw IOException(it) }
                val pixels = checkNotNull(Native.videoLutBuffer(id))
                val digest = MessageDigest.getInstance("SHA-256")
                val copy = pixels.duplicate()
                val chunk = ByteArray(64 * 1024)
                while (copy.hasRemaining()) {
                    val count = minOf(copy.remaining(), chunk.size)
                    copy.get(chunk, 0, count); digest.update(chunk, 0, count)
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val metadata = JSONObject()
                    .put("mode", "VIDEO_LUT").put("preset", PRESET)
                    .put("reference_commit", PhotoModel.REFERENCE_COMMIT)
                    .put("asset_key", FilmEngine.renderCacheKey)
                    .put("input", "sRGB SDR encoded 0..1").put("output", "linear sRGB")
                    .put("shaper", "sRGB CCTF").put("size", size).put("sha256", hash)
                    .put("film", order.film).put("paper", order.paper).put("scan_film", order.scanFilm)
                    .put("exposure_ev", order.exposureEv.toDouble()).put("print_exposure", order.printExposure.toDouble())
                    .put("filter_y", order.yShift.toDouble()).put("filter_m", order.mShift.toDouble())
                    .put("grade", JSONArray(grade.toList()))
                    .put("render_parameters", JSONArray(order.floats().toList()))
                    .put("model", order.model.toJson())
                    .put("disabled_effects", JSONArray(listOf("auto_exposure", "halation_scatter", "diffusion",
                        "lens_blur", "dir_diffusion", "grain", "glare", "unsharp", "highlight_boost", "black_white_correction")))
                return VideoLut(id, size, metadata)
            } catch (t: Throwable) { Native.freeVideoLut(id); throw t }
        }
    }
}
