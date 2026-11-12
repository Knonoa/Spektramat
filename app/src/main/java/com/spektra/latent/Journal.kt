package com.spektra.latent

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app has to say while it runs. It goes to the system log, and in
 * development builds also to a file of the app's own, `files/log/latent.log`
 * in its external folder: some phones drop an app's lines from the system log
 * (a vivo did, from one start of the app to the next), and a file can be
 * followed over adb whatever the phone does:
 *
 *     adb shell tail -F /sdcard/Android/data/com.spektra.latent.dev/files/log/latent.log
 *
 * A line is written the way logcat's `threadtime` writes it. The file also
 * takes what the system log would have had from elsewhere: an exception nobody
 * caught, and at each start how the runs before it ended.
 */
object Journal {
    /** Past this the file is put aside as `latent.1.log` at the next start. */
    private const val LIMIT = 2L * 1024 * 1024

    private val lock = Any()
    private var out: FileWriter? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write('I', tag, message, null)
    }

    fun w(tag: String, message: String, cause: Throwable? = null) {
        if (cause != null) Log.w(tag, message, cause) else Log.w(tag, message)
        write('W', tag, message, cause)
    }

    fun e(tag: String, message: String, cause: Throwable? = null) {
        if (cause != null) Log.e(tag, message, cause) else Log.e(tag, message)
        write('E', tag, message, cause)
    }

    /** Development builds call this once, before anything else can speak. */
    fun open(app: Application) {
        val dir = app.getExternalFilesDir("log") ?: return
        synchronized(lock) {
            try {
                val file = File(dir, "latent.log")
                if (file.length() > LIMIT) {
                    val old = File(dir, "latent.1.log")
                    old.delete()
                    file.renameTo(old)
                }
                out = FileWriter(file, true)
            } catch (e: Exception) {
                Log.w("latent", "no journal file", e)
                return
            }
        }
        i("latent", "started: ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write('E', "latent-crash", "nobody caught this, on thread ${thread.name}", error)
            before?.uncaughtException(thread, error)
        }
        earlierEnds(app, dir)
    }

    /** How the runs before this one ended, each told once: the system keeps that, whatever its log dropped. */
    private fun earlierEnds(app: Application, dir: File) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val mark = File(dir, "ends-told")
            val told = if (mark.exists()) mark.readText().trim().toLongOrNull() ?: 0L else 0L
            val manager = app.getSystemService(ActivityManager::class.java) ?: return
            val ends = manager.getHistoricalProcessExitReasons(null, 0, 8)
                .filter { it.timestamp > told }.sortedBy { it.timestamp }
            val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            for (end in ends) {
                val line = "an earlier run (process ${end.pid}) ended at ${clock.format(Date(end.timestamp))}: " +
                    reason(end.reason) + (end.description?.let { ", $it" } ?: "")
                when (end.reason) {
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> w("latent-exit", line)
                    else -> i("latent-exit", line)
                }
                // an ANR's trace is text; a native crash's is a tombstone in binary, left where it is
                if (end.reason == ApplicationExitInfo.REASON_ANR) {
                    end.traceInputStream?.bufferedReader()?.useLines { lines ->
                        lines.take(120).forEach { write('W', "latent-exit", it, null) }
                    }
                }
            }
            ends.lastOrNull()?.let { mark.writeText(it.timestamp.toString()) }
        } catch (e: Exception) {
            w("latent", "how the earlier runs ended could not be read", e)
        }
    }

    private fun reason(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "it exited by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed because the phone was short of memory"
        ApplicationExitInfo.REASON_CRASH -> "it crashed (an exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "it crashed in native code"
        ApplicationExitInfo.REASON_ANR -> "it stopped responding (ANR)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "it could not start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission was changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for using too much"
        // swiped away, force-stopped, or replaced by an install: the description says which
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed on request"
        ApplicationExitInfo.REASON_USER_STOPPED -> "the user stopped it"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depended on died"
        ApplicationExitInfo.REASON_FREEZER -> "killed while frozen in the background"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "the app was disabled or changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "replaced by a new install"
        else -> "the system ended it (reason $code)"
    }

    private fun write(level: Char, tag: String, message: String, cause: Throwable?) {
        if (out == null) return
        synchronized(lock) {
            val o = out ?: return
            try {
                val head = stamp.format(Date()) + " " + Process.myPid().toString().padStart(5) + " " +
                    Process.myTid().toString().padStart(5) + " " + level + " " + tag + ": "
                for (line in message.split('\n')) o.append(head).append(line).append('\n')
                if (cause != null) {
                    for (line in Log.getStackTraceString(cause).trimEnd().split('\n')) o.append(head).append(line).append('\n')
                }
                o.flush()
            } catch (e: Exception) {
                out = null
            }
        }
    }
}
