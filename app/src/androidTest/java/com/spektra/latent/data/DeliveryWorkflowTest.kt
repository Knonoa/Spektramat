package com.spektra.latent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeliveryWorkflowTest {
    private val app get() = ApplicationProvider.getApplicationContext<LatentApp>()
    private suspend fun startup() = withTimeout(120_000) { app.startup.first { it is Startup.Ready } as Startup.Ready }

    @Test fun highlightInspectionReadsFloatChannelsBeforeEncoding() = runBlocking {
        startup()
        val w = 40; val h = 30
        val data = ByteBuffer.allocateDirect(w * h * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val look = Look()
        val original = FilmEngine.restoreProxy(w, h, data, false, 0)
        val base = look.photoOrder(1L)
        val order = base.copy(model = base.model.copy(outputCctfEncoding = false))
        val proof = original.use { FilmEngine.proofFloat(it, look.photoGrade(), order, look.finish(), look.finishTable()) }
        proof.use { photo ->
            val values = photo.pixels().asFloatBuffer()
            val colors = arrayOf(floatArrayOf(-.2f, .3f, .4f), floatArrayOf(.97f, .4f, .4f),
                floatArrayOf(.5f, 1.4f, .2f), floatArrayOf(5f, 5f, 5f))
            for (y in 0 until h) for (x in 0 until w) values.put(colors[x / 10])
            val gate = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            val stamp = floatArrayOf(.75f, 0f, 1f, 1f)
            val jpeg = HighlightCheck.inspect(photo, PrintFormat.JPEG, gate, stamp, true)
            val dng = HighlightCheck.inspect(photo, PrintFormat.DNG, gate, stamp, true)
            try {
                assertEquals(HighlightReport(900, 300, 300), jpeg.report)
                assertEquals(jpeg.report, dng.report)
                assertNotEquals(jpeg.overlay!!.getPixel(20, 0), dng.overlay!!.getPixel(20, 0))
                assertEquals(0, jpeg.overlay!!.getPixel(30, 0))
                assertEquals(1.4f, photo.pixels().asFloatBuffer()[20 * 3 + 1], 0f)
                assertTrue(dng.report.label(PrintFormat.DNG).contains("浮点保留"))
            } finally { jpeg.overlay?.recycle(); dng.overlay?.recycle() }
        }
    }

    @Test fun tetherCaptureSelectionAndDeliveryUseTheArchivedOriginal() = runBlocking {
        val ready = startup()
        val project = ready.library.createProject("delivery-instrumentation-${UUID.randomUUID()}")
        val source = File(app.cacheDir, "${UUID.randomUUID()}.jpg")
        val gallery = ArrayList<Uri>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var desk: PrintDesk
        var order: PrintJob? = null
        val prefs = app.getSharedPreferences("print-queue", Context.MODE_PRIVATE)
        val oldQueue = prefs.getString("orders", null)
        val monitor = ready.monitor
        val previousProject = withContext(Dispatchers.Main) { monitor.projectId }
        val previousLook = withContext(Dispatchers.Main) { monitor.look }
        try {
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xffdbaf60.toInt())
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            val bytes = source.readBytes()
            assertNotNull(ready.library.addCapture(source, "tether-source.jpg", Look(), 0f, project))
            val look = Look(exposure = .5f)
            withContext(Dispatchers.Main) {
                monitor.selectProject(project)
                monitor.change(look)
                monitor.connect(monitor.devices.first { it.usb == null })
            }
            withTimeout(25_000) { while (!withContext(Dispatchers.Main) { monitor.connected }) delay(50) }
            withContext(Dispatchers.Main) { monitor.release() }
            val shot = withTimeout(30_000) {
                ready.library.rolls.first { rolls -> rolls.any { it.id == project && it.frames.any { f -> f.tethered } } }
                    .first { it.id == project }.frames.first { it.tethered }
            }
            withContext(Dispatchers.Main) { monitor.disconnect() }
            assertArrayEquals(bytes, shot.file.readBytes())
            assertEquals(look.exposure, shot.look.exposure, 0f)
            monitor.pick(shot.id, true)
            val picked = ready.library.frame(shot.id)!!
            assertTrue(picked.picked)
            assertEquals(shot.revision, picked.revision)
            assertTrue(Library(app).let { it.load(); it.frame(picked.id)?.picked == true })
            val contact = ContactSheet.export(app, "Delivery Test", listOf(picked)) {}
            assertNull(contact.error)
            val page = contact.pages.single()
            if (page.inGallery) gallery += page.uri
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            app.contentResolver.openInputStream(page.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            assertEquals("image/jpeg", bounds.outMimeType)
            assertTrue(bounds.outWidth > 2000)

            // Isolate synthetic queue writes from any existing developer queue history.
            prefs.edit().remove("orders").commit()
            val print = withContext(Dispatchers.Main) {
                desk = PrintDesk(app, scope, ready.library)
                desk.order(picked, picked.look, 80, PrintFormat.PNG, checkHighlights = true, batch = "delivery-test").also { order = it }
            }
            withTimeout(50_000) { while (withContext(Dispatchers.Main) { print.active }) delay(50) }
            val done = withContext(Dispatchers.Main) { print.state as PrintJob.State.Done }
            if (done.printed.inGallery) gallery += done.printed.uri
            assertNotNull(done.printed.highlightReport)
            val restored = withContext(Dispatchers.Main) { PrintDesk(app, scope, ready.library).jobs.single() }
            val reopened = restored.state as PrintJob.State.Done
            assertEquals(done.printed.uri, reopened.printed.uri)
            assertEquals(done.printed.highlightReport, reopened.printed.highlightReport)
            assertEquals("delivery-test", restored.batch)

            // A process exit while a second order was outstanding never restarts it automatically.
            val persisted = org.json.JSONArray(prefs.getString("orders", "[]"))
            persisted.put(org.json.JSONObject(persisted.getJSONObject(0).toString())) // duplicate history cannot duplicate UI keys
            persisted.put(org.json.JSONObject(persisted.getJSONObject(0).toString()).put("id", restored.id + 1).put("state", "unfinished"))
            prefs.edit().putString("orders", persisted.toString()).commit()
            val retry = withContext(Dispatchers.Main) {
                desk = PrintDesk(app, scope, ready.library)
                assertEquals(2, desk.jobs.size)
                val interrupted = desk.jobs.last()
                assertTrue(interrupted.state is PrintJob.State.Failed)
                assertTrue(desk.canRetry(interrupted))
                desk.retry(interrupted)!!.also { order = it }
            }
            withTimeout(50_000) { while (withContext(Dispatchers.Main) { retry.active }) delay(50) }
            val retried = withContext(Dispatchers.Main) { retry.state as PrintJob.State.Done }
            if (retried.printed.inGallery) gallery += retried.printed.uri
            assertNotEquals(done.printed.uri, retried.printed.uri)
            assertEquals(done.printed.highlightReport, retried.printed.highlightReport)
            assertEquals(print.look, retry.look)
        } finally {
            withContext(Dispatchers.Main) {
                monitor.disconnect()
                monitor.change(previousLook)
                if (previousProject != null) monitor.selectProject(previousProject)
                order?.let { if (it.active) desk.stop(it) }
            }
            scope.cancel()
            prefs.edit().putString("orders", oldQueue).commit()
            gallery.forEach { app.contentResolver.delete(it, null, null) }
            ready.library.removeProject(project, deleteFiles = true)
            source.delete()
        }
    }
}
