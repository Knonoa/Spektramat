package com.spektra.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.LinearImage
import java.io.File
import com.spektra.latent.model.Aspects
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

/** Original files decode into float32 linear RGB. Native readers preserve RAW,
 * JPEG / PNG and TIFF precision and profiles; platform 8-bit formats are
 * colour-converted before proxy scaling. No display bitmap feeds export. */
object Decoder {
    /** Oriented width and height, or null when the file is no picture this app reads. */
    fun size(file: File): IntArray? {
        if (kind(file) == 1 || FilmEngine.isRaw(file.path)) return FilmEngine.probe(file.path)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return if (quarterTurned(file)) intArrayOf(o.outHeight, o.outWidth) else intArrayOf(o.outWidth, o.outHeight)
    }

    /**
     * The file cut to the shape [aspect] (Aspects; empty for all of it). [maxEdge] is the
     * long edge of what comes back, so a cut picture is as sharp as an uncut one.
     * @throws IOException with a reason a person can read
     */
    fun decode(file: File, maxEdge: Int, lensShading: Boolean, aspect: String): LinearImage {
        if (aspect.isEmpty()) return decode(file, maxEdge, lensShading)
        val size = size(file) ?: throw IOException("无法读取这张图片")
        val cut = Aspects.rect(size[0], size[1], aspect)
        if (cut[2] == size[0] && cut[3] == size[1]) return decode(file, maxEdge, lensShading)
        val edge = if (maxEdge <= 0) 0 else {
            val grown = ceil(maxEdge.toDouble() * max(size[0], size[1]) / max(cut[2], cut[3])).toInt()
            if (grown >= max(size[0], size[1])) 0 else grown
        }
        return decode(file, edge, lensShading).use { whole ->
            val r = Aspects.rect(whole.width, whole.height, aspect)
            whole.cropped(r[0], r[1], r[2], r[3])
        }
    }

    /**
     * @param maxEdge the long edge is at most this; 0 for the native size
     * @throws IOException with a reason a person can read
     */
    fun decode(file: File, maxEdge: Int, lensShading: Boolean = true): LinearImage {
        if (FilmEngine.isRaw(file.path)) return FilmEngine.decode(file.path, maxEdge, lensShading)
        if (kind(file) == 1) return FilmEngine.decode(file.path, maxEdge, lensShading)
        if (kind(file) != 2) throw IOException("此格式尚无保留原始精度的解码器，请使用 PNG、TIFF、JPEG 或支持的 RAW")
        val bitmap = platform8(file) ?: throw IOException("无法读取这张图片")
        try {
            return FilmEngine.fromEncodedBitmap(bitmap).use { it.scaled(maxEdge) }
        } finally {
            bitmap.recycle()
        }
    }

    /** The remaining platform formats have at most 8-bit encoded RGB samples.
     * ICC conversion happens in float32, before linear-light proxy scaling. */
    private fun platform8(file: File): Bitmap? {
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                info.colorSpace?.let { decoder.setTargetColorSpace(it) }
                decoder.isUnpremultipliedRequired = true
            }
        } else {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
            })?.let { bitmap ->
                val matrix = Matrix()
                when (orientation(file)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                }
                if (matrix.isIdentity) bitmap else
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
                        .also { if (it !== bitmap) bitmap.recycle() }
            }
        } ?: return null
        if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.recycle()
            throw IOException("此格式尚无保留原始精度的解码器，请使用 PNG、TIFF、JPEG 或支持的 RAW")
        }
        return decoded
    }

    private fun kind(file: File): Int {
        val bytes = ByteArray(12)
        val count = file.inputStream().use { it.read(bytes) }
        if (count < 4) return 0
        fun code(i: Int) = bytes[i].toInt() and 255
        if (code(0) == 255 && code(1) == 216 ||
            code(0) == 137 && bytes.copyOfRange(1, 4).toString(Charsets.US_ASCII) == "PNG" ||
            bytes.copyOfRange(0, 2).toString(Charsets.US_ASCII) in listOf("II", "MM")) return 1
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) in listOf("GIF8", "RIFF") ||
            bytes.copyOfRange(0, 2).toString(Charsets.US_ASCII) == "BM") return 2
        // Unknown / high precision platform formats must not silently be
        // quantized by Android's display decoder and then used for export.
        return 0
    }
    private fun orientation(file: File): Int = try {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun quarterTurned(file: File) = when (orientation(file)) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
        else -> false
    }
}
