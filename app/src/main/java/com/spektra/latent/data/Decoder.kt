package com.spektra.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
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
import kotlin.math.roundToInt

/**
 * A file in the library -> a scene-linear picture.
 *
 * RAW goes to the engine, which develops it from the sensor data. Everything
 * else (JPEG, HEIF, PNG, WebP) is decoded by Android, which converts the
 * file's own ICC profile to extended linear sRGB without an 8-bit intermediate,
 * and applies its orientation. The two routes never mix: a RAW is not shown through its
 * embedded JPEG unless the engine itself says that is all it could read.
 */
object Decoder {
    /** Oriented width and height, or null when the file is no picture this app reads. */
    fun size(file: File): IntArray? {
        if (FilmEngine.isRaw(file.path)) return FilmEngine.probe(file.path)
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
        val bitmap = raster(file, maxEdge) ?: throw IOException("无法读取这张图片")
        try {
            return FilmEngine.fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun raster(file: File, maxEdge: Int): Bitmap? {
        val decoded = if (Build.VERSION.SDK_INT >= 28) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
                    val long = max(info.size.width, info.size.height)
                    if (maxEdge in 1 until long) {
                        val k = maxEdge.toFloat() / long
                        decoder.setTargetSize(
                            max(1, (info.size.width * k).roundToInt()), max(1, (info.size.height * k).roundToInt()),
                        )
                    }
                }
            } catch (e: IOException) {
                null
            }
        } else {
            legacy(file, maxEdge)
        } ?: return null
        val target = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        val linear = if (decoded.config == Bitmap.Config.RGBA_F16) decoded else
            decoded.copy(Bitmap.Config.RGBA_F16, true).also { decoded.recycle() }
        if (linear.colorSpace != target) {
            // Canvas performs the profile conversion into an extended linear
            // destination, retaining negative and above-white float channels.
            val converted = Bitmap.createBitmap(linear.width, linear.height, Bitmap.Config.RGBA_F16, true, target)
            android.graphics.Canvas(converted).drawBitmap(linear, 0f, 0f, null)
            linear.recycle()
            return converted
        }
        return linear
    }

    /** Android 8: no ImageDecoder. BitmapFactory with the EXIF orientation applied by hand. */
    private fun legacy(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val long = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (maxEdge > 0) while (long / (sample * 2) >= maxEdge) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGBA_F16
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        }
        var bitmap = BitmapFactory.decodeFile(file.path, o) ?: return null
        val m = Matrix()
        when (orientation(file)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val scaledLong = max(bitmap.width, bitmap.height)
        if (maxEdge in 1 until scaledLong) m.postScale(maxEdge.toFloat() / scaledLong, maxEdge.toFloat() / scaledLong)
        if (!m.isIdentity) {
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (turned !== bitmap) bitmap.recycle()
            bitmap = turned
        }
        return bitmap
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
