package com.spektra.latent.ui.darkroom

import android.os.SystemClock
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektra.latent.LatentApp
import com.spektra.latent.MainActivity
import com.spektra.latent.Startup
import com.spektra.latent.data.Frame
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.PrintJob
import com.spektra.latent.data.Printed
import com.spektra.latent.ui.PrintOverlay
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

/** Removes and recreates the real animation composition, as switching away from the bench does. */
@RunWith(AndroidJUnit4::class)
class PrintRunLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun frame() = Frame(-721003L, -1L, 1, File("unused-print-test.png"), "print test", false,
        120, 80, 0L, "", "", 0, 0.0, 0.0, 0.0, 0f, Look(), File("unused-thumb.png"), 1)

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail(message)
    }

    @Test fun returningToTheWorkbenchResumesTheSameTimeline() {
        val job = PrintJob(-721003L, frame(), Look(), 0)
        val visible = mutableStateOf(true)
        val pictureReady = mutableStateOf(true)
        var observed: PrintRunState? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                job.state = PrintJob.State.Working
                job.stage = 1
                activity.setContent {
                    if (visible.value) {
                        val run = rememberPrintRun(job, pictureReady.value)
                        SideEffect { observed = run }
                    }
                }
            }
            await("The print timeline did not start") { observed != null && job.animation.position > .15f }
            val retained = job.animation
            var position = 0f
            onMain { visible.value = false; position = retained.position }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            onMain {
                assertEquals("Leaving the screen must retain the last visible position", position, retained.position, .3f)
                job.stage = 4
                observed = null
                pictureReady.value = false
                visible.value = true
            }
            await("Returning to the bench lost its print animation") { observed != null }
            onMain {
                assertSame(retained, observed)
                assertTrue("Returning must not restart the animation from zero", retained.position >= position)
            }
            var waiting = 0f
            onMain { waiting = retained.position }
            SystemClock.sleep(150)
            onMain {
                assertEquals("The restored timeline waits until the proxy is visible", waiting, retained.position, 0f)
                pictureReady.value = true
            }
            await("The restored animation did not follow the background export") { retained.position > position + .3f }
        }
    }

    @Test fun aFailedBackgroundOrderIsStillReportedAfterReturning() {
        val job = PrintJob(-721004L, frame(), Look(), 0)
        val visible = mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { if (visible.value) rememberPrintRun(job, pictureReady = true) }
                job.state = PrintJob.State.Failed("test gallery failure")
                visible.value = true
            }
            await("Failure was lost when returning to the workbench") { job.animation.over }
            onMain { assertEquals("test gallery failure", job.animation.failed) }
        }
    }

    @Test fun workbenchReattachesOnlyToUnacknowledgedOrders() {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val startup = runBlocking { withTimeout(120_000) {
            app.startup.first { it is Startup.Ready } as Startup.Ready
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        try {
            onMain {
                val desk = PrintDesk(app, scope, startup.library)
                val job = PrintJob(-721005L, frame(), Look(), 0)
                desk.jobs += job
                assertSame(job, desk.workbenchJob(job.frame.id))
                job.animation.position = 3.25f
                assertSame(job, desk.workbenchJob(job.frame.id))
                assertEquals(3.25f, desk.workbenchJob(job.frame.id)!!.animation.position, 0f)
                job.animation.over = true
                assertSame("A completed run remains until its result is acknowledged", job,
                    desk.workbenchJob(job.frame.id))
                job.animation.acknowledged = true
                assertNull(desk.workbenchJob(job.frame.id))
            }
        } finally { scope.cancel() }
    }

    @Test fun completedOrderSurvivesOverlayTimeoutAwayThenFinishesAfterReturning() {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val startup = runBlocking { withTimeout(120_000) {
            app.startup.first { it is Startup.Ready } as Startup.Ready
        } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        val desk = PrintDesk(app, scope, startup.library)
        val job = PrintJob(-721006L, frame(), Look(), 0)
        val visible = mutableStateOf(false)
        val bitmap = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    job.animation.attached = true
                    job.animation.position = 3.4f
                    job.state = PrintJob.State.Done(Printed(Uri.parse("content://test/complete"),16,16,bitmap,true))
                    desk.jobs += job
                    activity.setContent {
                        PrintOverlay(desk, if (visible.value) job.frame.id else null)
                        if (visible.value) {
                            val run = rememberPrintRun(desk.workbenchJob(job.frame.id),true)
                            val completed = run?.over == true
                            SideEffect { if (completed) run?.acknowledged = true }
                        }
                    }
                }
                SystemClock.sleep(5_600)
                onMain {
                    assertTrue("A completed workbench order must survive the overlay timeout while away", job in desk.jobs)
                    assertEquals(3.4f,job.animation.position,0f)
                    visible.value = true
                }
                await("Returning must finish and acknowledge the saved animation") { job.animation.acknowledged }
                await("The finished tile can clear after the animation was acknowledged") { job !in desk.jobs }
            }
        } finally { scope.cancel(); bitmap.recycle() }
    }
}
