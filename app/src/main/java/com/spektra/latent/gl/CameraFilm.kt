package com.spektra.latent.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.spektra.latent.Journal
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.spektra.latent.BuildConfig
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.FilmTables
import com.spektra.latent.model.Look
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The film between the camera and everything that shows or records its
 * picture. Each camera frame is drawn through the same tables and the same
 * shader arithmetic as the darkroom, so what is seen is what the film gives.
 *
 * There are two ways in, because CameraX has two:
 *
 *  - as a CameraX effect ([FilmEffect]): CameraX hands over the camera's
 *    frames and the places they are wanted -- the viewfinder, and the video
 *    encoder while recording -- already turned and cropped for each. This is
 *    the normal way, for JPEG photographs and for video.
 *  - as the viewfinder itself ([direct], [attachWindow]): CameraX refuses an
 *    effect while RAW is being captured, so there the camera's frames are
 *    taken as a plain preview and drawn here to a window of the app's own.
 *
 * All of it runs on the GL thread; only [look] may be set from anywhere.
 */
class CameraFilm(context: Context) : SurfaceProcessor {
    private val app = context.applicationContext
    private val tables = FilmTables()
    private val gpu = FilmGpu()
    private val finish = FinishGpu()
    private var program: Program? = null
    private val inputs = HashMap<SurfaceTexture, Int>()               // each camera stream and its texture
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val cameraMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val turn = FloatArray(16)
    private var baked: Look? = null
    private var frames = 0L
    private var released = false
    private val pending = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    // the direct viewfinder
    private var window: EGLSurface? = null
    private var windowW = 0
    private var windowH = 0
    private var directStream: SurfaceTexture? = null
    private var directSize = Size(4, 3)
    private var directRotation = 0
    private var directMirror = false

    /** The film in the camera. */
    @Volatile
    var look: Look = Look()

    /** How far the screen is turned from the device's natural position, in degrees (0 on a phone held upright). */
    @Volatile
    var displayDegrees = 0

    /** Called on the GL thread when the film could not be drawn; the text is for the person. */
    @Volatile
    var onFailure: ((String) -> Unit)? = null

    // ---- as a CameraX effect

    override fun onInputSurface(request: SurfaceRequest) {
        open(request)
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released || GlCore.failure != null) {
            output.close()
            return
        }
        val surface = output.getSurface(GlCore.executor) {
            outputs.remove(output)?.let { GlCore.release(it) }
            output.close()
        }
        val egl = GlCore.windowSurface(surface)
        if (egl == null) {
            Journal.e(TAG, "no EGL surface for output ${output.size} targets ${output.targets}")
            output.close()
            return
        }
        outputs[output] = egl
    }

    // ---- as the viewfinder itself

    /** Give this to a Preview (with [GlCore.executor]) that has no effect on it. */
    val direct = Preview.SurfaceProvider { request ->
        val stream = open(request) ?: return@SurfaceProvider
        directStream = stream
        directSize = request.resolution
        request.setTransformationInfoListener(GlCore.executor) { info ->
            directRotation = info.rotationDegrees
            directMirror = info.isMirroring
        }
    }

    /** The window the direct viewfinder is drawn to: a TextureView's surface. */
    fun attachWindow(target: SurfaceTexture, w: Int, h: Int) = GlCore.post {
        window?.let { GlCore.release(it) }
        window = GlCore.windowSurface(target)
        windowW = w
        windowH = h
    }

    fun resizeWindow(w: Int, h: Int) = GlCore.post {
        windowW = w
        windowH = h
    }

    /** Blocks until the window surface is gone, so the caller may then free the window. */
    fun detachWindow() {
        GlCore.await {
            window?.let { GlCore.release(it) }
            window = null
        }
    }

    /** Frees the tables and the program. The camera must have been unbound first. */
    fun release() = GlCore.post {
        released = true
        window?.let { GlCore.release(it) }
        window = null
        GlCore.makeCurrent(null)
        gpu.release()
        finish.release()
        tables.close()
    }

    // ---- frames

    /** Makes the texture a camera stream writes to, and answers [request] with it. Null when it was declined. */
    private fun open(request: SurfaceRequest): SurfaceTexture? {
        if (released || GlCore.failure != null || !GlCore.makeCurrent(null)) {
            request.willNotProvideSurface()
            return null
        }
        val texture = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)
        val stream = SurfaceTexture(texture)
        stream.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(stream)
        inputs[stream] = texture
        request.provideSurface(surface, GlCore.executor) {
            // the camera has stopped writing to it
            stream.setOnFrameAvailableListener(null)
            inputs.remove(stream)
            if (directStream === stream) directStream = null
            stream.release()
            surface.release()
            if (GlCore.makeCurrent(null)) deleteTexture(texture)
        }
        // A frame is only announced here; it is drawn on the GL thread, and frames that arrive
        // while one is still being drawn are skipped rather than queued behind it.
        stream.setOnFrameAvailableListener({ s ->
            if (pending.compareAndSet(false, true)) {
                GlCore.handler.post {
                    pending.set(false)
                    try {
                        draw(s)
                    } catch (t: Throwable) {
                        Journal.e(TAG, "camera frame failed", t)
                        onFailure?.invoke(t.message ?: "取景画面画不出来")
                    }
                }
            }
        }, main)
        return stream
    }

    private fun draw(stream: SurfaceTexture) {
        val texture = inputs[stream] ?: return          // released while this frame waited
        if (released || !GlCore.makeCurrent(null)) return
        stream.updateTexImage()
        stream.getTransformMatrix(cameraMatrix)
        val toWindow = stream === directStream
        if (if (toWindow) window == null else outputs.isEmpty()) return

        val look = this.look
        if (look != baked) {
            tables.bake(
                look.film, look.paper, look.scanFilm,
                interLayer = look.interLayer > 0f, interLayerAmount = look.interLayer / 100f,
                yShift = look.filterY, mShift = look.filterM,
            )
            gpu.sync(tables)
            baked = look
        }
        if (!gpu.ready) return
        val p = program ?: Program(ShaderText.CAMERA_VERTEX, ShaderText.cameraFragment(app)).also { program = it }
        frames++
        val began = System.nanoTime()

        if (toWindow) {
            val target = window ?: return
            if (!GlCore.makeCurrent(target) || windowW <= 0 || windowH <= 0) return
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, windowW, windowH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            // the picture upright, fitted into the window and centred
            val sideways = directRotation % 180 != 0
            val uprightW = if (sideways) directSize.height else directSize.width
            val uprightH = if (sideways) directSize.width else directSize.height
            val fit = min(windowW / uprightW.toFloat(), windowH / uprightH.toFloat())
            val w = max(1, (uprightW * fit).roundToInt())
            val h = max(1, (uprightH * fit).roundToInt())
            GLES30.glViewport((windowW - w) / 2, (windowH - h) / 2, w, h)
            // The stream's own transform stands the picture upright for the device's natural position.
            // What is left: a screen turned away from that position, and the mirror of a front camera.
            Matrix.setIdentityM(turn, 0)
            val degrees = displayDegrees
            if (degrees != 0) {
                Matrix.translateM(turn, 0, 0.5f, 0.5f, 0f)
                Matrix.rotateM(turn, 0, degrees.toFloat(), 0f, 0f, 1f)
                Matrix.translateM(turn, 0, -0.5f, -0.5f, 0f)
            }
            if (directMirror) {
                Matrix.translateM(turn, 0, 1f, 0f, 0f)
                Matrix.scaleM(turn, 0, -1f, 1f, 1f)
            }
            Matrix.multiplyMM(outputMatrix, 0, cameraMatrix, 0, turn, 0)
            paint(p, texture, look, w, h, PHOTO_LONG_EDGE)
            GlCore.swap(target)
        } else {
            // grain as fine as it will be in what is kept: the video frame while one is wanted, else a photograph
            val kept = outputs.keys.filter { it.targets and CameraEffect.VIDEO_CAPTURE != 0 }
                .maxOfOrNull { max(it.size.width, it.size.height) } ?: PHOTO_LONG_EDGE
            for ((output, egl) in outputs) {
                if (!GlCore.makeCurrent(egl)) continue
                val w = output.size.width
                val h = output.size.height
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, w, h)
                output.updateTransformMatrix(outputMatrix, cameraMatrix)
                paint(p, texture, look, w, h, kept)
                GlCore.presentationTime(egl, stream.timestamp)
                GlCore.swap(egl)
            }
        }
        GlCore.makeCurrent(null)
        pace(began, if (toWindow) 1 else outputs.size)
    }

    // how fast the film is being drawn, into the log every couple of seconds (development builds only)
    private var paceSince = 0L
    private var paceFrames = 0
    private var paceNanos = 0L

    private fun pace(began: Long, places: Int) {
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        if (paceSince == 0L) paceSince = began
        paceFrames++
        paceNanos += now - began
        val span = now - paceSince
        if (span >= 2_000_000_000L) {
            Journal.i(
                TAG,
                String.format(
                    java.util.Locale.US, "film drawn %.1f times a second, %.1f ms each, to %d place(s)",
                    paceFrames * 1e9 / span, paceNanos / 1e6 / paceFrames, places,
                ),
            )
            paceSince = now
            paceFrames = 0
            paceNanos = 0L
        }
    }

    /** Draws the camera's texture through the film over the whole viewport, [w] x [h], with [outputMatrix]. */
    private fun paint(p: Program, texture: Int, look: Look, w: Int, h: Int, keptLongEdge: Int) {
        p.use()
        GLES30.glUniformMatrix4fv(p.loc("u_tex"), 1, false, outputMatrix, 0)
        bindUnit(Tex.SOURCE, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        p.setInt("u_camera", Tex.SOURCE)
        gpu.bind(p)
        // the camera has already chosen the exposure: its picture is taken as it is, like a frame it shot
        p.setStruct("u_grade", ShaderText.GRADE, look.grade(0f))
        finish.bind(p, look)
        val amount = look.grain / 100f
        val shownUm = look.formatUm / max(w, h)
        val keptUm = look.formatUm / keptLongEdge
        // the grain moves from frame to frame, as it does on film running through a gate
        val film = tables.filmBlock(1f, look.printGain, amount, FilmEngine.grainScale(amount, shownUm, keptUm), 1f + frames % 997)
        p.setStruct("u_film", ShaderText.FILM, film)
        p.set("u_cells", w.toFloat(), h.toFloat())
        Quad.draw()
    }

    companion object {
        private const val TAG = "latent-camera"
        /** Long edge a photograph from a phone is taken to have when its grain is shown in the viewfinder. */
        private const val PHOTO_LONG_EDGE = 4000
    }
}

/** [film] as a CameraX effect on the streams named by [targets] (PREVIEW, or PREVIEW | VIDEO_CAPTURE). */
class FilmEffect(targets: Int, film: CameraFilm) :
    CameraEffect(targets, GlCore.executor, film, { Journal.e("latent-camera", "the film effect failed", it) })
