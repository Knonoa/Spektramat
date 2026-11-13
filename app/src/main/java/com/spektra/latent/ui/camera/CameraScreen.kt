package com.spektra.latent.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.LatentApp
import com.spektra.latent.camera.CameraFacts
import com.spektra.latent.camera.CameraRig
import com.spektra.latent.camera.Dials
import com.spektra.latent.camera.Lenses
import com.spektra.latent.camera.Mode
import com.spektra.latent.camera.RigStatus
import com.spektra.latent.data.Library
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.Recipes
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Aspects
import com.spektra.latent.model.Look
import com.spektra.latent.ui.BarSep
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnKind
import com.spektra.latent.ui.BtnSize
import com.spektra.latent.ui.Chip
import com.spektra.latent.ui.Chips
import com.spektra.latent.ui.Ctl
import com.spektra.latent.ui.CtlColumn
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.GAP
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.GlassPage
import com.spektra.latent.ui.GlassTone
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.Icon
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Lamp
import com.spektra.latent.ui.LampState
import com.spektra.latent.ui.LocalOverlays
import com.spektra.latent.ui.LocalShell
import com.spektra.latent.ui.Notice
import com.spektra.latent.ui.Opt
import com.spektra.latent.ui.Orb
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.Seg
import com.spektra.latent.ui.SegStyle
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.PageScheme
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.TopBar
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.activity
import com.spektra.latent.ui.ambientOf
import com.spektra.latent.ui.bottomInset
import com.spektra.latent.ui.darkroom.EdgePanel
import com.spektra.latent.ui.darkroom.FilmPanel
import com.spektra.latent.ui.darkroom.LookHost
import com.spektra.latent.ui.drawDroplet
import com.spektra.latent.ui.glass
import com.spektra.latent.ui.rememberDroplet
import com.spektra.latent.ui.shortName
import com.spektra.latent.ui.topInset
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The camera: the phone's lens seen through the film that is loaded.
 *
 * What is kept is the original -- a JPEG, or a RAW where the phone gives one
 * -- filed in the library with the loaded film as its look, so it can be
 * developed differently later. Video is the exception: the film is recorded
 * into the picture, because a video has no darkroom afterwards.
 *
 * Every control shown is one this phone reported: lenses, exposure
 * compensation, sensitivity, exposure time, focus, white balance, RAW.
 * The screen stays upright; pictures are stored the way the phone was held.
 */
@Composable
fun CameraScreen(library: Library, prints: PrintDesk, recipes: Recipes, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LatentApp
    val owner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val shell = LocalShell.current
    val overlays = LocalOverlays.current
    val rig = remember { CameraRig(context, library, app.scope) }
    val rolls by library.rolls.collectAsStateWithLifecycle()
    val lastShot = rig.lastShot?.let { shot -> rolls.firstNotNullOfOrNull { roll -> roll.frames.firstOrNull { it.id == shot.id } } }

    var allowed by remember { mutableStateOf(holds(context, Manifest.permission.CAMERA)) }
    var refused by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        refused = !it
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (!allowed) askCamera.launch(Manifest.permission.CAMERA) }

    val finder = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    LaunchedEffect(allowed) { if (allowed) rig.start(owner, finder) }

    DisposableEffect(Unit) {
        val activity = context.activity()
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // the screen does not turn, but what is shot is stored the way the phone is held
        val held = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                rig.hold(
                    when (degrees) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    },
                )
            }
        }
        held.enable()
        onDispose {
            held.disable()
            rig.stop()
            rig.release()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (before != null) activity.requestedOrientation = before
        }
    }

    var dial by remember { mutableStateOf<String?>(null) }
    var back by remember { mutableStateOf(false) }
    val saved by recipes.all.collectAsStateWithLifecycle()
    val facts = rig.facts
    val dials = facts?.let { dialsOf(it) }.orEmpty()

    // a print of every photograph as soon as it is filed, when the person has asked for that
    DisposableEffect(rig) {
        rig.onFiled = { frame ->
            if (rig.printOnRelease) prints.order(frame, frame.look, context.getSharedPreferences("appearance", 0).getInt("exportEdge", 0))
        }
        onDispose { rig.onFiled = null }
    }
    // the phone's own camera app, for lenses the phone keeps to itself: its picture comes back here whole
    var handover by remember { mutableStateOf<File?>(null) }
    val systemCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = handover
        handover = null
        if (file != null) {
            if (taken && file.length() > 0) rig.file(file, "SYS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg")
            else file.delete()
        }
    }
    fun openSystemCamera() {
        try {
            val dir = File(context.cacheDir, "handover").apply { mkdirs() }
            val file = File(dir, "system-${System.nanoTime()}.jpg")
            handover = file
            systemCamera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", file))
        } catch (e: Exception) {
            handover = null
            rig.notice = "这台手机没有可以打开的系统相机。"
        }
    }
    if (dial != null && dial !in dials) dial = null
    BackHandler(enabled = dial != null) { dial = null }
    Notice(rig.notice, { rig.notice = null })
    PageScheme(light = false)

    // the room around the viewfinder takes the colour of what the lens sees
    var window by remember { mutableStateOf<TextureView?>(null) }
    var ambient by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(window) {
        while (true) {
            delay(280)
            val view = window ?: continue
            if (!view.isAvailable || view.width <= 0 || view.height <= 0) continue
            val small = runCatching { view.getBitmap(36, 48) }.getOrNull() ?: continue
            ambient = ambientOf(small, 24)
        }
    }

    GlassPage(
        live = false,
        behind = {
            Box(Modifier.fillMaxSize().background(Color.Black))
            ambient?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .6f, filterQuality = FilterQuality.High) }
            // ---- the viewfinder: the sensor is 4:3 and the screen is upright, so it stands in the middle,
            // as wide as the screen, with the room's light above and below it
            // (beside the sidebar where a wide screen keeps it)
            if (allowed) BoxWithConstraints(Modifier.fillMaxSize().padding(start = shell.insetLeft), contentAlignment = Alignment.Center) {
                val across = if (maxWidth < maxHeight * .75f) maxWidth else maxHeight * .75f
                Box(Modifier.size(across, across / .75f)) {
                        if (rig.direct) {
                            // The GPU draws live camera pixels into this window.
                            AndroidView(
                                factory = { c ->
                                    TextureView(c).apply {
                                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                                val turned = when (display?.rotation) {
                                                    Surface.ROTATION_90 -> 90
                                                    Surface.ROTATION_180 -> 180
                                                    Surface.ROTATION_270 -> 270
                                                    else -> 0
                                                }
                                                rig.window(st, w, h, turned, display)
                                            }

                                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = rig.windowResized(w, h)

                                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                rig.windowGone()
                                                return true
                                            }

                                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                                        }
                                        window = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            AndroidView({ finder }, Modifier.fillMaxSize())
                        }
                        // what the chosen shape keeps; everything outside it is dimmed, not cut
                        val aspect = rig.look.aspect
                        val lines = rig.grid && rig.mode == Mode.Photo
                        Canvas(Modifier.fillMaxSize()) {
                            val k = minOf(size.width / 3f, size.height / 4f)
                            val cut = Aspects.rect(3000, 4000, if (rig.mode == Mode.Photo) aspect else "")
                            val w = 3f * k * cut[2] / 3000f
                            val h = 4f * k * cut[3] / 4000f
                            val x = (size.width - w) / 2f
                            val y = (size.height - h) / 2f
                            if (rig.mode == Mode.Photo && aspect.isNotEmpty()) {
                                val shade = Color.Black.copy(alpha = .52f)
                                val top = (size.height - 4f * k) / 2f
                                val left = (size.width - 3f * k) / 2f
                                drawRect(shade, Offset(left, top), Size(3f * k, y - top))
                                drawRect(shade, Offset(left, y + h), Size(3f * k, top + 4f * k - y - h))
                                drawRect(shade, Offset(left, y), Size(x - left, h))
                                drawRect(shade, Offset(x + w, y), Size(left + 3f * k - x - w, h))
                            }
                            corners(Rect(x, y, x + w, y + h))
                            if (lines) {
                                val line = Color.White.copy(alpha = .4f)
                                for (i in 1..2) {
                                    drawLine(line, Offset(x + w * i / 3f, y), Offset(x + w * i / 3f, y + h), 1.dp.toPx())
                                    drawLine(line, Offset(x, y + h * i / 3f), Offset(x + w, y + h * i / 3f), 1.dp.toPx())
                                }
                            }
                        }
                        var mark by remember { mutableStateOf<Offset?>(null) }
                        val markAlpha = remember { Animatable(0f) }
                        LaunchedEffect(mark) {
                            if (mark != null) {
                                markAlpha.snapTo(1f)
                                markAlpha.animateTo(0f, tween(900, delayMillis = 500))
                            }
                        }
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(rig) { detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) rig.zoomBy(zoom) } }
                                .pointerInput(rig) {
                                    detectTapGestures(onTap = {
                                        dial = null
                                        mark = it
                                        rig.focusAt(it.x, it.y, size.width.toFloat(), size.height.toFloat())
                                    })
                                },
                        ) {
                            mark?.let { at ->
                                val side = 58.dp.toPx()
                                drawRoundRect(
                                    Ink.Amber.copy(alpha = markAlpha.value), Offset(at.x - side / 2f, at.y - side / 2f), Size(side, side),
                                    CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()),
                                )
                            }
                        }
                        // the moment of exposure
                        val dark = remember { Animatable(0f) }
                        LaunchedEffect(rig.exposures) {
                            if (rig.exposures > 0) {
                                dark.snapTo(.9f)
                                dark.animateTo(0f, tween(260))
                            }
                        }
                        if (dark.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dark.value)))
                }
            }
        },
        chrome = {
            val film = shortName(FilmEngine.catalog.film(rig.look.film)?.name ?: rig.look.film)
            TopBar(
                title = "相机",
                sub = "$film · ${if (rig.mode == Mode.Video) CameraRig.qualityName(rig.quality) else rig.look.aspect.ifEmpty { "4:3" }}",
                actions = {
                    if (facts?.flash == true) IconButton(Glyph.Flash, FLASH[rig.flash.coerceIn(0, 2)], {
                        rig.cycleFlash()
                        overlays?.toast(FLASH[rig.flash.coerceIn(0, 2)])
                    }, on = rig.flash != 0)
                    if (rig.mode == Mode.Photo) IconButton(Glyph.Grid, "构图线", rig::toggleGrid, on = rig.grid)
                    BarSep()
                    IconButton(Glyph.Back35, "机背：胶片、片幅、片边、日期", { back = true }, enabled = !rig.recordingBusy)
                },
            )
            when (val status = rig.status) {
                is RigStatus.Failed -> Txt(status.reason, Type.body, Modifier.align(Alignment.Center).padding(start = shell.insetLeft).padding(32.dp), color = Color.White.copy(alpha = .7f), maxLines = 5)
                else -> if (!allowed) {
                    Column(Modifier.align(Alignment.Center).padding(start = shell.insetLeft).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt("取景需要相机权限", Type.title, color = Color.White)
                        Txt("拍下的照片和视频只保存在这台手机上。", Type.label, color = Color.White.copy(alpha = .7f), maxLines = 2)
                        Gap(height = 8.dp)
                        if (refused) Btn("到系统设置里打开", {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }, kind = BtnKind.Line)
                        Btn("允许使用相机", { askCamera.launch(Manifest.permission.CAMERA) }, kind = BtnKind.Key)
                    }
                }
            }
            // what the viewfinder is, said once and quietly: it stands in for the engine, it is not the engine
            if (allowed && !rig.recording) Txt(
                if (rig.previewLutReady) "取景是近似 · 光晕、颗粒以成片为准" else "正在更新胶片预览",
                Type.micro.copy(shadow = Shadow(Color.Black.copy(alpha = .8f), Offset(0f, 1f), 4f)),
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = shell.insetLeft, top = GAP + 54.dp + 10.dp), color = Color.White.copy(alpha = .62f),
            )
            if (rig.recording) {
                Row(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = shell.insetLeft, top = GAP + 54.dp + 14.dp)
                        .glass(17.dp, GlassTone.Thin, shadow = false).height(34.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Lamp(LampState.Rec)
                    Txt(if (rig.saving) "正在保存" else Dials.clock(rig.recordedMs), Type.num.copy(fontSize = 13.sp), color = Ink.GlassText)
                    if (rig.silent) Txt("无声", Type.micro, color = Ink.GlassDim)
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).padding(start = GAP + shell.insetLeft, end = GAP, bottom = GAP + bottomInset()).widthIn(max = 500.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---- the scale of the setting being adjusted
                val open = dial
                if (open != null && facts != null) {
                    Column(Modifier.fillMaxWidth().glass(24.dp, shadow = false).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp)) {
                        DialPanel(open, rig, facts)
                    }
                }
                // ---- the manual settings: one capsule, a droplet on the one being adjusted
                val bounds = remember { mutableStateMapOf<String, Rect>() }
                val droplet = rememberDroplet(dial, dial?.let { bounds[it] })
                if (dials.isNotEmpty() || rig.mode == Mode.Video) {
                    Row(
                        Modifier.fillMaxWidth().glass(27.dp, shadow = false).height(54.dp).padding(5.dp)
                            .drawBehind { drawDroplet(droplet, 22.dp.toPx()) },
                    ) {
                        if (rig.mode == Mode.Video) {
                            if (rig.qualities.isNotEmpty()) Param("画质", CameraRig.qualityName(rig.quality), set = false) {
                                val list = rig.qualities
                                rig.pickQuality(list[(list.indexOf(rig.quality) + 1) % list.size])
                            }
                            val rates = facts?.frameRates.orEmpty().filter { it in VIDEO_RATES }
                            if (rates.size > 1 && rig.frameRate > 0) Param("帧率", "${rig.frameRate}", set = false) {
                                rig.pickFrameRate(rates[(rates.indexOf(rig.frameRate) + 1) % rates.size])
                            }
                        }
                        for (id in dials) {
                            val (value, changed) = readout(id, rig, facts)
                            Param(
                                DIAL_NAMES.getValue(id), value, changed,
                                Modifier.onGloballyPositioned { bounds[id] = it.boundsInParent() },
                            ) { dial = if (dial == id) null else id }
                        }
                    }
                }
                // ---- photograph or video; which lens
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.glass(50.dp, shadow = false).padding(3.dp)) {
                        Seg(
                            listOf(Opt(Mode.Photo.name, "照片"), Opt(Mode.Video.name, "视频")), rig.mode.name,
                            {
                                if (!rig.recordingBusy) {
                                    val m = Mode.valueOf(it)
                                    rig.switchMode(m)
                                    if (m == Mode.Video && !holds(context, Manifest.permission.RECORD_AUDIO)) askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            style = SegStyle.Capsule, bare = true, minItem = 46.dp,
                        )
                    }
                    if (rig.stops.size > 1) Box(Modifier.glass(50.dp, shadow = false).padding(3.dp)) {
                        Seg(
                            rig.stops.mapIndexed { i, s -> Opt(i.toString(), if (s == rig.stop) magnification(rig.zoom) + "×" else s.label) },
                            rig.stops.indexOf(rig.stop).toString(), { rig.pick(rig.stops[it.toInt()]) },
                            style = SegStyle.Capsule, bare = true, minItem = 46.dp,
                        )
                    }
                }
                // ---- the release
                Row(Modifier.fillMaxWidth().glass(36.dp).height(104.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        lastShot?.let { shot ->
                            Box(
                                Modifier.size(52.dp).border(2.dp, Ink.Paper)
                                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !rig.recordingBusy) { onOpen(shot.id) }
                                    .semantics { contentDescription = "刚拍的这张，点开去冲印" },
                            ) { FileImage(shot.thumb, Modifier.fillMaxSize().padding(2.dp)) }
                        }
                    }
                    Shutter(
                        if (rig.mode == Mode.Photo) "拍照" else if (rig.recording) "停止录像" else "开始录像",
                        enabled = rig.status == RigStatus.Running && !(rig.shooting || rig.saving),
                        video = rig.mode == Mode.Video, recording = rig.recording,
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (rig.mode == Mode.Photo) rig.shoot() else rig.toggleRecording()
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (rig.canFlip) Orb("换到另一面的镜头", rig::flip, enabled = !rig.recordingBusy) { Icon(Glyph.Flip, tint = Ink.GlassText) }
                    }
                }
            }
        },
    )

    // ---- the back of the camera: what is loaded, what it writes on the film, and what happens after the release
    val host = remember(rig) {
        object : LookHost {
            override val look: Look get() = rig.look
            override fun change(next: Look) = rig.change(next)
            override fun commit() = Unit
        }
    }
    Sheet(back, { back = false }, title = "机背") {
        Txt(
            "拍下的照片是原片，这卷胶片决定取景的样子和默认的冲洗方式，之后在暗房里可以换。视频会直接录成这卷胶片的样子。",
            Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 4,
        )
        CtlColumn(Modifier.fillMaxWidth()) {
            FilmPanel(host, lastShot, saved, onKeep = null, bleed = 20.dp)
            Ctl("画幅", note = "取景里把裁掉的部分压暗，原片完整保留。") {
                Chips(
                    Aspects.all.filter { it.id != "4:3" }.map { Opt(it.id, if (it.id.isEmpty()) "不裁切" else it.id) }, rig.look.aspect,
                    { rig.change(rig.look.copy(aspect = it)) }, bleed = 20.dp,
                )
            }
            EdgePanel(host, null, bleed = 20.dp)
            if (facts?.raw == true) Ctl("保存 RAW", note = "片边和日期印在成片上；取景里看不到它们。", trailing = { Toggle(rig.raw, { rig.toggleRaw() }, "保存 RAW") })
            Ctl("拍下即冲印", note = "每拍一张，原片存进片库，同时在后台按这里的设置冲一张成片存进相册。关着时只存原片。", trailing = { Toggle(rig.printOnRelease, rig::printOnRelease, "拍下即冲印") })
            Ctl("系统相机", note = "有的手机把长焦等镜头留给自带的相机。在那里拍，照片完整地回到这里，按同样的设置入库和冲印。") {
                Btn("打开", { back = false; openSystemCamera() }, size = BtnSize.Small)
            }
        }
    }
}

private val FLASH = listOf("闪光灯：关", "闪光灯：自动", "闪光灯：开")
private val VIDEO_RATES = listOf(24, 25, 30, 60)
private val DIAL_NAMES = mapOf("ev" to "曝光", "iso" to "感光度", "shutter" to "快门", "focus" to "对焦", "wb" to "白平衡")

/** The settings this camera can take, in the order they are shown. */
private fun dialsOf(f: CameraFacts): List<String> = buildList {
    if (f.isoRange != null && f.shutterRange != null) {
        add("iso")
        add("shutter")
    }
    if (f.minFocus > 0f) add("focus")
    if (f.whiteBalances.isNotEmpty()) add("wb")
    if (!f.evRange.isEmpty() && f.evStep > 0f) add("ev")
}

/** What a setting reads, and whether the person has taken it over from the camera. */
private fun readout(id: String, rig: CameraRig, f: CameraFacts?): Pair<String, Boolean> = when (id) {
    "ev" -> Dials.ev(rig.ev * (f?.evStep ?: 0f)) to (rig.ev != 0)
    "iso" -> (if (rig.iso == null) "自动" else Dials.iso(rig.iso ?: rig.liveIso)) to (rig.iso != null)
    "shutter" -> (if (rig.shutter == null) "自动" else Dials.shutter(rig.shutter ?: rig.liveShutter)) to (rig.shutter != null)
    "focus" -> (rig.focus?.let(Dials::focus) ?: "自动") to (rig.focus != null)
    else -> Lenses.whiteBalanceName(rig.whiteBalance) to (rig.whiteBalance != CameraMetadata.CONTROL_AWB_MODE_AUTO)
}

/** One setting in the capsule: its name over its value; the value is amber once it is the person's own. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Param(label: String, value: String, set: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.weight(1f).fillMaxHeight().clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Txt(label, Type.micro.copy(lineHeight = 13.sp), color = Ink.GlassDim)
        Txt(value, Type.num.copy(fontSize = 13.sp, lineHeight = 16.sp), color = if (set) Ink.Accent else Ink.GlassText)
    }
}

/** A third of a stop to a step. */
private const val THIRD = 1f / 3f

@Composable
private fun DialHead(label: String, value: String, set: Boolean, auto: (() -> Unit)? = null, automatic: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Type.body, Modifier.weight(1f), color = Ink.GlassText)
        if (auto != null) {
            Chip("自动", automatic, auto, height = 28.dp)
            Gap(width = 12.dp)
        }
        Txt(value, Type.num.copy(fontSize = 13.5.sp), color = if (set) Ink.Accent else Ink.GlassDim)
    }
}

@Composable
private fun DialPanel(id: String, rig: CameraRig, f: CameraFacts) {
    val (value, changed) = readout(id, rig, f)
    when (id) {
        "ev" -> {
            DialHead("曝光", value, changed)
            if (rig.manualExposure) {
                Txt("感光度和快门是手动的时候，曝光补偿不起作用。", Type.micro, color = Ink.GlassDim, maxLines = 2)
            } else {
                val step = f.evStep
                Ruler("曝光补偿", rig.ev * step, f.evRange.first * step, f.evRange.last * step, step, 0f, { rig.changeEv((it / step).roundToInt()) }, {})
            }
        }
        "iso" -> f.isoRange?.let { range ->
            DialHead("感光度", if (rig.iso == null) "自动 · ${Dials.iso(rig.liveIso)}" else value, changed, rig::autoExposure, !rig.manualExposure)
            val low = Dials.isoStops(range.first)
            Ruler(
                "感光度", Dials.isoStops((rig.iso ?: rig.liveIso.takeIf { it > 0 } ?: 100).coerceIn(range.first, range.last)),
                low, Dials.isoStops(range.last), THIRD, low, { rig.changeIso(Dials.isoFromStops(it)) }, {}, sticky = false,
            )
        }
        "shutter" -> f.shutterRange?.let { range ->
            // nothing longer than a second: the viewfinder would stand still
            val longest = min(range.last, 1_000_000_000L)
            DialHead("快门", if (rig.shutter == null) "自动 · ${Dials.shutter(rig.liveShutter)}" else value, changed, rig::autoExposure, !rig.manualExposure)
            val low = Dials.shutterStops(range.first)
            Ruler(
                "快门", Dials.shutterStops((rig.shutter ?: rig.liveShutter.takeIf { it > 0 } ?: 16_666_666L).coerceIn(range.first, longest)),
                low, Dials.shutterStops(longest), THIRD, low, { rig.changeShutter(Dials.shutterFromStops(it)) }, {}, sticky = false,
            )
        }
        "focus" -> {
            DialHead("对焦", value, changed, rig::autoFocus, rig.focus == null)
            Ruler("对焦", (rig.focus ?: rig.liveFocus).coerceIn(0f, f.minFocus), 0f, f.minFocus, f.minFocus / 80f, 0f, rig::changeFocus, {}, sticky = false)
        }
        else -> {
            DialHead("白平衡", value, changed)
            Chips(
                (listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO) + f.whiteBalances).map { Opt(it.toString(), Lenses.whiteBalanceName(it)) },
                rig.whiteBalance.toString(), { rig.changeWhiteBalance(it.toInt()) }, bleed = 20.dp, height = 32.dp,
            )
        }
    }
}

/**
 * The release: a ring with a disc in it. White for a photograph, red for
 * video, a red square while recording. Shared with the monitor's remote release.
 */
@Composable
fun Shutter(label: String, enabled: Boolean, video: Boolean = false, recording: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(if (pressed) 80 else 250, easing = com.spektra.latent.ui.Motion.Ease), label = "release")
    val square by animateFloatAsState(if (recording) 1f else 0f, tween(300, easing = com.spektra.latent.ui.Motion.Ease), label = "square")
    val ring = Ink.GlassText
    val disc = if (video) Ink.Rec else Ink.GlassText
    Canvas(
        Modifier
            .size(74.dp)
            .graphicsLayer {
                val s = 1f - .07f * press
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else .5f
            }
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        val r = size.minDimension / 2f
        drawCircle(ring, r - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
        val inner = (r - 8.dp.toPx()) * (1f - .1f * press) * (1f - .52f * square)
        val corner = inner * (1f - square) + 9.dp.toPx() * .48f * square
        drawRoundRect(disc, Offset(center.x - inner, center.y - inner), Size(inner * 2, inner * 2), CornerRadius(corner))
    }
}

/** Corner marks on anything that is "in the gate". */
@Composable
fun Corners(modifier: Modifier = Modifier) {
    Canvas(modifier) { corners(Rect(Offset.Zero, size)) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.corners(r: Rect) {
    val arm = 14.dp.toPx()
    val inset = 10.dp.toPx()
    val line = Color.White.copy(alpha = .6f)
    val w = 1.dp.toPx()
    for ((cx, sx) in listOf(r.left + inset to 1f, r.right - inset to -1f)) for ((cy, sy) in listOf(r.top + inset to 1f, r.bottom - inset to -1f)) {
        drawLine(line, Offset(cx, cy), Offset(cx + arm * sx, cy), w)
        drawLine(line, Offset(cx, cy), Offset(cx, cy + arm * sy), w)
    }
}

private fun magnification(ratio: Float): String {
    val whole = ratio.roundToInt()
    return if (ratio >= .95f && abs(ratio - whole) < .05f) "$whole" else String.format(Locale.US, "%.1f", ratio)
}

private fun holds(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
