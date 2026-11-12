package com.spektra.latent.tether

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import kotlin.math.sin

/** A picture waiting in the camera: its name there, its size in bytes, its PTP format code, its pixels. */
class CameraObject(val name: String, val bytes: Long, val format: Int, val width: Long, val height: Long)

/** What the camera is set to right now, as it would show it; empty where it does not say. */
class Readings(val iso: String = "", val aperture: String = "", val shutter: String = "") {
    val line: String get() = listOf(iso, aperture, shutter).filter { it.isNotEmpty() }.joinToString("  ")
}

/**
 * A camera on a cable: it shows what its lens sees, says how it is set, and
 * hands over each photograph it takes. All of it is called from one thread.
 */
interface TetherCamera : Closeable {
    val model: String
    /** What the person should know about how the camera is set up, once connected. */
    val setupNote: String
    fun connect()
    fun poll(): Readings
    fun hasPhoto(): Boolean
    fun photoInfo(): CameraObject
    fun receive(file: File, info: CameraObject, progress: (Long) -> Unit)
    /** One frame of the live view as a JPEG; null when the camera has none just now. */
    fun liveJpeg(): ByteArray?
    val canRelease: Boolean
    fun release()
    fun finish()
}

/**
 * A stand-in camera for development builds: it looks slowly across one
 * picture and "takes" that picture when released. It lets the monitor be
 * exercised -- live view, film look, recording, receiving -- where there is
 * no camera to plug in. It proves nothing about any real camera.
 */
class DemoCamera(private val picture: File) : TetherCamera {
    private var source: Bitmap? = null
    private var started = 0L
    private var pending = false
    private val out = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val model = "演示相机"
    override val setupNote = "这是调试版里的演示相机：画面来自片库里的一张照片，按快门会把这张照片当作新拍的传过来。"

    override fun connect() {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(picture.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        source = BitmapFactory.decodeFile(picture.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw java.io.IOException("演示相机读不了这张照片")
        started = System.currentTimeMillis()
    }

    override fun poll() = Readings("ISO 400", "f/2.8", "1/125")
    override fun hasPhoto() = pending
    override fun photoInfo() = CameraObject("DEMO_${System.currentTimeMillis() % 100000}.JPG", picture.length(), 0x3801, 0, 0)

    override fun receive(file: File, info: CameraObject, progress: (Long) -> Unit) {
        pending = false
        picture.inputStream().use { input ->
            file.outputStream().use { output ->
                val block = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(block)
                    if (n < 0) break
                    output.write(block, 0, n)
                    done += n
                    progress(done)
                }
            }
        }
    }

    override fun liveJpeg(): ByteArray? {
        val src = source ?: return null
        Thread.sleep(33)
        val t = (System.currentTimeMillis() - started) / 1000f
        // a window of the picture, three by two, drifting from side to side
        val k = minOf(src.width / 3f, src.height / 2f) * 0.86f
        val w = (3f * k).toInt()
        val h = (2f * k).toInt()
        val x = ((src.width - w) * (0.5f + 0.5f * sin(t * 0.5f))).toInt()
        val y = ((src.height - h) * (0.5f + 0.5f * sin(t * 0.31f))).toInt()
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(src, Rect(x, y, x + w, y + h), Rect(0, 0, out.width, out.height), paint)
        return ByteArrayOutputStream(96 * 1024).also { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }

    override val canRelease = true
    override fun release() {
        pending = true
    }

    override fun finish() = close()
    override fun close() {
        source?.recycle()
        source = null
    }
}
