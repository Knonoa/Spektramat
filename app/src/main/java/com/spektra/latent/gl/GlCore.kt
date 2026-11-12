package com.spektra.latent.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import com.spektra.latent.Journal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * The app's one OpenGL ES 3 context and the one thread that may touch it.
 *
 * Every picture the app draws through the film -- the darkroom preview, the
 * viewfinder, the develop animation -- is drawn here, so their textures and
 * programs can be shared. Nothing in this class knows about film; it owns the
 * thread, the context and the window surfaces.
 */
object GlCore {
    private const val TAG = "latent-gl"
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    private val thread = HandlerThread("latent-gl").apply { start() }
    val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE

    /** Set once the context exists; null while it is fine, else why there is no GPU pipeline. */
    @Volatile
    var failure: String? = null
        private set

    /** Whether float textures can be rendered to (halation needs it). */
    var floatTargets = false
        private set

    /** Whether surfaces of this context can feed a video encoder. */
    var recordable = false
        private set

    /** Hands work to the GL thread; for libraries that want an [Executor]. */
    val executor = Executor { handler.post(it) }

    init {
        handler.post { create() }
    }

    fun isGlThread() = Thread.currentThread() === thread

    /** Runs [block] on the GL thread, with the context current. */
    fun post(block: () -> Unit) {
        handler.post {
            if (failure == null) {
                try {
                    block()
                } catch (t: Throwable) {
                    Journal.e(TAG, "GL task failed", t)
                }
            }
        }
    }

    /** Runs [block] on the GL thread and waits for it. Never call from the GL thread. */
    fun <T> await(block: () -> T): T? {
        check(!isGlThread())
        var result: T? = null
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (failure == null) result = block()
            } catch (t: Throwable) {
                Journal.e(TAG, "GL task failed", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return result
    }

    private fun create() {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL could not start" }
            // A video encoder only takes frames from a "recordable" configuration. Nearly every
            // device has one; where there is none the app still runs, without film-look video.
            fun choose(recordable: Boolean): EGLConfig? {
                val attributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    if (recordable) EGL_RECORDABLE_ANDROID else EGL14.EGL_NONE, if (recordable) 1 else EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                return if (EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
            }
            config = choose(recordable = true)?.also { recordable = true } ?: choose(recordable = false)
            check(config != null) { "this device offers no OpenGL ES 3 surface" }
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3 is not available" }
            offscreen = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, offscreen, offscreen, context)) { "the GL context could not be used" }
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            floatTargets = extensions.contains("GL_EXT_color_buffer_half_float") || extensions.contains("GL_EXT_color_buffer_float")
            Journal.i(TAG, "GL ready: ${GLES30.glGetString(GLES30.GL_RENDERER)}, ${GLES30.glGetString(GLES30.GL_VERSION)}, float targets $floatTargets, recordable $recordable")
        } catch (t: Throwable) {
            failure = t.message ?: "the GPU pipeline could not start"
            Journal.e(TAG, "GL unavailable", t)
        }
    }

    // ---- window surfaces (GL thread only)

    fun windowSurface(window: Any): EGLSurface? {
        val surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        return if (surface == null || surface == EGL14.EGL_NO_SURFACE) null else surface
    }

    fun makeCurrent(surface: EGLSurface?): Boolean {
        val s = surface ?: offscreen
        return EGL14.eglMakeCurrent(display, s, s, context)
    }

    fun swap(surface: EGLSurface) {
        EGL14.eglSwapBuffers(display, surface)
    }

    /** Stamps the frame about to be swapped: a video encoder orders and times frames by this. */
    fun presentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun release(surface: EGLSurface) {
        EGL14.eglMakeCurrent(display, offscreen, offscreen, context)
        EGL14.eglDestroySurface(display, surface)
    }
}
