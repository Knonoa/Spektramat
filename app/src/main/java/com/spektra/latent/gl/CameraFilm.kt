package com.spektra.latent.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.RectF
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.spektra.latent.BuildConfig
import com.spektra.latent.Journal
import com.spektra.latent.engine.PreviewLut
import com.spektra.latent.engine.VideoLut
import com.spektra.latent.model.Look
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Live GPU viewfinder plus a separate VIDEO_CAPTURE effect. Saved photographs
 * never enter either cube; ImageCapture keeps the untouched JPEG/RAW. */
class CameraFilm(context: Context) : SurfaceProcessor {
    private val app = context.applicationContext
    private val videoGpu = VideoLutGpu()
    private val previewGpu = PreviewLutGpu()
    private val finish = FinishGpu()
    private var videoProgram: Program? = null
    private var previewProgram: Program? = null
    private var rawProgram: Program? = null
    private val inputs = HashMap<SurfaceTexture, Int>()
    private val previewStreams = HashSet<SurfaceTexture>()
    private val previewCallbacks = HashMap<SurfaceTexture, ((Int, Int, Boolean, Look) -> Unit)?>()
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val cameraMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val turn = FloatArray(16)
    private val inverseCamera = FloatArray(16)
    private val previewTransform = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val baker = Executors.newSingleThreadExecutor()
    private val baking = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var released = false
    @Volatile private var videoLook: Look? = null
    @Volatile private var previewLook: Look? = null
    private var meterTime = 0L
    private var meterEv = 0f
    private var targetEv = 0f
    private var meterReady = false
    private var meterLook: Look? = null
    private var meterTexture = 0
    private var meterFbo = 0
    private var meterWidth = 0
    private var meterHeight = 0
    private var meterBuffer: ByteBuffer? = null
    private var gradeLook: Look? = null
    private var previewGrade = FloatArray(11)
    private var window: EGLSurface? = null
    private var windowW = 0
    private var windowH = 0
    private var directStream: SurfaceTexture? = null
    private var directSize = Size(4, 3)
    private var directRotation = 0
    private var directMirror = false

    @Volatile var look: Look = Look()
        set(value) { field = value; prepare() }
    @Volatile var active = false
        set(value) { field = value; if (value) prepare() }
    @Volatile var videoEnabled = false
        set(value) { field = value; if (value) prepare() }
    @Volatile var recording = false
    @Volatile var displayDegrees = 0
    @Volatile var onFailure: ((String) -> Unit)? = null
    @Volatile var onPreviewFrame: ((Int, Int, Boolean, Look) -> Unit)? = null

    fun readyFor(value: Look): Boolean = value == videoLook && videoEnabled && active && !released

    /** The newest look wins; no GL operation or LUT integration runs on UI. */
    private fun prepare() {
        if (released || !active || (previewLook == look && (!videoEnabled || videoLook == look)) ||
            !baking.compareAndSet(false, true)) return
        baker.execute {
            var handedOff = false
            var failed = false
            try {
                while (!released && active) {
                    val wanted = look
                    var preview: PreviewLut? = null
                    var video: VideoLut? = null
                    try {
                        if (previewLook != wanted) preview = PreviewLut.bake(wanted.order(0L))
                        // Publish the live look first; preparing the encoder's
                        // separate cube must not delay the viewfinder update.
                        if (preview == null && videoEnabled && videoLook != wanted)
                            video = VideoLut.bake(wanted.order(0L), wanted.grade(0f))
                        if (released || !active || wanted != look) continue
                        val nextPreview = preview
                        val nextVideo = video
                        GlCore.handler.post {
                            var uploadFailed = false
                            try {
                                if (!released && active && wanted == look) {
                                    check(GlCore.makeCurrent(null)) { "GPU 预览上下文不可用" }
                                    nextPreview?.let { previewGpu.upload(it); previewLook = wanted }
                                    if (videoEnabled) nextVideo?.let { videoGpu.upload(it); videoLook = wanted }
                                }
                            } catch (t: Throwable) {
                                uploadFailed = true
                                Journal.e(TAG, "camera LUT upload failed", t)
                                onFailure?.invoke(t.message ?: "胶片预览生成失败")
                            } finally {
                                nextPreview?.close(); nextVideo?.close()
                                baking.set(false)
                                if (!uploadFailed) prepare()
                            }
                        }
                        preview = null; video = null
                        handedOff = true
                        break
                    } finally { preview?.close(); video?.close() }
                }
            } catch (t: Throwable) {
                failed = true
                Journal.e(TAG, "camera LUT bake failed", t)
                onFailure?.invoke(t.message ?: "胶片预览生成失败")
            } finally {
                if (!handedOff) {
                    baking.set(false)
                    if (!failed) prepare()
                }
            }
        }
    }

    fun resetPreview() = GlCore.post {
        directStream = null
        meterLook = null; meterReady = false; meterEv = 0f; targetEv = 0f; meterTime = 0L
    }

    override fun onInputSurface(request: SurfaceRequest) { open(request) }
    override fun onOutputSurface(output: SurfaceOutput) {
        if (released || GlCore.failure != null || output.targets != CameraEffect.VIDEO_CAPTURE) {
            output.close(); return
        }
        val surface = output.getSurface(GlCore.executor) {
            outputs.remove(output)?.let { GlCore.release(it) }
            output.close()
        }
        val egl = GlCore.windowSurface(surface)
        if (egl == null) { output.close(); onFailure?.invoke("录像输出不可用"); return }
        outputs[output] = egl
    }

    /** A plain Preview SurfaceProvider remains compatible with RAW capture. */
    val direct = Preview.SurfaceProvider { request ->
        val stream = open(request) ?: return@SurfaceProvider
        previewStreams.add(stream)
        previewCallbacks[stream] = onPreviewFrame
        directStream = stream
        directSize = request.resolution
        request.setTransformationInfoListener(GlCore.executor) { info ->
            if (directStream !== stream) return@setTransformationInfoListener
            directRotation = info.rotationDegrees
            directMirror = info.isMirroring
            val rect = info.cropRect
            directSize = Size(rect.width(), rect.height())
            // CameraX's SurfaceTexture already carries sensor rotation/front
            // mirroring. Cancel that predicted component before applying the
            // requested output transform; applying rotation twice turns phones
            // sideways even when the viewport's dimensions look correct.
            fun base(matrix: FloatArray, rotation: Int, mirror: Boolean) {
                Matrix.setIdentityM(matrix, 0)
                Matrix.translateM(matrix, 0, .5f, .5f, 0f)
                Matrix.scaleM(matrix, 0, 1f, -1f, 1f)
                Matrix.rotateM(matrix, 0, rotation.toFloat(), 0f, 0f, 1f)
                if (mirror) Matrix.scaleM(matrix, 0, -1f, 1f, 1f)
                Matrix.translateM(matrix, 0, -.5f, -.5f, 0f)
            }
            val cameraInfo = request.camera.cameraInfo
            base(turn, if (info.hasCameraTransform()) cameraInfo.sensorRotationDegrees else 0,
                info.hasCameraTransform() && cameraInfo.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT)
            check(Matrix.invertM(inverseCamera, 0, turn, 0))
            base(turn, directRotation, directMirror)
            val side = directRotation % 180 != 0
            val fullW = if (side) request.resolution.height else request.resolution.width
            val fullH = if (side) request.resolution.width else request.resolution.height
            val map = android.graphics.Matrix().apply {
                setRectToRect(RectF(0f, 0f, request.resolution.width.toFloat(), request.resolution.height.toFloat()),
                    RectF(-1f, -1f, 1f, 1f), android.graphics.Matrix.ScaleToFit.FILL)
                postRotate(directRotation.toFloat())
                if (directMirror) postScale(-1f, 1f)
                postConcat(android.graphics.Matrix().apply {
                    setRectToRect(RectF(-1f, -1f, 1f, 1f), RectF(0f, 0f, fullW.toFloat(), fullH.toFloat()),
                        android.graphics.Matrix.ScaleToFit.FILL)
                })
            }
            val rotatedCrop = RectF(rect); map.mapRect(rotatedCrop)
            Matrix.translateM(turn, 0, rotatedCrop.left / fullW, (fullH - rotatedCrop.bottom) / fullH, 0f)
            Matrix.scaleM(turn, 0, rotatedCrop.width() / fullW, rotatedCrop.height() / fullH, 1f)
            Matrix.multiplyMM(previewTransform, 0, inverseCamera, 0, turn, 0)
        }
    }

    fun attachWindow(target: SurfaceTexture, w: Int, h: Int) = GlCore.post {
        window?.let { GlCore.release(it) }
        window = GlCore.windowSurface(target)
        windowW = w; windowH = h
        if (window == null) onFailure?.invoke("GPU 取景窗口不可用")
    }
    fun resizeWindow(w: Int, h: Int) = GlCore.post { windowW = w; windowH = h }
    fun detachWindow() { GlCore.await { window?.let { GlCore.release(it) }; window = null } }

    fun release() {
        released = true; active = false
        baker.shutdown()
        GlCore.post {
            window?.let { GlCore.release(it) }; window = null
            GlCore.makeCurrent(null)
            videoGpu.release(); previewGpu.release(); finish.release()
            deleteTexture(meterTexture)
            if (meterFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(meterFbo), 0)
            for (p in listOfNotNull(videoProgram, previewProgram, rawProgram)) GLES30.glDeleteProgram(p.id)
        }
    }

    private fun open(request: SurfaceRequest): SurfaceTexture? {
        if (released || GlCore.failure != null || !GlCore.makeCurrent(null)) {
            request.willNotProvideSurface(); return null
        }
        val texture = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
        val stream = SurfaceTexture(texture)
        stream.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(stream)
        inputs[stream] = texture
        request.provideSurface(surface, GlCore.executor) {
            stream.setOnFrameAvailableListener(null)
            inputs.remove(stream)
            previewStreams.remove(stream)
            previewCallbacks.remove(stream)
            if (directStream === stream) directStream = null
            stream.release(); surface.release()
            if (GlCore.makeCurrent(null)) deleteTexture(texture)
        }
        // Separate pending flags prevent encoder frames from suppressing preview.
        val pending = AtomicBoolean(false)
        stream.setOnFrameAvailableListener({ s ->
            if (pending.compareAndSet(false, true)) GlCore.handler.post {
                pending.set(false)
                try { draw(s) }
                catch (t: Throwable) {
                    Journal.e(TAG, "camera frame failed", t)
                    onFailure?.invoke(t.message ?: "取景画面画不出来")
                }
            }
        }, main)
        return stream
    }

    private fun draw(stream: SurfaceTexture) {
        val texture = inputs[stream] ?: return
        if (released || !GlCore.makeCurrent(null)) return
        stream.updateTexImage()
        if (!active) return
        stream.getTransformMatrix(cameraMatrix)
        val wanted = look
        val began = System.nanoTime()
        if (stream === directStream) drawPreview(texture, wanted, previewCallbacks[stream])
        else if (stream !in previewStreams && videoEnabled && wanted == videoLook && videoGpu.ready) {
            val p = videoProgram ?: Program(ShaderText.CAMERA_VERTEX, ShaderText.videoFragment(app)).also { videoProgram = it }
            for ((output, egl) in outputs) {
                if (!GlCore.makeCurrent(egl)) continue
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, output.size.width, output.size.height)
                output.updateTransformMatrix(outputMatrix, cameraMatrix)
                bindCamera(p, texture)
                videoGpu.bind(p); finish.bind(p, wanted); Quad.draw()
                GlCore.presentationTime(egl, stream.timestamp); GlCore.swap(egl)
            }
        }
        GlCore.makeCurrent(null)
        if (stream === directStream) pace(began)
    }

    private fun drawPreview(texture: Int, wanted: Look, callback: ((Int, Int, Boolean, Look) -> Unit)?) {
        val target = window ?: return
        if (windowW <= 0 || windowH <= 0) return
        val sideways = directRotation % 180 != 0
        val uprightW = if (sideways) directSize.height else directSize.width
        val uprightH = if (sideways) directSize.width else directSize.height
        val fit = min(windowW / uprightW.toFloat(), windowH / uprightH.toFloat())
        val w = max(1, (uprightW * fit).roundToInt())
        val h = max(1, (uprightH * fit).roundToInt())
        Matrix.multiplyMM(outputMatrix, 0, cameraMatrix, 0, previewTransform, 0)
        val useVideo = recording && videoEnabled && videoGpu.ready && videoLook == wanted
        val shown = previewLook ?: wanted
        if (gradeLook != shown) { gradeLook = shown; previewGrade = shown.photoGrade() }
        if (!useVideo && shown.model.autoExposure) meter(texture, shown, w, h)
        else { meterEv = 0f; targetEv = 0f; meterReady = false }
        if (!GlCore.makeCurrent(target)) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, windowW, windowH)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glViewport((windowW - w) / 2, (windowH - h) / 2, w, h)
        val ready: Boolean
        if (useVideo) {
            val p = videoProgram ?: Program(ShaderText.CAMERA_VERTEX, ShaderText.videoFragment(app)).also { videoProgram = it }
            bindCamera(p, texture); videoGpu.bind(p); finish.bind(p, wanted); Quad.draw()
            ready = true
        } else if (previewGpu.ready) {
            val p = previewProgram ?: Program(ShaderText.CAMERA_VERTEX, ShaderText.previewCameraFragment(app)).also { previewProgram = it }
            bindCamera(p, texture); previewGpu.bind(p)
            p.setStruct("u_grade", ShaderText.GRADE, previewGrade)
            p.set("u_preview_ev", if (shown.model.autoExposure) meterEv else 0f)
            finish.bind(p, shown); Quad.draw()
            ready = previewLook == wanted
        } else {
            bindCamera(raw(), texture); Quad.draw()
            ready = false
        }
        GlCore.swap(target)
        callback?.invoke(w, h, ready, wanted)
    }

    private fun raw(): Program = rawProgram ?: Program(ShaderText.CAMERA_VERTEX, ShaderText.CAMERA_PASSTHROUGH).also { rawProgram = it }
    private fun bindCamera(p: Program, texture: Int) {
        p.use()
        GLES30.glUniformMatrix4fv(p.loc("u_tex"), 1, false, outputMatrix, 0)
        bindUnit(Tex.SOURCE, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        p.setInt("u_camera", Tex.SOURCE)
    }

    /** Only a small raw frame is read back four times a second. */
    private fun meter(texture: Int, wanted: Look, w: Int, h: Int) {
        val now = System.nanoTime()
        if (meterLook != wanted) { meterLook = wanted; meterTime = 0L; meterReady = false }
        if (now - meterTime >= 250_000_000L) {
            meterTime = now
            val scale = min(1f, 256f / max(w, h))
            val mw = max(1, (w * scale).roundToInt())
            val mh = max(1, (h * scale).roundToInt())
            if (meterTexture == 0) {
                meterTexture = newTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
                val fbo = IntArray(1); GLES30.glGenFramebuffers(1, fbo, 0); meterFbo = fbo[0]
            }
            if (meterWidth != mw || meterHeight != mh) {
                meterWidth = mw; meterHeight = mh
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, meterTexture)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, mw, mh, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                meterBuffer = ByteBuffer.allocateDirect(mw * mh * 4).order(ByteOrder.nativeOrder())
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, meterFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, meterTexture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
            GLES30.glViewport(0, 0, mw, mh)
            bindCamera(raw(), texture); Quad.draw()
            val pixels = checkNotNull(meterBuffer)
            pixels.rewind()
            GLES30.glReadPixels(0, 0, mw, mh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            // glReadPixels is bottom-up; reference metering uses top-down rows.
            val row = ByteArray(mw * 4)
            val other = ByteArray(mw * 4)
            for (y in 0 until mh / 2) {
                pixels.position(y * row.size); pixels.get(row)
                pixels.position((mh - 1 - y) * row.size); pixels.get(other)
                pixels.position(y * row.size); pixels.put(other)
                pixels.position((mh - 1 - y) * row.size); pixels.put(row)
            }
            pixels.rewind()
            targetEv = PreviewLut.meter(pixels, mw, mh, previewGrade, wanted.model).coerceIn(-12f, 12f)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (!meterReady) { meterEv = targetEv; meterReady = true }
        }
        val delta = targetEv - meterEv
        if (abs(delta) > .001f) meterEv += delta * .12f
    }

    private var paceSince = 0L
    private var paceFrames = 0
    private var paceNanos = 0L
    private fun pace(began: Long) {
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        if (paceSince == 0L) paceSince = began
        paceFrames++; paceNanos += now - began
        if (now - paceSince >= 2_000_000_000L) {
            Journal.i(TAG, String.format(java.util.Locale.US, "GPU preview %.1f fps, %.1f ms/frame",
                paceFrames * 1e9 / (now - paceSince), paceNanos / 1e6 / paceFrames))
            paceSince = now; paceFrames = 0; paceNanos = 0L
        }
    }
    companion object { private const val TAG = "latent-camera" }
}

class FilmEffect(targets: Int, film: CameraFilm) :
    CameraEffect(targets, GlCore.executor, film, { Journal.e("latent-camera", "the film effect failed", it) }) {
    init { require(targets == CameraEffect.VIDEO_CAPTURE) { "A film LUT must never target IMAGE_CAPTURE" } }
}
