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
import com.spektra.latent.data.Decoder
import com.spektra.latent.data.Frame
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Controls
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
class DarkroomProofTest {
    @Test fun rapidExposureEditsPublishTheLastFullEngineProof() {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val startup = runBlocking { withTimeout(120_000) { app.startup.first { it is Startup.Ready } as Startup.Ready } }
        val file = File(app.cacheDir, "proof-requests-${System.nanoTime()}.png")
        val input = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val grey = 30 + x * 190 / (width - 1)
                setPixel(x, y, Color.rgb(grey, (grey * .8f).toInt(), 40 + y * 150 / (height - 1)))
            }
        }
        file.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        input.recycle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val look = Look(grain = 0f, glare = 0f, halation = 0f, interLayer = 0f)
        val frame = Frame(-912341L, -1L, 1, file, "proof test", false, 120, 80,
            0L, "", "", 0, 0.0, 0.0, 0.0, 0f, look, file, 1)
        lateinit var state: DarkroomState
        var stateCreated = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitForProof() {
            val deadline = SystemClock.uptimeMillis() + 90_000
            while (SystemClock.uptimeMillis() < deadline) {
                var done = false
                var failure: String? = null
                onMain { done = state.ready && !state.proofing; failure = state.failure }
                assertNull(failure)
                if (done) return
                SystemClock.sleep(25)
            }
            fail("The latest exposure request never published a proof")
        }
        try {
            onMain { state = DarkroomState(app, startup.library, frame, scope); stateCreated = true }
            waitForProof()
            val exposure = Controls.scene.first { it.id == "exposure" }
            for (ev in listOf(-2f, 2f, -.5f, 1f, -1f, 2.5f, .75f)) {
                onMain { state.change(exposure.set(state.look, ev)); state.commit() }
                SystemClock.sleep(3)
            }
            waitForProof()
            lateinit var finalLook: Look
            lateinit var actual: Bitmap
            onMain { finalLook = state.look; actual = state.proof!!.asAndroidBitmap() }
            assertEquals(.75f, finalLook.exposure, 0f)
            assertFalse(finalLook.model.printExposureCompensation)
            Decoder.decode(file, DarkroomState.PREVIEW_EDGE, look.lensShading, look.aspect).use { decoded ->
                decoded.scaled(DarkroomState.PROOF_EDGE).use { source ->
                    val expected = FilmEngine.develop(source, finalLook.photoGrade(),
                        finalLook.photoOrder(seed = frame.id * 7919L + 1L), finalLook.finish(), finalLook.finishTable(),
                        lane = FilmEngine.THUMB)
                    try {
                        val a = IntArray(actual.width * actual.height)
                        val b = IntArray(expected.width * expected.height)
                        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                        expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
                        assertArrayEquals("The workbench must display the final edit, not an earlier proof", b, a)
                    } finally { expected.recycle() }

                    // Switching tabs is itself a render request. Even a rapid
                    // round-trip must end at the adjusted source, without film.
                    onMain {
                        state.showAdjustmentsOnly(true)
                        state.showAdjustmentsOnly(false)
                        state.showAdjustmentsOnly(true)
                    }
                    waitForProof()
                    onMain { actual = state.proof!!.asAndroidBitmap() }
                    val adjusted = FilmEngine.adjustPhoto(source, finalLook.grade(0f), finalLook.finish(), finalLook.finishTable())
                    try {
                        val a = IntArray(actual.width * actual.height)
                        val b = IntArray(adjusted.width * adjusted.height)
                        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                        adjusted.getPixels(b, 0, adjusted.width, 0, 0, adjusted.width, adjusted.height)
                        assertArrayEquals("Adjustment tab must show the adjusted source", b, a)
                    } finally { adjusted.recycle() }

                    // Continue editing after entering the adjustment tab. The
                    // committed edit must request a new source-only proof.
                    onMain {
                        assertTrue(state.adjustmentsOnly)
                        state.change(exposure.set(state.look, -1.25f))
                        state.commit()
                    }
                    waitForProof()
                    onMain {
                        assertTrue(state.adjustmentsOnly)
                        finalLook = state.look
                        actual = state.proof!!.asAndroidBitmap()
                    }
                    assertEquals(-1.25f, finalLook.exposure, 0f)
                    val editedAdjustment = FilmEngine.adjustPhoto(source, finalLook.grade(0f), finalLook.finish(), finalLook.finishTable())
                    try {
                        assertEquals(editedAdjustment.width, actual.width)
                        assertEquals(editedAdjustment.height, actual.height)
                        val a = IntArray(actual.width * actual.height)
                        val b = IntArray(editedAdjustment.width * editedAdjustment.height)
                        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                        editedAdjustment.getPixels(b, 0, editedAdjustment.width, 0, 0, editedAdjustment.width, editedAdjustment.height)
                        assertArrayEquals("A committed edit in the adjustment tab must update the adjusted source", b, a)
                    } finally { editedAdjustment.recycle() }

                    // Returning to another tab must restore the complete film
                    // render using that same latest edit, not the old proof.
                    onMain { state.showAdjustmentsOnly(false) }
                    waitForProof()
                    onMain {
                        assertFalse(state.adjustmentsOnly)
                        assertEquals(finalLook, state.look)
                        actual = state.proof!!.asAndroidBitmap()
                    }
                    val restoredFilm = FilmEngine.develop(source, finalLook.photoGrade(),
                        finalLook.photoOrder(seed = frame.id * 7919L + 1L), finalLook.finish(), finalLook.finishTable(),
                        lane = FilmEngine.THUMB)
                    try {
                        assertEquals(restoredFilm.width, actual.width)
                        assertEquals(restoredFilm.height, actual.height)
                        val a = IntArray(actual.width * actual.height)
                        val b = IntArray(restoredFilm.width * restoredFilm.height)
                        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                        restoredFilm.getPixels(b, 0, restoredFilm.width, 0, 0, restoredFilm.width, restoredFilm.height)
                        assertArrayEquals("Leaving adjustments must restore the full film proof with the latest edit", b, a)
                    } finally { restoredFilm.recycle() }
                }
            }
        } finally {
            if (stateCreated) onMain { state.dispose() }
            scope.cancel()
            file.delete()
        }
    }
}
