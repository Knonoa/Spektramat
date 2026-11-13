package com.spektra.latent.ui.monitor

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.APP_NAME
import com.spektra.latent.camera.Dials
import com.spektra.latent.data.Recipes
import com.spektra.latent.data.Roll
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Look
import com.spektra.latent.tether.Monitor
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnKind
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
import com.spektra.latent.ui.GlyphField
import com.spektra.latent.ui.GlyphSubject
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
import com.spektra.latent.ui.Seg
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.PageScheme
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.TopBar
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.activity
import com.spektra.latent.ui.ambientOf
import com.spektra.latent.ui.bottomInset
import com.spektra.latent.ui.camera.Corners
import com.spektra.latent.ui.camera.Shutter
import com.spektra.latent.ui.darkroom.FilmPanel
import com.spektra.latent.ui.darkroom.LookHost
import com.spektra.latent.ui.glass
import com.spektra.latent.ui.library.NewProject
import com.spektra.latent.ui.shortName
import com.spektra.latent.ui.softShadow
import com.spektra.latent.ui.topInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The monitor: tethered shooting. A camera on a USB cable becomes a
 * viewfinder here, and what it shoots arrives as originals.
 *
 * Tethering starts only after a project has been chosen: every original is
 * filed under that project, and it is the same list of projects the lab shows.
 */
@Composable
fun MonitorScreen(monitor: Monitor, recipes: Recipes, onProject: (Long) -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlays.current
    val projects by monitor.projects.collectAsStateWithLifecycle()
    val saved by recipes.all.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    var colour by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    DisposableEffect(monitor) {
        monitor.watched = true
        monitor.refresh()
        onDispose { monitor.watched = false }
    }
    DisposableEffect(monitor.connected) {
        val window = context.activity()?.window
        if (monitor.connected) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    Notice(monitor.notice, { monitor.notice = null })

    if (monitor.connected) Live(monitor, projects, onOpen, onProject, { colour = true }, { settings = true })
    else Waiting(monitor, projects) { creating = true }

    // ---- which project, how sharp, what is burnt in
    Sheet(settings, { settings = false }, title = "监看设置") {
        CtlColumn(Modifier.fillMaxWidth()) {
            Ctl("原片归入项目", note = "换项目只影响之后拍的，已经收到的原片留在原来的项目里。") {
                ProjectChips(monitor, projects, 20.dp) { settings = false; creating = true }
            }
            Ctl("监看画质") {
                Seg(listOf(Opt("480", "480p"), Opt("720", "720p")), monitor.resolution.toString(), { monitor.pickResolution(it.toInt()) }, Modifier.fillMaxWidth(), block = true)
            }
            Ctl("把参数印进录像", note = "感光度、光圈、快门叠在录下的画面上。", trailing = { Toggle(monitor.burnIn, monitor::burnIn, "把参数印进录像") })
            Ctl("原片同时存到手机", note = "相机拍的原片除了进项目，再在 Download/${APP_NAME} 里留一份。", trailing = { Toggle(monitor.publicCopy, monitor::publicCopy, "原片同时存到手机") })
            Column(Modifier.padding(vertical = 10.dp)) {
                if (monitor.connected) Btn("断开连接", { settings = false; monitor.disconnect() }, Modifier.fillMaxWidth(), BtnKind.Line)
                else Btn("重新查找相机", monitor::refresh, Modifier.fillMaxWidth())
            }
        }
    }
    // ---- the film the monitor is seen through, and that received originals are filed with
    val host = remember(monitor) {
        object : LookHost {
            override val look: Look get() = monitor.look
            override fun change(next: Look) = monitor.change(next)
            override fun commit() = Unit
        }
    }
    Sheet(colour, { colour = false }, title = "监看色彩") {
        CtlColumn(Modifier.fillMaxWidth()) {
            Ctl("胶片监看", note = "收到的原片也用这份设置，可以在暗房里继续调。", trailing = { Toggle(monitor.filmOn, monitor::film, "胶片监看") })
            FilmPanel(host, monitor.recentShots.firstOrNull(), saved, onKeep = null, bleed = 20.dp)
        }
    }
    NewProject(creating, { creating = false }, name, { name = it }) {
        val label = name.trim()
        scope.launch {
            try {
                monitor.createProject(label)
                name = ""
                creating = false
                overlays?.toast("已新建项目", label)
            } catch (e: Exception) {
                overlays?.toast("项目没能创建：${e.message}")
            }
        }
    }
}

private fun filmName(monitor: Monitor) = shortName(FilmEngine.catalog.film(monitor.look.film)?.name ?: monitor.look.film)

/** The lab's projects as chips, and a way to start a new one. */
@Composable
private fun ProjectChips(monitor: Monitor, projects: List<Roll>, bleed: androidx.compose.ui.unit.Dp, onCreate: () -> Unit) {
    Chips(
        projects.map { Opt(it.id.toString(), it.name) }, monitor.projectId?.toString(), { monitor.selectProject(it.toLong()) },
        bleed = bleed, trailing = { Chip("新建项目", false, onCreate, icon = Glyph.Plus) },
    )
}

// ------------------------------------------------------------------ not connected: pick the project, then plug in

@Composable
private fun Waiting(monitor: Monitor, projects: List<Roll>, onCreate: () -> Unit) {
    val shell = LocalShell.current
    val chosen = projects.any { it.id == monitor.projectId }
    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            // a camera on the end of a cable
            GlyphField(GlyphSubject.Camera(.27f), Modifier.fillMaxSize(), cell = 8.dp, noise = .12f)
        },
        chrome = {
            TopBar(title = "监视器", sub = if (monitor.busy) monitor.status else "未连接")
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp + shell.insetLeft, end = 24.dp, bottom = maxHeight * .05f + bottomInset())
                        .heightIn(max = maxHeight * .62f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Txt("接上相机", Type.display)
                    Txt(
                        "用 USB 线连接 Sony 相机，在相机里选「电脑遥控」。连上以后，这里就是它的取景器，拍下的原片直接送过来。",
                        Type.body.copy(lineHeight = 24.5.sp), Modifier.widthIn(max = 336.dp), color = Ink.Dim, maxLines = 4,
                    )
                    // before tethering: which project the originals are filed under
                    Column(
                        Modifier.padding(top = 8.dp).widthIn(max = 430.dp).fillMaxWidth().glass(26.dp).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Txt("原片归入项目", Type.body, Modifier.weight(1f), color = Ink.GlassText)
                            Txt("和冲印室里的项目是同一份", Type.micro, color = Ink.GlassDim)
                        }
                        ProjectChips(monitor, projects, 14.dp, onCreate)
                        if (monitor.devices.isEmpty()) {
                            Btn("查找 USB 相机", monitor::refresh, Modifier.fillMaxWidth(), BtnKind.Key, icon = Glyph.Plug, enabled = chosen && !monitor.busy)
                        } else {
                            monitor.devices.forEachIndexed { index, d ->
                                Btn(
                                    if (monitor.busy) "正在连接…" else "连接 ${d.name}", { monitor.connect(d) }, Modifier.fillMaxWidth(),
                                    if (index == 0) BtnKind.Key else BtnKind.Glass, icon = Glyph.Plug, enabled = chosen && !monitor.busy,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Lamp(if (monitor.busy) LampState.On else LampState.Off)
                        Txt(if (!chosen) "先选一个项目，才能开始联机" else monitor.status, Type.label, color = Ink.Faint, maxLines = 2)
                    }
                }
            }
        },
    )
}

// ------------------------------------------------------------------ connected

@Composable
private fun Live(
    monitor: Monitor, projects: List<Roll>, onOpen: (Long) -> Unit, onProject: (Long) -> Unit,
    onColour: () -> Unit, onSettings: () -> Unit,
) {
    val shell = LocalShell.current
    val haptic = LocalHapticFeedback.current
    val picture = monitor.picture
    val project = projects.firstOrNull { it.id == monitor.projectId }
    PageScheme(light = false)
    // a few times a second is enough for the light in the room
    var room by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            monitor.picture?.let { room = withContext(Dispatchers.Default) { runCatching { ambientOf(it.asAndroidBitmap(), 24) }.getOrNull() } ?: room }
            delay(300)
        }
    }
    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Color.Black))
            // the picture's own light fills the room behind it
            room?.let {
                val richer = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) }) }
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .7f, colorFilter = richer, filterQuality = FilterQuality.High)
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = shell.insetLeft, top = topInset() + 4.dp, bottom = 270.dp + bottomInset()), contentAlignment = Alignment.Center) {
                if (picture != null) {
                    val shape = picture.width.toFloat() / picture.height
                    val across = if (maxWidth < maxHeight * shape) maxWidth else maxHeight * shape
                    val faded = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.3f) }) }
                    Box(
                        Modifier
                            .size(across, across / shape)
                            .drawBehind { softShadow(Rect(Offset.Zero, size), 0f, Color.Black.copy(alpha = .9f), 30.dp.toPx(), 60.dp.toPx(), -30.dp.toPx()) },
                    ) {
                        Image(
                            picture, "相机取景画面", Modifier.fillMaxSize().graphicsLayer { alpha = if (monitor.stale) .6f else 1f },
                            contentScale = ContentScale.FillBounds, colorFilter = if (monitor.stale) faded else null,
                        )
                        Corners(Modifier.matchParentSize())
                        if (monitor.recording && monitor.burnIn) {
                            Txt(
                                listOf("ISO ${monitor.readings.iso}", monitor.readings.aperture, monitor.readings.shutter).filter { it.isNotBlank() }.joinToString("  "),
                                Type.num.copy(fontSize = 12.sp, letterSpacing = .12.em, shadow = Shadow(Color.Black.copy(alpha = .8f), Offset(0f, 1f), 4f)),
                                Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 10.dp), color = Color.White,
                            )
                        }
                        // a photograph is coming over the cable
                        if (monitor.transfer >= 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(monitor.transfer.coerceIn(.02f, 1f)).height(3.dp).background(Ink.Amber))
                    }
                } else GlyphField(GlyphSubject.Room, Modifier.fillMaxSize(), noise = .2f, ink = Color(0xFFB6B0A4))
            }
        },
        chrome = {
            TopBar(
                title = "监视器",
                sub = if (monitor.recording) "● 录制中 ${Dials.clock(monitor.recordedMs)}"
                else "${monitor.model} · ${monitor.resolution}p" + if (monitor.stale) " · 无信号" else " · ${String.format(Locale.US, "%.0f", monitor.fps)} fps",
                subInk = if (monitor.recording) Ink.Rec else Color.Unspecified,
                actions = { IconButton(Glyph.More, "监看设置", onSettings) },
            )
            if (picture != null && monitor.stale) {
                Row(
                    Modifier.align(Alignment.Center).padding(start = shell.insetLeft, bottom = 180.dp).glass(19.dp, shadow = false).height(38.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Lamp(LampState.Off)
                    Txt("信号中断 · 这是最后一帧", Type.body.copy(fontSize = 13.sp), color = Ink.GlassText)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(start = GAP + shell.insetLeft, end = GAP, bottom = GAP + bottomInset()).widthIn(max = 500.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---- where the originals are going, and the ones that have arrived
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier
                            .weight(1f, fill = false)
                            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = project != null) { project?.let { onProject(it.id) } }
                            .semantics { contentDescription = "原片归入项目「${project?.name ?: ""}」，点开看这个项目" }
                            .glass(21.dp, GlassTone.Thin, shadow = false)
                            .height(42.dp)
                            .padding(start = 11.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Glyph.Folder, tint = Ink.GlassText, size = 17.dp)
                        Txt(project?.name ?: "未选项目", Type.body.copy(fontSize = 13.sp), Modifier.weight(1f, fill = false), color = Ink.GlassText)
                        Txt(
                            if (monitor.transfer >= 0f) "接收 ${(monitor.transfer * 100).toInt()}%" else if (monitor.received > 0) "收到 ${monitor.received} 张" else "等待原片",
                            Type.numSmall, color = Ink.GlassDim,
                        )
                    }
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End), reverseLayout = false) {
                        items(monitor.recentShots, key = { it.id }) { shot ->
                            Box(
                                Modifier.size(46.dp).border(2.dp, Ink.Paper)
                                    .clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(shot.id) }
                                    .semantics { contentDescription = "打开 ${shot.name}" },
                            ) { FileImage(shot.thumb, Modifier.fillMaxSize().padding(2.dp)) }
                        }
                    }
                }
                // ---- how the camera is set
                Row(Modifier.fillMaxWidth().glass(27.dp, shadow = false).height(54.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("感光度" to monitor.readings.iso, "光圈" to monitor.readings.aperture, "快门" to monitor.readings.shutter).forEachIndexed { index, (label, value) ->
                        if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.Line))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Txt(label, Type.micro.copy(lineHeight = 14.sp), color = Ink.GlassDim)
                            Txt(value.ifEmpty { "—" }, Type.num.copy(lineHeight = 17.sp), color = Ink.GlassText)
                        }
                    }
                }
                // ---- the film it is seen through, the release, the recorder
                Row(Modifier.fillMaxWidth().glass(36.dp).height(104.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onColour).padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Txt("胶片监看", Type.micro, color = Ink.GlassDim)
                        Txt(if (monitor.filmOn) filmName(monitor) else "关", Type.film.copy(fontSize = 13.sp), color = if (monitor.filmOn) Ink.Accent else Ink.GlassText)
                    }
                    Shutter(
                        "遥控快门", enabled = monitor.canRelease && monitor.transfer < 0f,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            monitor.release()
                        },
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Orb(if (monitor.recording) "停止录制监看画面" else "录制监看画面", {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            monitor.toggleRecording()
                        }) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .graphicsLayer { val s = if (monitor.recording) .8f else 1f; scaleX = s; scaleY = s }
                                    .clip(if (monitor.recording) RoundedCornerShape(5.dp) else CircleShape)
                                    .background(Ink.Rec),
                            )
                        }
                    }
                }
            }
        },
    )
}
