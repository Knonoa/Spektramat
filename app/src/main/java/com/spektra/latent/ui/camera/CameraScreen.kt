package com.spektra.latent.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.net.Uri
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spektra.latent.LatentApp
import com.spektra.latent.camera.CameraFacts
import com.spektra.latent.camera.CameraRig
import com.spektra.latent.camera.Dials
import com.spektra.latent.camera.Lenses
import com.spektra.latent.camera.Mode
import com.spektra.latent.camera.RigStatus
import com.spektra.latent.data.Library
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Notice
import com.spektra.latent.ui.ParamChip
import com.spektra.latent.ui.Pill
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.StockStrip
import com.spektra.latent.ui.ChoiceStrip
import com.spektra.latent.ui.Icon
import com.spektra.latent.ui.ScrambleText
import com.spektra.latent.ui.SettingRow
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.darkroom.EdgeBench
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.Recipes
import com.spektra.latent.model.Aspects
import com.spektra.latent.model.Choice
import com.spektra.latent.model.Format
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.filmStocks
import com.spektra.latent.ui.pressable
import com.spektra.latent.ui.shortName
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
fun CameraScreen(library: Library, prints: PrintDesk, recipes: Recipes, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LatentApp
    val owner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val rig = remember { CameraRig(context, library, app.scope) }

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
    var loading by remember { mutableStateOf(false) }
    var back by remember { mutableIntStateOf(0) }       // which page of the back is open: film, edge and date, shooting
    val saved by recipes.all.collectAsStateWithLifecycle()
    val facts = rig.facts
    val dials = (if (rig.mode == Mode.Photo) listOf("aspect") else emptyList()) + facts?.let { dialsOf(it) }.orEmpty()

    // a print of every photograph as soon as it is filed, when the person has asked for that
    DisposableEffect(rig) {
        rig.onFiled = { frame -> if (rig.printOnRelease) prints.order(frame, frame.look, 3072) }
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // ---- top: the way out, the film that is loaded, what is recorded
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Glyph.Back, "回到片库", onBack)
                Column(Modifier.weight(1f).padding(start = 4.dp).pressable(enabled = !rig.recordingBusy) { loading = true }) {
                    ScrambleText(shortName(FilmEngine.catalog.film(rig.look.film)?.name ?: rig.look.film), Type.edge, color = Ink.Amber)
                    Txt(
                        listOfNotNull(
                            "机背", Format.of(rig.look.format).label,
                            "片边".takeIf { rig.look.edge.on }, "日期".takeIf { rig.look.date.on },
                            "即冲印".takeIf { rig.printOnRelease },
                        ).joinToString(" · "),
                        Type.caption,
                    )
                }
                if (facts?.flash == true) {
                    Box(
                        Modifier.pressable(onClick = rig::cycleFlash).padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Glyph.Flash, tint = if (rig.flash == 0) Ink.Dim else Ink.Amber, size = 20.dp)
                            if (rig.flash == 1) Txt("A", Type.edgeSmall, color = Ink.Amber)
                        }
                    }
                }
                if (rig.mode == Mode.Photo && facts?.raw == true) {
                    Txt(
                        "RAW", Type.edge,
                        Modifier.pressable(onClick = rig::toggleRaw).padding(horizontal = 14.dp, vertical = 10.dp),
                        color = if (rig.raw) Ink.Amber else Ink.Dim,
                    )
                }
            }

            // ---- the viewfinder
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (allowed) {
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
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AndroidView({ finder }, Modifier.fillMaxSize())
                    }
                    Txt(
                        if (rig.previewLutReady) "实时预览 · 光晕/颗粒等效果以成片为准" else "正在更新胶片预览",
                        Type.caption, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    )
                    if (rig.mode == Mode.Photo && (rig.look.aspect.isNotEmpty() || rig.grid)) {
                        val aspect = rig.look.aspect
                        val lines = rig.grid
                        Canvas(Modifier.fillMaxSize()) {
                            // the sensor is 4:3 and the screen is upright: the picture fills the width or the height
                            val k = minOf(size.width / 3f, size.height / 4f)
                            val pw = 3f * k
                            val ph = 4f * k
                            val cut = Aspects.rect(3000, 4000, aspect)
                            val w = pw * cut[2] / 3000f
                            val h = ph * cut[3] / 4000f
                            val x = (size.width - w) / 2f
                            val y = (size.height - h) / 2f
                            if (aspect.isNotEmpty()) {
                                // what will be cut away is dimmed, not hidden: the photographer sees what is just outside
                                val shade = Color.Black.copy(alpha = 0.62f)
                                drawRect(shade, Offset(0f, 0f), Size(size.width, y))
                                drawRect(shade, Offset(0f, y + h), Size(size.width, size.height - y - h))
                                drawRect(shade, Offset(0f, y), Size(x, h))
                                drawRect(shade, Offset(x + w, y), Size(size.width - x - w, h))
                                drawRect(Color.White.copy(alpha = 0.7f), Offset(x, y), Size(w, h), style = Stroke(1.dp.toPx()))
                            }
                            if (lines) {
                                val line = Color.White.copy(alpha = 0.32f)
                                for (i in 1..2) {
                                    drawLine(line, Offset(x + w * i / 3f, y), Offset(x + w * i / 3f, y + h), 1.dp.toPx())
                                    drawLine(line, Offset(x, y + h * i / 3f), Offset(x + w, y + h * i / 3f), 1.dp.toPx())
                                }
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
                            .pointerInput(rig) {
                                detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) rig.zoomBy(zoom) }
                            }
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
                                Ink.Amber.copy(alpha = markAlpha.value),
                                Offset(at.x - side / 2f, at.y - side / 2f), Size(side, side),
                                CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()),
                            )
                        }
                    }
                    // the moment of exposure
                    val dark = remember { Animatable(0f) }
                    LaunchedEffect(rig.exposures) {
                        if (rig.exposures > 0) {
                            dark.snapTo(0.9f)
                            dark.animateTo(0f, tween(220))
                        }
                    }
                    if (dark.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dark.value)))
                }

                when (val status = rig.status) {
                    is RigStatus.Failed -> Txt(
                        status.reason, Type.body, Modifier.align(Alignment.Center).padding(32.dp), color = Ink.Dim, maxLines = 5,
                    )
                    else -> if (!allowed) {
                        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Txt("取景需要相机权限", Type.heading)
                            Gap(height = 6.dp)
                            Txt("拍下的照片和视频只保存在这台手机上。", Type.label, color = Ink.Dim, maxLines = 2)
                            Gap(height = 18.dp)
                            if (refused) {
                                Pill("到系统设置里打开", {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                })
                                Gap(height = 10.dp)
                            }
                            Pill("允许使用相机", { askCamera.launch(Manifest.permission.CAMERA) }, filled = true)
                        }
                    }
                }

                if (rig.recording) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(Ink.Red, CircleShape))
                        Gap(width = 8.dp)
                        Txt(if (rig.saving) "正在保存" else Dials.clock(rig.recordedMs), if (rig.saving) Type.label else Type.edge)
                        if (rig.silent) {
                            Gap(width = 8.dp)
                            Txt("无声", Type.caption)
                        }
                    }
                }

                // ---- the dial of the chosen setting, over the foot of the viewfinder
                val open = dial
                if (open == "aspect") {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(68.dp).background(Ink.Body.copy(alpha = 0.88f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChoiceStrip(
                            Aspects.all.filter { it.id != "4:3" }.map { Choice(it.id, if (it.id.isEmpty()) "4:3 全幅" else it.id) }, rig.look.aspect,
                            { rig.change(rig.look.copy(aspect = it)) },
                        )
                    }
                } else if (open != null && facts != null) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(68.dp).background(Ink.Body.copy(alpha = 0.88f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        DialPanel(open, rig, facts)
                    }
                }
            }

            // ---- lenses
            if (rig.stops.size > 1) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (s in rig.stops) {
                        val on = s == rig.stop
                        Box(
                            Modifier
                                .size(if (on) 42.dp else 34.dp)
                                .clip(CircleShape)
                                .background(Ink.Raised)
                                .pressable { rig.pick(s) }
                                .semantics { contentDescription = "${s.label} 倍镜头" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Txt(
                                if (on) magnification(rig.zoom) + "×" else s.label,
                                Type.edgeSmall, color = if (on) Ink.Amber else Ink.Text,
                            )
                        }
                    }
                }
            }

            // ---- settings this camera has
            Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (rig.mode == Mode.Video) {
                        if (rig.qualities.isNotEmpty()) {
                            ParamChip("画质", CameraRig.qualityName(rig.quality), changed = false, selected = false, onClick = {
                                val list = rig.qualities
                                rig.pickQuality(list[(list.indexOf(rig.quality) + 1) % list.size])
                            })
                        }
                        val rates = facts?.frameRates.orEmpty().filter { it in VIDEO_RATES }
                        if (rates.size > 1 && rig.frameRate > 0) {
                            ParamChip("帧率", "${rig.frameRate}", changed = false, selected = false, onClick = {
                                rig.pickFrameRate(rates[(rates.indexOf(rig.frameRate) + 1) % rates.size])
                            })
                        }
                    }
                    for (id in dials) {
                        val (value, changed) = readout(id, rig, facts)
                        ParamChip(DIAL_NAMES.getValue(id), value, changed, selected = dial == id, onClick = { dial = if (dial == id) null else id })
                    }
                    if (rig.mode == Mode.Photo) {
                        ParamChip("构图线", if (rig.grid) "开" else "关", changed = rig.grid, selected = false, onClick = rig::toggleGrid)
                    }
                }
            }

            // ---- photograph or video
            Row(
                Modifier.fillMaxWidth().height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (m in Mode.entries) {
                    Txt(
                        if (m == Mode.Photo) "照片" else "视频", Type.label,
                        Modifier.pressable(enabled = !rig.recordingBusy) {
                            rig.switchMode(m)
                            if (m == Mode.Video && !holds(context, Manifest.permission.RECORD_AUDIO)) {
                                askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }.padding(horizontal = 6.dp, vertical = 6.dp),
                        color = if (m == rig.mode) Ink.Amber else Ink.Dim,
                    )
                }
            }

            if (rig.mode == Mode.Video) {
                Txt("录制关闭自动测光、光晕、颗粒、扩散、眩光与锐化", Type.caption,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Ink.Dim, maxLines = 2)
            }

            // ---- the release
            Box(Modifier.fillMaxWidth().navigationBarsPadding().height(104.dp)) {
                rig.lastShot?.let { shot ->
                    Box(
                        Modifier.align(Alignment.CenterStart).padding(start = 30.dp).size(52.dp)
                            .clip(RoundedCornerShape(8.dp)).border(1.dp, Ink.Line, RoundedCornerShape(8.dp))
                            .pressable(enabled = !rig.recordingBusy) { onOpen(shot.id) }
                            .semantics { contentDescription = "刚拍的这张，点开去暗房" },
                    ) {
                        FileImage(shot.thumb, Modifier.fillMaxSize())
                    }
                }
                Release(
                    video = rig.mode == Mode.Video, recording = rig.recording, busy = rig.shooting || rig.saving,
                    enabled = rig.status == RigStatus.Running,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (rig.mode == Mode.Photo) rig.shoot() else rig.toggleRecording()
                }
                if (rig.canFlip) {
                    IconButton(
                        Glyph.Flip, "换到另一面的镜头", rig::flip,
                        Modifier.align(Alignment.CenterEnd).padding(end = 30.dp), enabled = !rig.recordingBusy,
                    )
                }
            }
        }

        Notice(rig.notice, { rig.notice = null }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 150.dp))

        Sheet(loading, { loading = false }) {
            // the back of the camera: what is loaded, what it writes on the film, and what happens after the release
            Row(verticalAlignment = Alignment.CenterVertically) {
                for ((i, name) in listOf("胶片", "片边与日期", "拍摄").withIndex()) {
                    Box(Modifier.pressable { back = i }.padding(end = 18.dp, top = 4.dp, bottom = 8.dp)) {
                        Txt(name, Type.heading, color = if (i == back) Ink.Text else Ink.Dim)
                    }
                }
            }
            when (back) {
                0 -> {
                    Txt("拍下的照片是原片，这卷胶片决定取景的样子和默认的冲洗方式，之后在暗房里可以换。视频会直接录成这卷胶片的样子。", Type.label, color = Ink.Dim, maxLines = 4)
                    Gap(height = 14.dp)
                    StockStrip(filmStocks(), rig.look.film, { rig.load(it) })
                    Gap(height = 12.dp)
                    Txt("片幅", Type.caption)
                    Gap(height = 6.dp)
                    ChoiceStrip(Format.entries.map { Choice(it.id, it.label) }, rig.look.format, { rig.change(rig.look.copy(format = it)) })
                    if (saved.isNotEmpty()) {
                        Gap(height = 12.dp)
                        Txt("装入配方", Type.caption)
                        Gap(height = 6.dp)
                        ChoiceStrip(saved.map { Choice(it.id.toString(), it.name) }, "", { id ->
                            saved.firstOrNull { it.id.toString() == id }?.let { rig.change(rig.look.withRecipe(it.look)) }
                        })
                    }
                    Gap(height = 10.dp)
                }
                1 -> {
                    EdgeBench(rig.look, null, rig::change, {})
                    Txt("片边和日期印在成片上；取景里看不到它们。", Type.caption, Modifier.padding(top = 6.dp), maxLines = 2)
                    Gap(height = 6.dp)
                }
                else -> {
                    SettingRow("拍下即冲印", "每拍一张，原片存进片库，同时在后台按相机里的设置冲印一张长边 3072 的成片，存进相册。") {
                        Toggle(rig.printOnRelease, rig::printOnRelease, "拍下即冲印")
                    }
                    SettingRow("系统相机", "有的手机把长焦等镜头留给自带的相机。在那里拍，照片完整地回到这里，按同样的设置入库和冲印。") {
                        Pill("打开", { loading = false; openSystemCamera() })
                    }
                }
            }
        }
    }
}

private val VIDEO_RATES = listOf(24, 25, 30, 60)

private val DIAL_NAMES = mapOf("aspect" to "画幅", "ev" to "曝光补偿", "iso" to "感光度", "shutter" to "快门", "focus" to "对焦", "wb" to "白平衡")

/** The settings this camera can take, in the order they are shown. */
private fun dialsOf(f: CameraFacts): List<String> = buildList {
    if (!f.evRange.isEmpty() && f.evStep > 0f) add("ev")
    if (f.isoRange != null && f.shutterRange != null) {
        add("iso")
        add("shutter")
    }
    if (f.minFocus > 0f) add("focus")
    if (f.whiteBalances.isNotEmpty()) add("wb")
}

/** What a chip shows, and whether the person has taken the setting over from the camera. */
private fun readout(id: String, rig: CameraRig, f: CameraFacts?): Pair<String, Boolean> = when (id) {
    "aspect" -> (if (rig.look.aspect.isEmpty()) "4:3" else rig.look.aspect) to rig.look.aspect.isNotEmpty()
    "ev" -> Dials.ev(rig.ev * (f?.evStep ?: 0f)) to (rig.ev != 0)
    "iso" -> Dials.iso(rig.iso ?: rig.liveIso) to (rig.iso != null)
    "shutter" -> Dials.shutter(rig.shutter ?: rig.liveShutter) to (rig.shutter != null)
    "focus" -> (rig.focus?.let(Dials::focus) ?: "自动") to (rig.focus != null)
    else -> Lenses.whiteBalanceName(rig.whiteBalance) to (rig.whiteBalance != CameraMetadata.CONTROL_AWB_MODE_AUTO)
}

/** Ten small marks to a numbered one; a numbered mark every ten thirds of a stop, which is a factor of ten. */
private const val THIRD = 1f / 3f
private const val DECADE = 10f / 3f

@Composable
private fun DialPanel(id: String, rig: CameraRig, f: CameraFacts) {
    when (id) {
        "ev" -> if (rig.manualExposure) {
            Txt("感光度和快门是手动的时候，曝光补偿不起作用。", Type.label, color = Ink.Dim, maxLines = 2)
        } else {
            val step = f.evStep
            Ruler(
                name = "曝光补偿", value = rig.ev * step,
                min = f.evRange.first * step, max = f.evRange.last * step, step = step, major = 1f, default = 0f,
                label = Dials::ev,
                onChange = { rig.changeEv((it / step).roundToInt()) }, onSettled = {},
            )
        }
        "iso" -> f.isoRange?.let { range ->
            WithAuto(automatic = !rig.manualExposure, onAuto = rig::autoExposure) {
                Ruler(
                    name = "感光度", value = Dials.isoStops((rig.iso ?: rig.liveIso.takeIf { it > 0 } ?: 100).coerceIn(range.first, range.last)),
                    min = Dials.isoStops(range.first), max = Dials.isoStops(range.last), step = THIRD, major = DECADE,
                    default = Dials.isoStops(range.first),
                    label = { Dials.iso(Dials.isoFromStops(it)) },
                    onChange = { rig.changeIso(Dials.isoFromStops(it)) }, onSettled = {},
                )
            }
        }
        "shutter" -> f.shutterRange?.let { range ->
            // nothing longer than a second: the viewfinder would stand still
            val longest = min(range.last, 1_000_000_000L)
            WithAuto(automatic = !rig.manualExposure, onAuto = rig::autoExposure) {
                Ruler(
                    name = "快门", value = Dials.shutterStops((rig.shutter ?: rig.liveShutter.takeIf { it > 0 } ?: 16_666_666L).coerceIn(range.first, longest)),
                    min = Dials.shutterStops(range.first), max = Dials.shutterStops(longest), step = THIRD, major = DECADE,
                    default = Dials.shutterStops(16_666_666L.coerceIn(range.first, longest)),
                    label = { Dials.shutter(Dials.shutterFromStops(it)) },
                    onChange = { rig.changeShutter(Dials.shutterFromStops(it)) }, onSettled = {},
                )
            }
        }
        "focus" -> WithAuto(automatic = rig.focus == null, onAuto = rig::autoFocus) {
            val major = f.minFocus / 4f
            Ruler(
                name = "对焦", value = (rig.focus ?: rig.liveFocus).coerceIn(0f, f.minFocus),
                min = 0f, max = f.minFocus, step = major / 20f, major = major, default = 0f,
                label = Dials::focus,
                onChange = rig::changeFocus, onSettled = {},
            )
        }
        else -> Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            for (mode in listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO) + f.whiteBalances) {
                Txt(
                    Lenses.whiteBalanceName(mode), Type.label,
                    Modifier.pressable { rig.changeWhiteBalance(mode) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = if (mode == rig.whiteBalance) Ink.Amber else Ink.Dim,
                )
            }
        }
    }
}

/** A ruler with the way back to automatic beside it. */
@Composable
private fun WithAuto(automatic: Boolean, onAuto: () -> Unit, ruler: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(
            "自动", Type.label,
            Modifier.pressable(enabled = !automatic, onClick = onAuto).padding(horizontal = 14.dp, vertical = 12.dp),
            color = if (automatic) Ink.Amber else Ink.Text,
        )
        Box(Modifier.weight(1f)) { ruler() }
    }
}

/** The shutter release: a ring; white inside for a photograph, red for video, a red square while recording. */
@Composable
private fun Release(video: Boolean, recording: Boolean, busy: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Canvas(
        modifier
            .size(76.dp)
            .pressable(enabled = enabled && !busy, onClick = onClick)
            .semantics { contentDescription = if (!video) "拍照" else if (recording) "停止录像" else "开始录像" },
    ) {
        val r = size.minDimension / 2f
        drawCircle(Ink.Text, r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
        when {
            recording -> {
                val side = r * 0.72f
                drawRoundRect(Ink.Red, Offset(center.x - side / 2f, center.y - side / 2f), Size(side, side), CornerRadius(side * 0.2f))
            }
            video -> drawCircle(Ink.Red, r - 8.dp.toPx())
            else -> drawCircle(Ink.Text, r - 8.dp.toPx())
        }
    }
}

private fun magnification(ratio: Float): String {
    val whole = ratio.roundToInt()
    return if (ratio >= 0.95f && abs(ratio - whole) < 0.05f) "$whole" else String.format(Locale.US, "%.1f", ratio)
}

private fun holds(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
