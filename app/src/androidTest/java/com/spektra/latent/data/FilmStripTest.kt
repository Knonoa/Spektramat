package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.model.FilmStrip
import com.spektra.latent.model.Look
import com.spektra.latent.model.StripFrame
import com.spektra.latent.model.StripLayout
import com.spektra.latent.model.StripMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FilmStripTest {
    @Test fun sleeveWrapsInOrderAndReservesTheEmptyPocketPositions() {
        val frames = (1L..5).map { StripFrame(it) }
        val strip = FilmStrip("wrap", frames, StripMode.Sleeve, perRow = 2)
        val layout = StripLayout.output(strip)
        assertEquals(3, layout.rows)
        assertEquals(2, layout.columns)
        assertEquals(layout.cells[0].x, layout.cells[4].x)
        assertEquals(layout.cells[1].y, layout.cells[0].y)
        assertTrue(layout.cells[2].y > layout.cells[0].bottom)
        assertTrue(layout.occupiedLength(2) < layout.rail(2).w)
        assertEquals(listOf(2, 2, 1), List(layout.rows, layout::occupiedIn))
        for (mode in StripMode.entries) {
            val biggest = StripLayout.output(FilmStrip("max", (1L..36).map { StripFrame(it) }, mode, perRow = 1))
            assertTrue(biggest.width <= StripLayout.MAX_EDGE && biggest.height <= StripLayout.MAX_EDGE)
            assertTrue(biggest.width * biggest.height <= StripLayout.MAX_PIXELS)
            assertTrue(biggest.cells.all { it.x >= 0 && it.right <= biggest.width && it.y >= 0 && it.bottom <= biggest.height })
        }
        val left = StripFrame(1, zoom = 2f, x = -1f, y = -1f).crop(80, 120, vertical = false)
        val right = StripFrame(1, zoom = 2f, x = 1f, y = 1f).crop(80, 120, vertical = false)
        assertEquals(0, left.x)
        assertEquals(0, left.y)
        assertEquals(80, right.right)
        assertEquals(120, right.bottom)
    }

    @Test fun cropResamplesInLinearLightAndKeepsSignedAndOverWhiteSamples() {
        val model = com.spektra.latent.engine.PhotoModel(outputCctfEncoding = false)
        val samples = floatArrayOf(.9f, .9f, .9f, -.4f, .3f, 2.1f, -.2f, .7f, 1.5f)
        com.spektra.latent.engine.FilmEngine.outputPixels(3, 1, samples, model).use { image ->
            image.cropScaled(1, 0, 2, 1, 1, 1).use { chosen ->
                val actual = FloatArray(3).also { chosen.pixels().asFloatBuffer().get(it) }
                assertArrayEquals(floatArrayOf(-.3f, .5f, 1.8f), actual, .00001f)
            }
        }
        val encoded = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
        com.spektra.latent.engine.FilmEngine.outputPixels(2, 1, encoded, model.copy(outputCctfEncoding = true)).use { image ->
            image.cropScaled(0, 0, 2, 1, 1, 1).use { chosen ->
                assertEquals(.735357f, chosen.pixels().asFloatBuffer().get(0), .00001f)
            }
        }
    }

    @Test fun queueComposesFiveOriginalsIntoOneSixteenBitSleeveAndOneJpegRoll() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val ready = withTimeout(25_000) { app.startup.first { it !is Startup.Loading } } as Startup.Ready
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prefs = app.getSharedPreferences("print-queue", Context.MODE_PRIVATE)
        val old = prefs.getString("orders", null)
        val appearance = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val priorCheck = appearance.getBoolean(HIGHLIGHT_PREF, false)
        val project = ready.library.createProject("Strip instrumentation")
        val created = mutableListOf<Frame>()
        val artifacts = mutableListOf<Uri>()
        try {
            val look = Look(grain = 0f, halation = 0f, glare = 0f)
            for (i in 0..4) {
                val file = File.createTempFile("strip-test", ".png", app.cacheDir)
                val original = Bitmap.createBitmap(if (i % 2 == 0) 120 else 80, if (i % 2 == 0) 80 else 120, Bitmap.Config.ARGB_8888)
                original.eraseColor(Color.rgb(35 + i * 47, 35 + i * 47, 35 + i * 47))
                try {
                    file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    created += checkNotNull(ready.library.addCapture(file, "strip-${i + 1}.png", look, 0f, project))
                } finally { file.delete(); original.recycle() }
            }
            val ranked = listOf(created[4], created[0], created[1], created[2], created[3])
            val strip = FilmStrip("Five original frames", ranked.map { StripFrame(it.id) }, StripMode.Sleeve, perRow = 2)
            prefs.edit().remove("orders").commit()
            appearance.edit().putBoolean(HIGHLIGHT_PREF, true).commit()
            val desk = withContext(Dispatchers.Main) { PrintDesk(app, scope, ready.library) }
            suspend fun order(work: FilmStrip, format: PrintFormat): Pair<PrintJob, Printed> {
                val job = withContext(Dispatchers.Main) { checkNotNull(desk.orderStrip(work, format)) }
                withTimeout(110_000) { while (withContext(Dispatchers.Main) { job.active }) delay(50) }
                val end = withContext(Dispatchers.Main) { job.state }
                assertTrue((end as? PrintJob.State.Failed)?.reason.orEmpty(), end is PrintJob.State.Done)
                val printed = (end as PrintJob.State.Done).printed
                if (printed.inGallery) artifacts += printed.uri
                return job to printed
            }
            val (job, printed) = order(strip, PrintFormat.PNG)
            assertTrue(printed.inGallery)
            assertEquals(5, job.stage)
            assertNotNull(printed.highlightReport)
            val layout = StripLayout.output(strip)
            assertEquals(layout.width, printed.width)
            assertEquals(layout.height, printed.height)
            val header = ByteArray(26)
            app.contentResolver.openInputStream(printed.uri)!!.use { java.io.DataInputStream(it).readFully(header) }
            assertEquals(16, header[24].toInt()) // IHDR bit depth
            val png = app.contentResolver.openInputStream(printed.uri)!!.use { BitmapFactory.decodeStream(it) }!!
            try {
                fun brightness(x: Int, y: Int): Int = png.getPixel(x, y).let { Color.red(it) + Color.green(it) + Color.blue(it) }
                val values = layout.cells.map { brightness(it.x + it.w / 2, it.y + it.h / 2) }
                assertEquals(values.sorted(), values.drop(1) + values.first())
                assertTrue(values.distinct().size == 5)
                assertTrue(brightness(layout.cells[1].x + layout.cells[1].w / 2, layout.cells[4].y + layout.cells[4].h / 2) < values[0])
            } finally { png.recycle() }
            val rawFile = File.createTempFile("strip-test-raw", ".dng", app.cacheDir)
            val rawFrame = try {
                InstrumentationRegistry.getInstrumentation().context.assets.open("export-fixtures/sensor14.dng").use { input ->
                    rawFile.outputStream().use { input.copyTo(it) }
                }
                checkNotNull(ready.library.addCapture(rawFile, "strip-test.dng", look, 0f, project))
            } finally { rawFile.delete() }
            assertTrue(rawFrame.raw)
            val continuous = strip.copy(mode = StripMode.Vertical,
                frames = listOf(StripFrame(rawFrame.id, zoom = 1.25f, x = -.5f)) + strip.frames.drop(1))
            val (_, jpeg) = order(continuous, PrintFormat.JPEG)
            assertTrue(jpeg.inGallery)
            assertTrue(jpeg.height > jpeg.width)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(jpeg.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            assertEquals("image/jpeg", bounds.outMimeType)
            assertEquals(jpeg.height, bounds.outHeight)

            val records = JSONArray(prefs.getString("orders", "[]"))
            assertEquals(2, records.length())
            records.getJSONObject(0).put("state", "unfinished")
            prefs.edit().putString("orders", records.toString()).commit()
            val restored = withContext(Dispatchers.Main) { PrintDesk(app, scope, ready.library) }
            val interrupted = restored.jobs.first()
            assertEquals(strip, interrupted.strip)
            assertEquals(job.stripLooks, interrupted.stripLooks)
            assertTrue(interrupted.state is PrintJob.State.Failed)
            assertTrue(restored.canRetry(interrupted))
            assertEquals(jpeg.uri, (restored.jobs.last().state as PrintJob.State.Done).printed.uri)
            assertNull(restored.workbenchJob(ranked.first().id))
        } finally {
            scope.cancel()
            for (uri in artifacts) app.contentResolver.delete(uri, null, null)
            ready.library.removeProject(project, deleteFiles = true)
            appearance.edit().putBoolean(HIGHLIGHT_PREF, priorCheck).commit()
            prefs.edit().apply { if (old == null) remove("orders") else putString("orders", old) }.commit()
        }
    }
}
