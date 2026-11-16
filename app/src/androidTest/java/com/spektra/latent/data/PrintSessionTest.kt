package com.spektra.latent.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.net.Uri
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
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

/** Navigation reconnects to the background desk; this never orders a real export. */
@RunWith(AndroidJUnit4::class)
class PrintSessionTest {
    @Test fun returningToAFrameRestoresItsTimelineAndFadeUntilAcknowledged() = withDesk { desk, frame ->
        val job = PrintJob(101L, frame, frame.look, frame.longEdge)
        job.state = PrintJob.State.Working
        desk.jobs += job
        val leaving = desk.workbenchJob(frame.id)!!.animation
        leaving.position = 3.4f
        leaving.time = 9f
        leaving.fade = .6f

        // A recreated workbench only has the frame ID; its old local state is gone.
        val returned = desk.workbenchJob(frame.id)!!
        assertSame(job, returned)
        assertSame(leaving, returned.animation)
        assertEquals(3.4f, returned.animation.position, 0f)
        assertEquals(9f, returned.animation.time, 0f)
        assertEquals(.6f, returned.animation.fade, 0f)

        returned.animation.over = true
        assertSame("Completion must remain available for its final UI notification", job, desk.workbenchJob(frame.id))
        returned.animation.acknowledged = true
        assertNull("An already shown completion must not restart on the next visit", desk.workbenchJob(frame.id))
    }

    @Test fun otherFramesAndStoppedJobsDoNotAttachToTheWorkbench() = withDesk { desk, frame ->
        val first = PrintJob(102L, frame, frame.look, frame.longEdge)
        val nextFrame = frame.copy(id = frame.id - 1)
        val other = PrintJob(103L, nextFrame, frame.look, frame.longEdge)
        desk.jobs += first
        desk.jobs += other
        assertSame(first, desk.workbenchJob(frame.id))
        assertSame(other, desk.workbenchJob(nextFrame.id))
        first.stopped = true
        assertNull(desk.workbenchJob(frame.id))
        assertSame(other, desk.workbenchJob(nextFrame.id))
        desk.dismiss(other)
        assertNull(desk.workbenchJob(nextFrame.id))
    }

    @Test fun completedWorkbenchOrderStaysUntilItsAnimationIsAcknowledged() = withDesk { desk, frame ->
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val job = PrintJob(104L, frame, frame.look, 0)
            desk.jobs += job
            assertFalse("Unvisited batch results can be cleared normally", job.animation.awaitingWorkbench)
            job.animation.attached = true
            job.animation.position = 2.5f
            job.state = PrintJob.State.Done(Printed(Uri.parse("content://test/completed"), 1, 1, bitmap, true))
            assertTrue("Leaving the bench must protect the completed animation from tile auto-dismiss", job.animation.awaitingWorkbench)
            assertSame(job, desk.workbenchJob(frame.id))
            assertEquals(2.5f, desk.workbenchJob(frame.id)!!.animation.position, 0f)
            job.animation.acknowledged = true
            assertFalse(job.animation.awaitingWorkbench)
            assertNull(desk.workbenchJob(frame.id))
        } finally { bitmap.recycle() }
    }

    private fun withDesk(check: (PrintDesk, Frame) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val startup = runBlocking {
            withTimeout(120_000) { app.startup.first { it is Startup.Ready } as Startup.Ready }
        }
        // A cancelled scope prevents the synthetic jobs from starting background work.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        val file = File(app.cacheDir, "unused-print-session.png")
        val look = Look()
        val frame = Frame(-891231L, -1L, 1, file, "session test", false, 120, 80,
            0L, "", "", 0, 0.0, 0.0, 0.0, 0f, look, file, 1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(PrintDesk(app, scope, startup.library), frame)
        }
    }
}
