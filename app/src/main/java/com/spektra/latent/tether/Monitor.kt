package com.spektra.latent.tether

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import com.spektra.latent.APP_NAME
import com.spektra.latent.Journal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.spektra.latent.BuildConfig
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.VideoLut
import com.spektra.latent.model.Look
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A camera that can be connected to: one on the cable, or the stand-in of development builds. */
class TetherDevice(val name: String, val usb: UsbDevice?) {
    val id: String get() = usb?.let { "usb-${it.deviceId}" } ?: "demo"
}

/**
 * The monitor: a camera on a USB cable. It shows the camera's live view (with
 * the film, if wanted), says how the camera is set, records what it shows, and
 * receives each photograph the camera takes into today's tethered roll.
 *
 * It belongs to the app, not to a screen: once connected it keeps receiving
 * while the person is in the library or the darkroom. Its state is read by
 * Compose and changed on the main thread; the camera is talked to on one
 * background thread.
 */
class Monitor(context: Context, private val library: Library, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = app.packageName + ".USB_CAMERA"
    private val incoming = File(app.filesDir, "tether").apply { mkdirs() }

    var devices by mutableStateOf<List<TetherDevice>>(emptyList()); private set
    var status by mutableStateOf("没有连接相机"); private set
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var model by mutableStateOf(""); private set
    /** The picture on the monitor now; after the cable is pulled, the last one it showed. */
    var picture by mutableStateOf<ImageBitmap?>(null); private set
    var stale by mutableStateOf(false); private set
    var readings by mutableStateOf(Readings()); private set
    var fps by mutableFloatStateOf(0f); private set
    var received by mutableIntStateOf(0); private set
    var lastShot by mutableStateOf<Frame?>(null); private set
    /** 0..1 while a photograph is coming over the cable, else negative. */
    var transfer by mutableFloatStateOf(-1f); private set
    var canRelease by mutableStateOf(false); private set
    var recording by mutableStateOf(false); private set
    var recordedMs by mutableStateOf(0L); private set
    var notice by mutableStateOf<String?>(null)

    /** The long edge of the monitored picture is fitted into 480p or 720p. */
    var resolution by mutableIntStateOf(prefs.getInt("resolution", 720)); private set
    var filmOn by mutableStateOf(prefs.getBoolean("film", true)); private set
    /** The camera's settings burnt into the top of a recording. */
    var burnIn by mutableStateOf(prefs.getBoolean("burnIn", false)); private set
    /** A second copy of every received original in the phone's Download/Spektramat folder. */
    var publicCopy by mutableStateOf(prefs.getBoolean("publicCopy", false)); private set
    /** How the monitor develops what it shows, and how received photographs are filed. */
    var look by mutableStateOf(Look.fromJson(prefs.getString("look", null))); private set

    @Volatile private var stopping = false
    @Volatile private var wantRecording = false
    @Volatile private var wantRelease = false
    /** A screen is showing the monitor: without one (and without a recording) the live view is not fetched. */
    @Volatile var watched = false
    private var task: Job? = null
    private var asked: TetherDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                val device = asked ?: return
                asked = null
                val u = device.usb
                if (u != null && !stopping && usb.hasPermission(u)) run(device)
                else {
                    busy = false
                    status = "没有得到访问这台相机的许可，可以再选一次。"
                }
            } else {
                refresh()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, receiver,
            IntentFilter(permissionAction).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
        // photographs that had arrived whole when the app last stopped, but were not filed yet
        scope.launch { fileLeftovers() }
    }

    fun refresh() {
        val found = try {
            usb.deviceList.values.filter { it.vendorId == SonyData.VENDOR }.map {
                TetherDevice("Sony " + (SonyData.models[it.productId] ?: it.productName ?: "相机"), it)
            }
        } catch (e: Exception) {
            emptyList()
        }
        devices = if (BuildConfig.DEBUG) found + TetherDevice("演示相机（调试版）", null) else found
    }

    fun connect(device: TetherDevice) {
        if (task?.isActive == true || busy) return
        stopping = false
        busy = true
        val u = device.usb
        if (u == null || usb.hasPermission(u)) {
            run(device)
        } else {
            status = "请允许访问这台 USB 相机"
            asked = device
            usb.requestPermission(
                u,
                PendingIntent.getBroadcast(
                    app, u.deviceId, Intent(permissionAction).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    fun disconnect() {
        stopping = true
        asked = null
        wantRecording = false
        if (task?.isActive != true) {
            busy = false
            status = "已断开"
        }
    }

    fun pickResolution(value: Int) {
        if (recording || wantRecording) return
        resolution = value
        prefs.edit().putInt("resolution", value).apply()
    }

    fun film(on: Boolean) {
        if (recording || wantRecording) return
        filmOn = on
        prefs.edit().putBoolean("film", on).apply()
    }

    fun burnIn(on: Boolean) {
        burnIn = on
        prefs.edit().putBoolean("burnIn", on).apply()
    }

    fun publicCopy(on: Boolean) {
        publicCopy = on
        prefs.edit().putBoolean("publicCopy", on).apply()
    }

    fun change(next: Look) {
        if (recording || wantRecording) return
        if (next == look) return
        look = next
        prefs.edit().putString("look", next.toJson().toString()).apply()
    }

    fun toggleRecording() {
        if (task?.isActive != true) return
        if (!wantRecording && filmOn) {
            notice = "胶片录像关闭自动测光、高光增强、扩散与模糊、光晕、散射、DIR 空间扩散、颗粒、眩光、黑白校正和锐化；收到的照片使用完整算法。"
        }
        wantRecording = !wantRecording
    }

    /** Presses the camera's shutter button from here. */
    fun release() {
        if (connected && canRelease) wantRelease = true
    }

    private fun run(device: TetherDevice) {
        if (task?.isActive == true) return
        stopping = false
        busy = true
        stale = false
        task = scope.launch(Dispatchers.IO) {
            var camera: TetherCamera? = null
            var recorder: MonitorRecorder? = null
            var videoLut: VideoLut? = null
            var videoLook: Look? = null
            var video: File? = null
            var recordingSince = 0L
            var lastPoll = 0L
            var lastSecond = SystemClock.elapsedRealtime()
            var count = 0
            var missingSince = 0L

            fun endRecording() {
                val r = recorder
                recorder = null
                wantRecording = false
                recording = false
                videoLut?.close()
                videoLut = null
                videoLook = null
                val f = video
                video = null
                if (r == null) return
                try {
                    r.close()
                } catch (e: Exception) {
                    notice = "录制没有收好尾：${e.message}"
                    return
                }
                if (f != null && f.isFile) {
                    try {
                        saveVideo(f)
                        f.delete()
                        notice = "监看录像已存进相册的 ${APP_NAME} 相簿。"
                    } catch (e: Exception) {
                        notice = "录像还在 App 里，没能存进相册：${e.message}"
                    }
                }
            }

            try {
                status = "正在连接 ${device.name}"
                camera = open(device)
                camera.connect()
                model = camera.model
                canRelease = camera.canRelease
                status = "${camera.model}，已连接"
                if (camera.setupNote.isNotEmpty()) notice = camera.setupNote
                connected = true
                busy = false

                while (!stopping) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPoll >= 500) {
                        val r = try {
                            camera.poll()
                        } catch (e: PtpFailure) {
                            if (e.response == 0x2019) {   // busy: ask again in a moment
                                delay(100)
                                continue
                            }
                            throw e
                        }
                        lastPoll = now
                        readings = r
                        if (camera.hasPhoto()) receive(camera)
                    }
                    if (wantRelease) {
                        wantRelease = false
                        try {
                            camera.release()
                        } catch (e: PtpFailure) {
                            notice = "相机没有接受遥控快门（0x${e.response.toString(16)}）。在相机上按快门照常可以拍。"
                        }
                    }
                    if (!wantRecording) endRecording()
                    if (!watched && !wantRecording) {
                        // nobody is looking: only the photographs are waited for
                        delay(200)
                        continue
                    }
                    val raw = camera.liveJpeg()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (raw == null) {
                        if (missingSince == 0L) missingSince = now
                        if (now - missingSince > 5000) {
                            endRecording()
                            status = "${camera.model}，已连接，但没有取景画面。看看相机的 PC 遥控设置。"
                        }
                        delay(80)
                        continue
                    }
                    if (missingSince != 0L) {
                        missingSince = 0L
                        status = "${camera.model}，已连接"
                    }
                    val fitted = fit(raw, resolution)
                    if (fitted !== raw) raw.recycle()
                    val shown = if (filmOn && wantRecording) {
                        val current = look
                        if (videoLook != current) {
                            val next = VideoLut.bake(current.order(0L), current.grade(0f))
                            videoLut?.close(); videoLut = next; videoLook = current
                            Journal.i(TAG, next.metadata.toString())
                        }
                        try { checkNotNull(videoLut).render(fitted, current.finish(), current.finishTable()) }
                        finally { fitted.recycle() }
                    } else if (filmOn) developed(fitted) else fitted
                    if (wantRecording && recorder == null) {
                        try {
                            val f = File(incoming, "monitor-${System.currentTimeMillis()}.mp4")
                            recorder = MonitorRecorder(f, shown.width, shown.height)
                            video = f
                            recordingSince = now
                            recording = true
                        } catch (e: Exception) {
                            wantRecording = false
                            notice = "开始不了录制：${e.message ?: "这台手机的编码器不可用"}"
                        }
                    }
                    if (recorder != null && incoming.usableSpace < 120L * 1024 * 1024) {
                        endRecording()
                        notice = "手机快没有空间了，录制已经结束并保存。"
                    }
                    recorder?.let {
                        it.frame(shown, if (burnIn) readings.line else null)
                        recordedMs = now - recordingSince
                    }
                    picture = shown.asImageBitmap()
                    count++
                    if (now - lastSecond >= 1000) {
                        fps = count * 1000f / (now - lastSecond)
                        lastSecond = now
                        count = 0
                    }
                    delay(4)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Journal.w(TAG, "tether stopped", e)
                status = "连接断了：${e.message ?: "USB 出错"}"
            } finally {
                try {
                    endRecording()
                } catch (e: Exception) {
                    notice = "录像没有存好：${e.message}"
                }
                camera?.finish()
                connected = false
                busy = false
                recording = false
                wantRecording = false
                canRelease = false
                transfer = -1f
                fps = 0f
                stale = picture != null
                readings = Readings()
                if (stopping) status = "已断开"
            }
        }
    }

    private fun open(device: TetherDevice): TetherCamera {
        val u = device.usb
        if (u != null) return SonyCamera(PtpSession(UsbPtpLink(usb, u)), device.name)
        val any = library.rolls.value.firstNotNullOfOrNull { roll -> roll.frames.firstOrNull { !it.raw } }
            ?: throw IOException("演示相机要用片库里的一张照片当画面，先导入一张 JPEG。")
        return DemoCamera(any.file)
    }

    /** The non-recording monitor is a photo preview and uses the full engine. */
    private fun developed(frame: Bitmap): Bitmap {
        val l = look
        val source = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
        return try {
            FilmEngine.fromBitmap(source).use { linear ->
                FilmEngine.develop(linear, l.photoGrade(), l.order(seed = 1L), l.finish(), l.finishTable(), lane = FilmEngine.PROOF)
            }
        } finally { source.recycle() }
    }

    private suspend fun receive(camera: TetherCamera) {
        val info = camera.photoInfo()
        val wanted = info.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(90)
        val name = wanted.takeIf { n -> KEPT.any { n.endsWith(".$it", ignoreCase = true) } }
            ?: ("TETHER_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + if (info.format == 0xb101) ".ARW" else ".JPG")
        val partial = File(incoming, "${System.nanoTime()}.partial")
        try {
            if (incoming.usableSpace < info.bytes * 2 + 100L * 1024 * 1024) throw IOException("手机剩下的空间不够收这张照片")
            transfer = 0f
            camera.receive(partial, info) { n -> transfer = (n.toFloat() / info.bytes).coerceIn(0f, 1f) }
            // From here on this file may be the only copy there is (a camera set to "computer only" keeps none):
            // it is given its name first, and filed second. If the app stops in between it is filed at the next start.
            val whole = File(incoming, "${System.nanoTime()}_$name")
            if (!partial.renameTo(whole)) throw IOException("收到的照片没能改名保存")
            file(whole, name)
        } finally {
            transfer = -1f
            partial.delete()
        }
    }

    /** Files a received original on today's tethered roll with the monitor's look, and copies it out if asked. */
    private suspend fun file(whole: File, name: String) {
        val copy = if (publicCopy) File(incoming, "copy-${System.nanoTime()}").also { whole.copyTo(it, overwrite = true) } else null
        val raw = FilmEngine.isRaw(whole.path)
        val frame = library.addCapture(whole, name, look, metered = if (raw) null else 0f, roll = library.tetherRoll(), keepOnFailure = true)
        if (frame == null) {
            // the library cannot read it, but it is a photograph someone took: it is handed over, never dropped
            copy?.delete()
            notice = try {
                savePublic(whole, name)
                whole.delete()
                "收到了 $name。片库读不了这种文件，已把它存到 Download/${APP_NAME}。"
            } catch (e: Exception) {
                val kept = File(File(incoming, "kept").apply { mkdirs() }, name)
                whole.renameTo(kept)
                "收到了 $name，但片库读不了它。文件留在 App 里，没有丢。"
            }
            return
        }
        received++
        lastShot = frame
        if (copy != null) {
            try {
                savePublic(copy, name)
                notice = "已接收 $name，另存了一份到 Download/${APP_NAME}。"
            } catch (e: Exception) {
                notice = "已接收 $name。另存到手机存储没有成功：${e.message}"
            } finally {
                copy.delete()
            }
        } else {
            notice = "已接收 $name"
        }
    }

    private suspend fun fileLeftovers() = withContext(Dispatchers.IO) {
        val files = incoming.listFiles() ?: return@withContext
        for (f in files) {
            if (f.isDirectory) continue
            when {
                f.name.endsWith(".partial") || f.name.startsWith("copy-") -> f.delete()
                f.name.endsWith(".mp4") -> try {
                    saveVideo(f)
                    f.delete()
                } catch (e: Exception) {
                    Journal.w(TAG, "a recording is still waiting to be saved", e)
                }
                else -> file(f, f.name.substringAfter('_'))
            }
        }
    }

    private fun saveVideo(file: File) {
        if (Build.VERSION.SDK_INT < 29) {
            // Android 8 and 9: the gallery needs a storage permission this app does not ask for
            val dir = File(app.getExternalFilesDir(null), "monitor").apply { mkdirs() }
            file.copyTo(File(dir, file.name), overwrite = true)
            return
        }
        publish(file, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/${APP_NAME}", file.name, "video/mp4")
    }

    private fun savePublic(file: File, name: String) {
        if (Build.VERSION.SDK_INT < 29) throw IOException("这个 Android 版本要另外的存储权限")
        val mime = when (name.substringAfterLast('.').lowercase(Locale.US)) {
            "arw" -> "image/x-sony-arw"
            "dng" -> "image/x-adobe-dng"
            "hif", "heif", "heic" -> "image/heif"
            else -> "image/jpeg"
        }
        publish(file, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/${APP_NAME}", name, mime)
    }

    private fun publish(file: File, collection: Uri, folder: String, name: String, mime: String) {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("手机没有接受这个文件")
        try {
            val copied = resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("写不进去")
            if (copied != file.length()) throw IOException("没有写完整")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("手机没有确认保存")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    companion object {
        private const val TAG = "latent-tether"
        private val KEPT = listOf("arw", "jpg", "jpeg", "hif", "heif", "dng")

        /** Fits a frame into a 16:9 box [resolution] high, never enlarging it; even sizes, as video wants. */
        fun fit(source: Bitmap, resolution: Int): Bitmap {
            val k = minOf(1f, resolution * 16f / 9f / source.width, resolution.toFloat() / source.height)
            val w = ((source.width * k).roundToInt().coerceAtLeast(2)) / 2 * 2
            val h = ((source.height * k).roundToInt().coerceAtLeast(2)) / 2 * 2
            if (w == source.width && h == source.height) return source
            return Bitmap.createScaledBitmap(source, w, h, true)
        }
    }
}
