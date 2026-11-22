package com.spektra.latent

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.PrintJob
import com.spektra.latent.data.Printer
import com.spektra.latent.engine.FilmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Main-thread bridge between the shutter, the queue and the foreground service. */
internal class PrintBackground(private val app: LatentApp) {
    val captures = MutableStateFlow(0)
    private val activityManager by lazy { app.getSystemService(ActivityManager::class.java) }
    private val power by lazy { app.getSystemService(PowerManager::class.java) }
    private val hardwareWorkers = Runtime.getRuntime().availableProcessors()
    private val conservativeBackground = (Build.MANUFACTURER + " " + Build.BRAND).let { vendor ->
        listOf("oneplus", "oppo", "realme").any { vendor.contains(it, ignoreCase = true) }
    }
    var activityVisible = false
        private set
    private var workers = 0
    var running = false
        set(value) { field = value; refreshResources() }

    fun setActivityVisible(visible: Boolean) { activityVisible = visible; refreshResources() }

    fun refreshResources() {
        val budget = if (running) {
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
            ExportResources.workers(hardwareWorkers, memory.totalMem shr 20, activityManager.isLowRamDevice,
                memory.lowMemory, activityVisible, power.isPowerSaveMode, thermal, conservativeBackground)
        } else 0
        if (budget == workers) return
        FilmEngine.limitExportWorkers(budget)
        workers = budget
        Journal.i("latent-print", "export resource budget: workers=$budget visible=$activityVisible cpus=$hardwareWorkers")
    }

    fun request() {
        if (running) return
        try {
            app.startForegroundService(Intent(app, PrintService::class.java))
        } catch (e: IllegalStateException) {
            // A late request from the background can be denied. The queue resumes on launch.
            Journal.w("latent-print", "print service start deferred", e)
        }
    }

    /** CameraX delivers its file later. Start while the shutter UI is visible. */
    fun holdForCapture(): () -> Unit {
        captures.value++
        request()
        var released = false
        val release = {
            if (!released) { released = true; captures.value-- }
        }
        val expiry = app.scope.launch { delay(90_000); release() }
        return { release(); expiry.cancel() }
    }
}

class PrintService : Service() {
    private val app get() = application as LatentApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var desk: PrintDesk? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStart = 0
    private var promoted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        if (promoted) return START_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "照片导出", NotificationManager.IMPORTANCE_LOW))
        try {
            val starting = notification("正在准备照片…")
            when {
                Build.VERSION.SDK_INT >= 35 -> startForeground(ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= 29 -> startForeground(ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(ID, starting)
            }
        } catch (e: IllegalStateException) {
            Journal.w(TAG, "print service promotion deferred", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        promoted = true
        app.printBackground.running = true
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:print").apply {
            setReferenceCounted(false)
            acquire()
        }
        Journal.i(TAG, "foreground export service started; restarted=${intent == null}")
        val resources = scope.launch {
            while (true) { delay(5_000); app.printBackground.refreshResources() }
        }
        runner = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val ready = app.startup.first { it !is Startup.Loading } as? Startup.Ready ?: return@launch
                desk = ready.prints
                drain(ready.prints, manager)
            } finally { resources.cancel(); finish() }
        }.also { it.start() }
        return START_STICKY
    }

    private suspend fun drain(prints: PrintDesk, manager: NotificationManager) {
        val captures = app.printBackground.captures
        while (true) {
            val job = prints.nextWaiting()
            if (job != null) {
                manager.notify(ID, notification("正在冲印，队列还有 ${prints.jobs.count { it.active }} 项"))
                prints.run(job)
            } else {
                if (captures.value == 0) return
                manager.notify(ID, notification("正在保存原片…"))
                captures.combine(snapshotFlow { prints.nextWaiting() != null }) { count, queued -> count == 0 || queued }
                    .first { it }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        interrupt()
        finish()
    }

    override fun onDestroy() {
        interrupt()
        scope.cancel()
        finish()
        super.onDestroy()
    }

    private fun interrupt() {
        if (runner?.isActive != true) return
        runner?.cancel()
        if (desk?.jobs?.any { it.state == PrintJob.State.Working || it.state == PrintJob.State.Stopping } == true) Printer.cancel()
    }

    private fun finish() {
        if (!promoted) return
        promoted = false
        app.printBackground.running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStart)
        Journal.i(TAG, "foreground export service stopped")
    }

    private fun notification(body: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_print_notification)
            .setContentTitle("Spektramat 正在后台冲印")
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val TAG = "latent-print-service"
        private const val CHANNEL = "photo_exports"
        private const val ID = 8031
    }
}
