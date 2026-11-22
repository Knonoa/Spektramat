package com.spektra.latent.ui.darkroom

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.data.CreativeSource
import com.spektra.latent.data.Decoder
import com.spektra.latent.data.Frame
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.CreativeLook
import com.spektra.latent.model.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CreativeProofTest {
    @Test fun creativeEditUndoAndAdjustmentTabUseTheCorrectSource() {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val startup = runBlocking { withTimeout(120_000) { app.startup.first { it is Startup.Ready } as Startup.Ready } }
        val file = File(app.cacheDir, "creative-proof-${System.nanoTime()}.png")
        val input = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width)
                setPixel(x, y, if (x in 45..70 && y in 25..55) Color.WHITE else Color.rgb(20 + x, 30 + y, 45))
        }
        file.outputStream().use { input.compress(Bitmap.CompressFormat.PNG, 100, it) }
        input.recycle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val look = Look(grain = 0f, halation = 0f, glare = 0f, interLayer = 0f)
        val frame = Frame(-991172L, -1L, 1, file, "creative proof", false, 120, 80,
            0L, "", "", 0, 0.0, 0.0, 0.0, 0f, look, file, 1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        lateinit var state: DarkroomState
        onMain { state = DarkroomState(app, startup.library, frame, scope) }
        fun awaitProof(exact: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 90_000
            while (SystemClock.uptimeMillis() < deadline) {
                var done = false
                onMain {
                    assertNull(state.failure)
                    done = state.ready && !state.proofing && (!exact || state.exact)
                }
                if (done) return
                SystemClock.sleep(25)
            }
            fail("Creative edit did not publish its latest proof")
        }
        fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        try {
            awaitProof(true)
            val edited = look.copy(creative = CreativeLook(softFocus = 65f, flare = 40f, glow = 70f))
            onMain { state.change(edited); state.commit() }
            awaitProof(true)
            lateinit var actual: IntArray
            onMain { actual = pixels(state.proof!!.asAndroidBitmap()) }
            runBlocking {
                Decoder.decode(file, 0).use { source ->
                    CreativeSource(app).apply(source, edited, 1280).use { creative ->
                        val expected = FilmEngine.develop(creative, edited.photoGrade(), edited.photoOrder(frame.id * 7919L + 1L),
                            edited.finish(), edited.finishTable(), lane = FilmEngine.THUMB)
                        try { assertArrayEquals("Exact proof must contain the same creative optics as export", pixels(expected), actual) }
                        finally { expected.recycle() }
                    }
                }
            }
            onMain { state.setHighlightWarning(com.spektra.latent.data.PrintFormat.JPEG) }
            val reportDeadline = SystemClock.uptimeMillis() + 45_000
            var reported = false
            while (!reported && SystemClock.uptimeMillis() < reportDeadline) {
                onMain { reported = (state.highlights?.total ?: 0) > 0 }
                if (!reported) SystemClock.sleep(25)
            }
            assertTrue("Enabling the warning must analyze a full float proof", reported)
            onMain {
                state.setHighlightWarning(null)
                assertNull(state.highlights)
                assertNull(state.highlightOverlay)
            }
            onMain { state.undo() }
            awaitProof(true)
            onMain { assertEquals(look, state.look); state.redo() }
            awaitProof(true)
            onMain { assertEquals(edited, state.look); state.showAdjustmentsOnly(true) }
            awaitProof(false)
            onMain { actual = pixels(state.proof!!.asAndroidBitmap()) }
            Decoder.decode(file, 0).use { source ->
                val expected = FilmEngine.adjustPhoto(source, edited.grade(0f), edited.finish(), edited.finishTable())
                try {
                    val reference = pixels(expected)
                    var maxError = 0
                    actual.indices.forEach { i ->
                        for (shift in listOf(0, 8, 16)) maxError = maxOf(maxError,
                            kotlin.math.abs((actual[i] shr shift and 255) - (reference[i] shr shift and 255)))
                    }
                    assertTrue("Adjustment tab must keep its original-source view: error=$maxError", maxError <= 2)
                } finally { expected.recycle() }
            }
        } finally {
            onMain { state.dispose() }
            scope.cancel()
            file.delete()
        }
    }
}
