package com.spektra.latent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.data.PhoneImageFormat.Kind
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
    /** Oriented dimensions from the same format policy and orientation path as decode. */
    fun size(file: File): IntArray? = try { probe(file) } catch (_: IOException) { null }

    fun probe(file: File): IntArray? = when (val kind = supportedKind(file)) {
        Kind.Native, Kind.Jpeg -> FilmEngine.probe(file.path)
        else -> if (Build.VERSION.SDK_INT >= 28) {
            // Stop at the header; ImageInfo already includes the encoded orientation.
            var dimensions: IntArray? = null
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { _, info, _ ->
                    dimensions = intArrayOf(info.size.width, info.size.height)
                    throw HeaderRead()
                }.recycle()
            } catch (_: HeaderRead) {
            } catch (e: ImageDecoder.DecodeException) {
                throw unreadable(kind, e)
            }
            dimensions ?: throw unreadable(kind)
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) null
            else if (quarterTurned(file)) intArrayOf(options.outHeight, options.outWidth)
            else intArrayOf(options.outWidth, options.outHeight)
        }
    }

    private class HeaderRead : RuntimeException()

    /**
     * The file cut to the shape [aspect] (Aspects; empty for all of it). [maxEdge] is the
     * long edge of what comes back, so a cut picture is as sharp as an uncut one.
     * @throws IOException with a reason a person can read
     */
    fun decode(file: File, maxEdge: Int, lensShading: Boolean, aspect: String): LinearImage {
        if (aspect.isEmpty()) return decode(file, maxEdge, lensShading)
        val size = probe(file) ?: throw IOException("无法读取这张图片")
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
        val kind = supportedKind(file)
        if (kind == Kind.Native || kind == Kind.Jpeg) return FilmEngine.decode(file.path, maxEdge, lensShading)
        val bitmap = try { platform8(file) } catch (e: ImageDecoder.DecodeException) {
            throw unreadable(kind, e)
        } ?: throw unreadable(kind)
        try {
            val original = FilmEngine.fromEncodedBitmap(bitmap)
            return if (maxEdge <= 0 || max(original.width, original.height) <= maxEdge) original
            else original.use { it.scaled(maxEdge) }
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
        if (decoded.config != Bitmap.Config.ARGB_8888 ||
            (Build.VERSION.SDK_INT >= 34 && decoded.hasGainmap())) {
            decoded.recycle()
            throw IOException(PhoneImageFormat.PRECISION_ERROR)
        }
        return decoded
    }

    private fun unreadable(kind: Kind, cause: IOException? = null) = IOException(when (kind) {
        Kind.Heif -> "无法解码这张 HEIC／HEIF 照片，请检查文件与设备支持"
        Kind.Avif -> "无法解码这张 AVIF 照片，请检查文件与设备支持"
        else -> "无法读取这张图片"
    }, cause)

    private fun supportedKind(file: File): Kind = when (val kind = PhoneImageFormat.kind(file)) {
        Kind.Jpeg -> kind.also { PhoneImageFormat.requireJpegSdr(file) }
        Kind.Heif, Kind.Avif -> {
            if (Build.VERSION.SDK_INT < (if (kind == Kind.Heif) 28 else 31)) {
                throw IOException(if (kind == Kind.Heif) "HEIC／HEIF 需要 Android 9 或更新版本" else "AVIF 需要 Android 12 或更新版本")
            }
            kind.also { PhoneImageFormat.requireSdr8(file, kind) }
        }
        Kind.Unknown -> if (FilmEngine.isRaw(file.path)) Kind.Native else throw IOException(
            "不支持此图片格式，请选择 JPEG、PNG、HEIC／HEIF、WebP、AVIF 或支持的 RAW"
        )
        else -> kind
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
