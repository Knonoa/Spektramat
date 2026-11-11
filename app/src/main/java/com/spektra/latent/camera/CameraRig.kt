package com.spektra.latent.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraState
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.gl.CameraFilm
import com.spektra.latent.gl.FilmEffect
import com.spektra.latent.gl.GlCore
import com.spektra.latent.model.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class Mode { Photo, Video }

sealed interface RigStatus {
    data object Starting : RigStatus
    data object Running : RigStatus
    data class Failed(val reason: String) : RigStatus
}

/**
 * The camera as the app uses it: which lens, which settings, what it is
 * doing. The screen reads the state and calls the verbs; nothing here draws.
 *
 * CameraX opens the device and carries the per-phone workarounds. Settings a
 * phone does not report (manual exposure, focus by hand, RAW, a second lens)
 * stay null or empty in [facts] / [stops], and the screen shows no control for
 * them. State is read and written on the main thread.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraRig(context: Context, private val library: Library, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("camera", Context.MODE_PRIVATE)
    private val main = ContextCompat.getMainExecutor(app)
    private val io = Executors.newSingleThreadExecutor()
    private val film = CameraFilm(app)
    private val photoEffect = FilmEffect(CameraEffect.PREVIEW, film)
    private val videoEffect = FilmEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, film)

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var view: PreviewView? = null
    private var survey: Survey? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var tape: Recording? = null
    private var rotation = Surface.ROTATION_0
    private var lastResult = 0L
    private var restarted = false
    private var watched: LiveData<CameraState>? = null

    var status by mutableStateOf<RigStatus>(RigStatus.Starting); private set
    var mode by mutableStateOf(Mode.Photo); private set
    var front by mutableStateOf(false); private set
    var canFlip by mutableStateOf(false); private set

    /** The lenses of the side in use, widest first, and the one the picture is coming through. */
    var stops by mutableStateOf<List<LensStop>>(emptyList()); private set
    var stop by mutableStateOf<LensStop?>(null); private set
    /** Magnification as shown, relative to the main camera. */
    var zoom by mutableFloatStateOf(1f); private set
    var facts by mutableStateOf<CameraFacts?>(null); private set

    /** Exposure compensation as the camera counts it; EV = index * facts.evStep. */
    var ev by mutableIntStateOf(0); private set
    /** Null: the camera decides. */
    var iso by mutableStateOf<Int?>(null); private set
    var shutter by mutableStateOf<Long?>(null); private set
    var focus by mutableStateOf<Float?>(null); private set
    var whiteBalance by mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO); private set
    /** What the camera is actually using right now, a few times a second. Zero until known. */
    var liveIso by mutableIntStateOf(0); private set
    var liveShutter by mutableLongStateOf(0L); private set
    var liveFocus by mutableFloatStateOf(0f); private set

    var raw by mutableStateOf(false); private set
    /** True while the viewfinder is the window given with [window] and not the CameraX preview view. */
    var direct by mutableStateOf(false); private set
    /** The film in the camera: every frame shot is filed with this look. */
    var look by mutableStateOf(Look.fromJson(prefs.getString("look", null))); private set

    var qualities by mutableStateOf<List<Quality>>(emptyList()); private set
    var quality by mutableStateOf(Quality.FHD); private set
    var frameRate by mutableIntStateOf(30); private set
    var recording by mutableStateOf(false); private set
    var recordedMs by mutableLongStateOf(0L); private set
    var silent by mutableStateOf(false); private set
    /** Between the stop and the file being complete: the encoder is finishing. */
    var saving by mutableStateOf(false); private set

    var shooting by mutableStateOf(false); private set
    /** Counts exposures: the viewfinder blinks when it changes. */
    var exposures by mutableIntStateOf(0); private set
    var lastShot by mutableStateOf<Frame?>(null); private set
    var notice by mutableStateOf<String?>(null)

    val manualExposure: Boolean get() = iso != null || shutter != null

    init {
        film.look = look
        film.onFailure = { reason -> main.execute { notice = reason } }
    }

    // ---- starting and stopping

    suspend fun start(owner: LifecycleOwner, view: PreviewView) {
        this.owner = owner
        this.view = view
        try {
            val p = provider ?: ProcessCameraProvider.awaitInstance(app).also { provider = it }
            val s = survey ?: Lenses.survey(p.availableCameraInfos).also { survey = it }
            if (s.back.isEmpty() && s.front.isEmpty()) {
                status = RigStatus.Failed("这台设备没有可以使用的摄像头。")
                return
            }
            canFlip = s.back.isNotEmpty() && s.front.isNotEmpty()
            if (s.back.isEmpty()) front = true
            bind()
        } catch (e: Exception) {
            Log.e(TAG, "camera did not start", e)
            status = RigStatus.Failed(e.message ?: "相机没有打开。")
        }
    }

    fun stop() {
        watched?.removeObserver(watch)
        watched = null
        tape?.stop()
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        camera = null
        imageCapture = null
        videoCapture = null
    }

    /** After [stop], when the screen is gone for good. */
    fun release() {
        film.release()
        io.shutdown()
    }

    private fun bind(retry: Boolean = true, last: Boolean = true) {
        val p = provider ?: return
        val owner = owner ?: return
        val view = view ?: return
        val s = survey ?: return
        val list = if (front) s.front else s.back
        if (list.isEmpty()) {
            status = RigStatus.Failed("这一面没有可以使用的摄像头。")
            return
        }
        val target = stop?.takeIf { it in list } ?: list.firstOrNull { abs(it.ratio - 1f) < 0.01f } ?: list.first()
        val info = s.infos.getValue(target.cameraId)
        val f = s.facts.getValue(target.cameraId)
        // what this camera cannot take goes back to automatic
        if (f.isoRange == null || f.shutterRange == null) { iso = null; shutter = null }
        iso = iso?.coerceIn(f.isoRange ?: 0..0)
        shutter = shutter?.coerceIn(f.shutterRange ?: 0L..0L)
        if (f.minFocus <= 0f) focus = null
        focus = focus?.coerceIn(0f, f.minFocus)
        if (whiteBalance !in f.whiteBalances) whiteBalance = CameraMetadata.CONTROL_AWB_MODE_AUTO
        if (ev !in f.evRange) ev = 0
        if (!f.raw) raw = false

        try {
            p.unbindAll()
            camera = null
            val photo = mode == Mode.Photo
            // The viewfinder has the shape of what will be kept: 4:3 for a photograph, 16:9 for video.
            // It is drawn through the film every frame, so it is asked for at screen size, not sensor size.
            val previewBuilder = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(if (photo) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(if (photo) Size(1440, 1080) else Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(results)
            // CameraX will not combine RAW capture with an effect: there the film draws the viewfinder itself
            val own = photo && raw
            val preview = previewBuilder.build()
            if (own) preview.setSurfaceProvider(GlCore.executor, film.direct) else preview.surfaceProvider = view.surfaceProvider
            val group = UseCaseGroup.Builder().addUseCase(preview)
            if (photo) {
                val builder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetRotation(rotation)
                if (raw) {
                    builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                } else {
                    builder.setJpegQuality(97).setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build(),
                    )
                }
                // No thumbnail inside the file: the library draws its own, and a camera asked for none
                // has one thing fewer to get wrong (the emulator's camera crashes making one).
                Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.JPEG_THUMBNAIL_SIZE, Size(0, 0))
                val capture = builder.build()
                imageCapture = capture
                videoCapture = null
                group.addUseCase(capture)
                if (!own) group.addEffect(photoEffect)
            } else {
                // Film-look video is drawn frame by frame on the GPU; 1080p is where that is dependable
                // across phones, so 4K is not offered yet.
                val offered = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
                    .filter { it == Quality.FHD || it == Quality.HD || it == Quality.SD }
                qualities = offered
                if (quality !in offered) quality = offered.firstOrNull() ?: Quality.HD
                if (frameRate !in f.frameRates) frameRate = if (30 in f.frameRates) 30 else 0
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                    .build()
                val builder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
                if (frameRate > 0) builder.setTargetFrameRate(Range(frameRate, frameRate))
                val video = builder.build()
                videoCapture = video
                imageCapture = null
                group.addUseCase(video).addEffect(videoEffect)
            }
            val cam = p.bindToLifecycle(owner, info.cameraSelector, group.build())
            watched?.removeObserver(watch)
            watched = cam.cameraInfo.cameraState.also { it.observe(owner, watch) }
            camera = cam
            direct = own
            stops = list
            stop = target
            facts = f
            zoom = target.ratio
            cam.cameraControl.setZoomRatio(target.zoom.coerceIn(f.minZoom, f.maxZoom))
            if (ev != 0) cam.cameraControl.setExposureCompensationIndex(ev)
            applyManual()
            status = RigStatus.Running
            restarted = false
        } catch (e: Exception) {
            Log.e(TAG, "camera could not be set up", e)
            if (retry) {
                // The phone's camera service may have restarted since the cameras were looked over
                // (it does after a crash): what was learnt then is void. Look again, once.
                val fresh = try { Lenses.survey(p.availableCameraInfos) } catch (again: Exception) { null }
                if (fresh != null && (fresh.back.isNotEmpty() || fresh.front.isNotEmpty())) {
                    survey = fresh
                    stop = null
                    bind(retry = false)
                    return
                }
            }
            if (last) restart()
        }
    }

    /**
     * The last resort: CameraX itself is shut down and started again. After the
     * camera service has restarted, CameraX (1.5.3) can be left believing the
     * phone has no cameras at all; nothing short of this brings them back.
     * Done at most once between two successful starts.
     */
    private fun restart() {
        val p = provider
        if (restarted || p == null) {
            status = RigStatus.Failed("相机没有打开。退出相机再进来试一次；还不行的话，重新打开 App。")
            return
        }
        restarted = true
        status = RigStatus.Starting
        tape = null
        recording = false
        saving = false
        camera = null
        imageCapture = null
        videoCapture = null
        Log.w(TAG, "restarting CameraX")
        p.shutdownAsync().addListener({
            provider = null
            survey = null
            stop = null
            val o = owner
            val v = view
            if (o != null && v != null) scope.launch { start(o, v) }
        }, main)
    }

    /** A camera that fails while it is running (its service died, it was taken away) is started afresh. */
    private val watch = Observer<CameraState> { state ->
        val error = state.error ?: return@Observer
        if (error.type != CameraState.ErrorType.CRITICAL) return@Observer
        Log.w(TAG, "camera error ${error.code}", error.cause)
        when (error.code) {
            CameraState.ERROR_CAMERA_DISABLED -> status = RigStatus.Failed("这台设备的管理策略禁用了相机。")
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> status = RigStatus.Failed("勿扰模式下相机打不开，关掉勿扰模式再试。")
            else -> restart()
        }
    }

    // ---- the viewfinder window the film draws to while [direct]

    fun window(target: SurfaceTexture, w: Int, h: Int, displayDegrees: Int) {
        film.displayDegrees = displayDegrees
        film.attachWindow(target, w, h)
    }

    fun windowResized(w: Int, h: Int) = film.resizeWindow(w, h)

    /** Blocks until the film has let go of the window. */
    fun windowGone() = film.detachWindow()

    // ---- lens

    fun pick(target: LensStop) {
        val current = stop ?: return
        // a recording stays on its camera, and so does an exposure under way
        if ((recording || shooting) && target.cameraId != current.cameraId) return
        stop = target
        if (target.cameraId == current.cameraId) {
            val f = facts ?: return
            camera?.cameraControl?.setZoomRatio(target.zoom.coerceIn(f.minZoom, f.maxZoom))
            zoom = target.ratio
        } else {
            bind()
        }
    }

    /** Pinch: [factor] times the present magnification, within what the camera in use can do. */
    fun zoomBy(factor: Float) {
        val f = facts ?: return
        val current = stop ?: return
        val base = current.base
        val next = (zoom * factor).coerceIn(base * f.minZoom, base * f.maxZoom)
        if (next == zoom) return
        zoom = next
        camera?.cameraControl?.setZoomRatio(next / base)
        // the lit stop is the longest one not past the present magnification
        stops.filter { it.cameraId == current.cameraId && it.ratio <= next + 0.005f }.maxByOrNull { it.ratio }?.let { stop = it }
    }

    fun flip() {
        if (!canFlip || recording || shooting) return
        front = !front
        stop = null
        bind()
    }

    fun switchMode(next: Mode) {
        if (next == mode || recording || shooting) return
        mode = next
        bind()
    }

    // ---- exposure, focus, colour

    fun changeEv(index: Int) {
        val f = facts ?: return
        if (f.evRange.isEmpty()) return
        ev = index.coerceIn(f.evRange.first, f.evRange.last)
        camera?.cameraControl?.setExposureCompensationIndex(ev)
    }

    /** Setting either by hand fixes both: a camera's exposure is automatic or it is not. */
    fun changeIso(value: Int) {
        val f = facts ?: return
        val range = f.isoRange ?: return
        val time = f.shutterRange ?: return
        iso = value.coerceIn(range.first, range.last)
        if (shutter == null) shutter = (liveShutter.takeIf { it > 0 } ?: 16_666_666L).coerceIn(time.first, time.last)
        applyManual()
    }

    fun changeShutter(nanos: Long) {
        val f = facts ?: return
        val range = f.isoRange ?: return
        val time = f.shutterRange ?: return
        shutter = nanos.coerceIn(time.first, time.last)
        if (iso == null) iso = (liveIso.takeIf { it > 0 } ?: 100).coerceIn(range.first, range.last)
        applyManual()
    }

    fun autoExposure() {
        iso = null
        shutter = null
        applyManual()
    }

    fun changeFocus(dioptres: Float) {
        val f = facts ?: return
        if (f.minFocus <= 0f) return
        focus = dioptres.coerceIn(0f, f.minFocus)
        applyManual()
    }

    fun autoFocus() {
        focus = null
        applyManual()
    }

    fun changeWhiteBalance(mode: Int) {
        whiteBalance = mode
        applyManual()
    }

    /** A tap in the viewfinder: focus and meter there. [x], [y] are in the pixels of the viewfinder, [w] x [h]. */
    fun focusAt(x: Float, y: Float, w: Float, h: Float) {
        val cam = camera ?: return
        val view = view ?: return
        if (focus != null) autoFocus()
        try {
            val point = if (direct) {
                // the viewfinder drawn by the film shows the upright 3:4 picture fitted and centred
                val fit = minOf(w / 3f, h / 4f)
                val pw = 3f * fit
                val ph = 4f * fit
                val px = x - (w - pw) / 2f
                val py = y - (h - ph) / 2f
                if (px < 0f || py < 0f || px > pw || py > ph) return
                DisplayOrientedMeteringPointFactory(view.display ?: return, cam.cameraInfo, pw, ph).createPoint(px, py)
            } else {
                view.meteringPointFactory.createPoint(x, y)
            }
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point).setAutoCancelDuration(4, TimeUnit.SECONDS).build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "focus at a point is not available", e)
        }
    }

    private fun applyManual() {
        val cam = camera ?: return
        val f = facts ?: return
        val options = CaptureRequestOptions.Builder()
        val i = iso
        val t = shutter
        if (i != null && t != null && f.isoRange != null && f.shutterRange != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, i)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
        }
        val d = focus
        if (d != null && f.minFocus > 0f) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
        }
        if (whiteBalance != CameraMetadata.CONTROL_AWB_MODE_AUTO && whiteBalance in f.whiteBalances) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, whiteBalance)
        }
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
        } catch (e: Exception) {
            Log.w(TAG, "manual settings were not taken", e)
        }
    }

    /** What the camera reports back with each frame; shown on the dials while they are automatic. */
    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastResult < 250) return
            lastResult = now
            val i = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val d = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            main.execute {
                liveIso = i
                liveShutter = t
                liveFocus = d
            }
        }
    }

    // ---- the film in the camera

    fun load(filmId: String) {
        val next = look.withFilm(filmId)
        look = next
        film.look = next
        prefs.edit().putString("look", next.toJson().toString()).apply()
    }

    // ---- photographs

    fun toggleRaw() {
        if (facts?.raw != true || mode != Mode.Photo || shooting) return
        raw = !raw
        bind(retry = false, last = false)
        if (camera == null && raw) {
            raw = false
            notice = "这台手机不能这样拍 RAW，已换回 JPEG。"
            bind()
        }
    }

    /** Which way up the phone is held (a Surface.ROTATION_ value): pictures and video are stored that way up. */
    fun hold(surfaceRotation: Int) {
        if (surfaceRotation == rotation) return
        rotation = surfaceRotation
        imageCapture?.targetRotation = surfaceRotation
        if (!recording) videoCapture?.targetRotation = surfaceRotation
    }

    fun shoot() {
        val capture = imageCapture ?: return
        if (shooting) return
        shooting = true
        exposures++
        val exposure = exposures
        scope.launch {
            delay(20_000)
            if (shooting && exposures == exposure) {
                shooting = false
                notice = "相机没有交出这张照片。"
            }
        }
        val asRaw = raw
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "IMG_$stamp" + if (asRaw) ".dng" else ".jpg"
        val file = File(app.cacheDir, "shot-${System.nanoTime()}" + if (asRaw) ".dng" else ".jpg")
        // A JPEG already holds the exposure the camera and the person chose: it is filed as it is.
        // A RAW is metered like any other RAW, and the compensation that was dialled in goes with it as the look's exposure.
        val compensation = ev * (facts?.evStep ?: 0f)
        val shotLook = if (asRaw) look.copy(exposure = compensation) else look
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(), io,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(saved: ImageCapture.OutputFileResults) {
                    scope.launch {
                        val frame = library.addCapture(file, name, shotLook, metered = if (asRaw) null else 0f)
                        shooting = false
                        if (frame != null) lastShot = frame else notice = "这一张没有存下来。"
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "capture failed", e)
                    file.delete()
                    main.execute {
                        shooting = false
                        notice = if (asRaw) "这台手机的 RAW 没有写成文件。关掉 RAW 可以照常拍。" else "这一张没有拍下来，再按一次。"
                    }
                }
            },
        )
    }

    // ---- video

    fun pickQuality(next: Quality) {
        if (recording || next == quality || next !in qualities) return
        quality = next
        bind()
    }

    fun pickFrameRate(next: Int) {
        if (recording || next == frameRate || facts?.frameRates?.contains(next) != true) return
        frameRate = next
        bind()
    }

    @SuppressLint("MissingPermission")   // the microphone is used only when its permission is held
    fun toggleRecording() {
        tape?.let {
            if (!saving) {
                saving = true
                it.stop()
            }
            return
        }
        val video = videoCapture ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "Latent_$stamp.mp4"
        val pending = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Latent")
            }
            video.output.prepareRecording(
                app,
                MediaStoreOutputOptions.Builder(app.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build(),
            )
        } else {
            // before Android 10 the shared gallery needs a storage permission; the film stays in the app's own folder
            val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Latent").apply { mkdirs() }
            video.output.prepareRecording(app, FileOutputOptions.Builder(File(dir, name)).build())
        }
        val microphone = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        silent = !microphone
        recordedMs = 0L
        tape = (if (microphone) pending.withAudioEnabled() else pending).start(main) { event ->
            when (event) {
                is VideoRecordEvent.Start -> recording = true
                is VideoRecordEvent.Status -> recordedMs = event.recordingStats.recordedDurationNanos / 1_000_000
                is VideoRecordEvent.Finalize -> {
                    recording = false
                    saving = false
                    tape = null
                    videoCapture?.targetRotation = rotation
                    notice = if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                        Log.e(TAG, "recording ended with error ${event.error}", event.cause)
                        "这段视频没有保存下来。"
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        "视频已保存到相册的 Latent 相簿。"
                    } else {
                        "视频已保存在 App 的 Movies/Latent 文件夹。"
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "latent-camera"

        fun qualityName(q: Quality): String = when (q) {
            Quality.UHD -> "4K"
            Quality.FHD -> "1080P"
            Quality.HD -> "720P"
            Quality.SD -> "480P"
            else -> "视频"
        }
    }
}
