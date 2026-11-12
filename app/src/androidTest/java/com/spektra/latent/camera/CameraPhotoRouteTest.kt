package com.spektra.latent.camera

import android.Manifest
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.engine.FilmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.io.File

@RunWith(AndroidJUnit4::class)
class CameraPhotoRouteTest {
    @Test fun gpuViewfinderAndFiledOriginalReachTheirSeparateRoutes() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as LatentApp
        instrument.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        assertTrue(FilmEngine.start(app) == null)
        val library = runBlocking {
            withTimeout(30_000) { app.startup.filterIsInstance<Startup.Ready>().first().library }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val rigRef = AtomicReference<CameraRig>()
        val windowRef = AtomicReference<TextureView>()
        val scenario = ActivityScenario.launch(CameraHarnessActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val finder = PreviewView(activity).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                val rig = CameraRig(activity, library, scope)
                rigRef.set(rig)
                val window = TextureView(activity).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            rig.window(surface, width, height, 0, display)
                        }
                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
                            rig.windowResized(width, height)
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            rig.windowGone()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                }
                windowRef.set(window)
                activity.setContentView(window)
                scope.launch { rig.start(activity, finder) }
            }
            waitFor(60_000) { rigRef.get()?.previewLutReady == true && rigRef.get().previewFrames > 2 }
            val rig = rigRef.get()
            assertLiveImage(instrument, windowRef.get())
            assertTrue(rig.direct)
            assertTrue("Photo preview must exclude display letterboxing",
                kotlin.math.abs(rig.previewWidth.toFloat() / rig.previewHeight - .75f) < .02f)
            val before = rig.previewFrames
            instrument.runOnMainSync { rig.change(rig.look.copy(filterY = rig.look.filterY + 5f)) }
            waitFor(60_000) { rig.previewLutReady && rig.previewFrames > before + 3 }
            // File the same untouched JPEG that a platform/system camera hands
            // back. Hardware ImageCapture itself is validated on a real phone:
            // this AVD's EmulatedFakeCam crashes inside its JPEG encoder.
            val capture = File(app.cacheDir, "camera-route-${System.nanoTime()}.jpg")
            val pixels = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff806040.toInt()) }
            try { capture.outputStream().use { assertTrue(pixels.compress(Bitmap.CompressFormat.JPEG, 97, it)) } }
            finally { pixels.recycle() }
            val originalBytes = capture.readBytes()
            instrument.runOnMainSync { rig.file(capture, "CAMERA_ROUTE.jpg") }
            waitFor(30_000) { rig.lastShot != null }
            val frame = checkNotNull(rig.lastShot)
            assertTrue(frame.file.length() > 0)
            org.junit.Assert.assertArrayEquals(originalBytes, frame.file.readBytes())
            assertTrue(frame.thumb.exists())
            assertTrue(!frame.raw)
            instrument.runOnMainSync { rig.switchMode(Mode.Video) }
            waitFor(60_000) { rig.mode == Mode.Video && rig.previewLutReady && rig.previewFrames > 2 }
            assertTrue(rig.status == RigStatus.Running)
            assertTrue("Video preview must exclude display letterboxing",
                kotlin.math.abs(rig.previewWidth.toFloat() / rig.previewHeight - 9f / 16f) < .02f)
            instrument.runOnMainSync { rig.switchMode(Mode.Photo) }
            waitFor(60_000) { rig.mode == Mode.Photo && rig.previewLutReady &&
                kotlin.math.abs(rig.previewWidth.toFloat() / rig.previewHeight - .75f) < .02f }
            if (rig.canFlip) {
                instrument.runOnMainSync { rig.flip() }
                waitFor(60_000) { rig.front && rig.previewLutReady && rig.previewFrames > 2 }
                assertLiveImage(instrument, windowRef.get())
                instrument.runOnMainSync { rig.flip() }
                waitFor(60_000) { !rig.front && rig.previewLutReady && rig.previewFrames > 2 }
            }
            if (rig.facts?.raw == true) {
                instrument.runOnMainSync { rig.toggleRaw() }
                waitFor(60_000) { rig.raw && rig.previewLutReady && rig.previewFrames > 2 }
                assertTrue(rig.status == RigStatus.Running)
                instrument.runOnMainSync { rig.toggleRaw() }
                waitFor(60_000) { !rig.raw && rig.previewLutReady && rig.previewFrames > 2 }
            }
            scenario.onActivity { activity ->
                rig.stop()
                scope.launch { rig.start(activity, PreviewView(activity)) }
            }
            waitFor(60_000) { rig.previewLutReady && rig.previewFrames > 2 }
            assertLiveImage(instrument, windowRef.get())
            android.util.Log.i("preview-route-test", "GPU preview survives look, mode, lens and lifecycle changes; raw=${rig.facts?.raw}")
            runBlocking { library.delete(listOf(frame.id)) }
        } finally {
            instrument.runOnMainSync {
                rigRef.get()?.let { it.stop(); it.release() }
                scope.cancel()
            }
            scenario.close()
        }
    }

    private fun assertLiveImage(instrument: android.app.Instrumentation, window: TextureView) {
        instrument.runOnMainSync {
            val bitmap = checkNotNull(window.bitmap) { "GPU viewfinder did not publish a display buffer" }
            try {
                var light = 0
                val cx = bitmap.width / 2
                val cy = bitmap.height / 2
                for (y in cy - 8 until cy + 8) for (x in cx - 8 until cx + 8) {
                    val pixel = bitmap.getPixel(x, y)
                    light += android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
                }
                assertTrue("GPU viewfinder must display camera pixels", light > 256 * 3)
                val context = instrument.targetContext
                File(context.getExternalFilesDir(null), "gpu-preview-${System.nanoTime()}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Camera route did not become ready within ${timeoutMs}ms", condition())
    }
}
