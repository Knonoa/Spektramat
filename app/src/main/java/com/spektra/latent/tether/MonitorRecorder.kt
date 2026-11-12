package com.spektra.latent.tether

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Records exactly the frames the monitor shows, as an MP4: no microphone, and
 * not the camera's own recording. Each frame is drawn onto the encoder's
 * input and stamped with the moment it was shown.
 */
class MonitorRecorder(private val file: File, width: Int, height: Int) : Closeable {
    val width = width / 2 * 2
    val height = height / 2 * 2
    private val codec = MediaCodec.createEncoderByType("video/avc")
    private var muxer: MediaMuxer? = null
    private var started = false
    private var track = -1
    private var frames = 0
    private var origin = 0L
    private var lastPts = -1L
    private var closed = false
    private val surface: Surface
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0xb0000000.toInt() }
    private val lettering = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }

    init {
        var input: Surface? = null
        try {
            codec.configure(
                MediaFormat.createVideoFormat("video/avc", this.width, this.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (width >= 1000) 6_000_000 else 3_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                },
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            input = codec.createInputSurface()
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            surface = input
        } catch (e: Exception) {
            codec.release()
            input?.release()
            muxer?.release()
            file.delete()
            throw e
        }
    }

    /** One frame. [note], when given, is burnt into the top of the picture (the camera's settings). */
    fun frame(bitmap: Bitmap, note: String?) {
        check(!closed)
        drain(false)
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            surface.lockCanvas(null)
        }
        try {
            canvas.drawColor(Color.BLACK)
            val k = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val w = bitmap.width * k
            val h = bitmap.height * k
            canvas.drawBitmap(bitmap, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), picture)
            if (!note.isNullOrBlank()) {
                lettering.textSize = height * 0.035f
                canvas.drawRect(0f, 0f, width.toFloat(), height * 0.075f, shade)
                canvas.drawText(note, width * 0.02f, height * 0.052f, lettering)
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        frames++
        drain(false)
    }

    private fun drain(end: Boolean) {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException("视频收尾超时")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started)
                    track = muxer!!.addTrack(codec.outputFormat)
                    muxer!!.start()
                    started = true
                }
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(started)
                            if (lastPts < 0) origin = info.presentationTimeUs
                            info.presentationTimeUs = maxOf(lastPts + 1, info.presentationTimeUs - origin)
                            lastPts = info.presentationTimeUs
                            muxer!!.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Ends the file so that it plays by itself, also after the cable was pulled. A recording with no frames is removed. */
    override fun close() {
        if (closed) return
        closed = true
        var valid = false
        try {
            codec.signalEndOfInputStream()
            drain(true)
            if (started) {
                muxer!!.stop()
                valid = frames > 0 && lastPts >= 0
            }
        } finally {
            try {
                codec.stop()
            } finally {
                codec.release()
                surface.release()
                muxer?.release()
            }
            if (!valid) file.delete()
        }
    }
}
