package com.spektra.latent.data

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektra.latent.LatentApp
import com.spektra.latent.MainActivity
import com.spektra.latent.Startup
import com.spektra.latent.model.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundPrintTest {
    @Test fun aShutterLeaseAcceptsAnOrderAfterHomeAndTheServiceFinishesIt() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as LatentApp
        val ready = withTimeout(30_000) { app.startup.filterIsInstance<Startup.Ready>().first() }
        val file = File.createTempFile("background-print-", ".jpg", app.cacheDir)
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff604174.toInt()) }
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) } }
        finally { bitmap.recycle() }
        val frame = checkNotNull(ready.library.addCapture(file, "BACKGROUND_PRINT_TEST.jpg", Look(), 0f, generateThumbnail = false))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var release: () -> Unit
                scenario.onActivity { release = ready.prints.holdForCapture() }
                try {
                    withTimeout(15_000) { while (!withContext(Dispatchers.Main) { app.printBackground.running }) delay(25) }
                    instrument.uiAutomation.executeShellCommand("input keyevent ${KeyEvent.KEYCODE_HOME}").close()
                    withTimeout(15_000) { while (withContext(Dispatchers.Main) { app.printBackground.activityVisible }) delay(25) }
                    val job = withContext(Dispatchers.Main) { ready.prints.orderCapture(frame).also { release() } }
                    withTimeout(60_000) { while (withContext(Dispatchers.Main) { job.active }) delay(25) }
                    val printed = (job.state as? PrintJob.State.Done)?.printed
                    assertTrue("Expected a published image: ${job.state}", printed?.inGallery == true)
                    printed?.let { app.contentResolver.delete(it.uri, null, null) }
                    assertTrue("Export must finish without bringing the activity back", !app.printBackground.activityVisible)
                    withTimeout(15_000) { while (withContext(Dispatchers.Main) { app.printBackground.running }) delay(25) }
                } finally { withContext(Dispatchers.Main) { release() } }
            }
        } finally { ready.library.delete(listOf(frame.id)) }
    }
}
