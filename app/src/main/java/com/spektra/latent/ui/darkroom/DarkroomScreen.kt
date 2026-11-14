package com.spektra.latent.ui.darkroom

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.APP_NAME
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.PrintFormat
import com.spektra.latent.data.PrintJob
import com.spektra.latent.data.Recipes
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Format
import com.spektra.latent.model.Look
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnKind
import com.spektra.latent.ui.BtnSize
import com.spektra.latent.ui.Ctl
import com.spektra.latent.ui.CtlColumn
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.GAP
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.GlassPage
import com.spektra.latent.ui.GlassTone
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.GlyphVeil
import com.spektra.latent.ui.Hairline
import com.spektra.latent.ui.Icon
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Lamp
import com.spektra.latent.ui.LampState
import com.spektra.latent.ui.LocalOverlays
import com.spektra.latent.ui.LocalReducedMotion
import com.spektra.latent.ui.LocalShell
import com.spektra.latent.ui.Motion
import com.spektra.latent.ui.Opt
import com.spektra.latent.ui.PrintFormatPicker
import com.spektra.latent.ui.Seg
import com.spektra.latent.ui.SegStyle
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.TextEntry
import com.spektra.latent.ui.TopBar
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.ambientOf
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.bottomInset
import com.spektra.latent.ui.glass
import com.spektra.latent.ui.leftInset
import com.spektra.latent.ui.rightInset
import com.spektra.latent.ui.shortName
import com.spektra.latent.ui.softShadow
import com.spektra.latent.ui.topInset
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The places a look can be changed, from scene corrections to the film and enlarger. */
private enum class Step(val title: String) { Tone("调节"), Film("胶片"), Texture("质感"), Print("放大机"), Edge("片边") }

/**
 * The darkroom: what the lab's workbench shows once there is a photograph on
 * it. The photograph stands in the upper part of the screen with the
 * histogram over its foot; under it a strip of the photographs on the bench,
 * and a tray of glass holding the five steps of the process, each with its
 * settings as rows.
 *
 * Hold the photograph to see it as it came, without the film; double-tap or
 * pinch to look closer. Printing plays the six steps of the print desk over
 * the photograph, and the strip under it becomes one progress bar.
 *
 * @param frames the photographs on the bench, newest first
 * @param fresh this photograph has only just arrived: it develops into view
 * @param tabs the lab's tabs, for the top capsule
 */
@Composable
fun Darkroom(
    library: Library,
    recipes: Recipes,
    prints: PrintDesk,
    frame: Frame,
    frames: List<Frame>,
    fresh: Boolean,
    onArrived: () -> Unit,
    onFrame: (Long) -> Unit,
    onImport: () -> Unit,
    onRemove: (Long) -> Unit,
    tabs: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shell = LocalShell.current
    val overlays = LocalOverlays.current
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val prefs = remember { context.getSharedPreferences("appearance", 0) }

    var step by remember {
        mutableStateOf(when (val stored = prefs.getString("step", "Film")) {
            "Date" -> Step.Edge
            "Finish" -> Step.Tone
            else -> runCatching { Step.valueOf(stored ?: "Film") }.getOrDefault(Step.Film)
        })
    }
    val state = remember(frame.id) { DarkroomState(context, library, frame, scope, initialAdjustmentsOnly = step == Step.Tone) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    var collapsed by remember { mutableStateOf(false) }
    var histogram by remember { mutableStateOf(prefs.getBoolean("histogram", true)) }
    var ordering by remember { mutableStateOf(false) }
    var keeping by remember { mutableStateOf(false) }
    val saved by recipes.all.collectAsStateWithLifecycle()
    BackHandler(enabled = collapsed) { collapsed = false }

    // ---- a print of this photograph, and the run that shows it being made
    var job by remember(frame.id) { mutableStateOf<PrintJob?>(null) }
    val run = rememberPrintRun(job)
    val printing = job != null && run != null && !run.over
    LaunchedEffect(state, step, printing) { state.showAdjustmentsOnly(step == Step.Tone && !printing) }
    LaunchedEffect(run?.over) {
        val done = job
        if (run != null && run.over && done != null) {
            if (run.failed == null) {
                val printed = (done.state as? PrintJob.State.Done)?.printed
                if (printed?.inGallery != false) overlays?.toast("已存入相册", "${done.longEdge} px")
                else overlays?.toast("已存入应用目录", "这台手机的相册没有收下它，从片库可以分享 · ${done.longEdge} px")
            }
            job = null
        }
    }
    // a photograph that has just arrived comes out of the characters
    val veil = remember(frame.id) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(frame.id, fresh) {
        if (fresh) {
            if (!reduced) veil.animateTo(1f, tween(900, easing = LinearEasing)) else veil.snapTo(1f)
            onArrived()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = topInset()
        val safe = bottomInset()
        val safeLeft = leftInset()
        val safeRight = rightInset()
        val contentWidth = (maxWidth - shell.insetLeft - safeLeft - safeRight).coerceAtLeast(0.dp)
        val wide = contentWidth >= 840.dp || maxWidth > maxHeight
        val trayWidth = (contentWidth * .38f).coerceIn(minOf(240.dp, contentWidth * .45f), 396.dp)
        val trayHeight by animateDpAsState(
            if (collapsed) 84.dp else min(maxHeight.value * .43f, 396f).dp,
            tween(if (reduced) 0 else 500, easing = Motion.Ease), label = "tray",
        )
        // what is left for the photograph and the strip under it
        val stageBottom = if (wide) safe else trayHeight + 14.dp + safe
        val stageRight = if (wide && !collapsed) trayWidth + 20.dp else 0.dp
        val side = if (wide) 12.dp else 16.dp
        val footBottom = if (wide) 14.dp else 2.dp
        val stageTop = if (collapsed) WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp else top + 2.dp
        val stageFoot = if (collapsed) 8.dp else footBottom + 58.dp
        val proof = state.proof

        GlassPage(
            behind = {
                Box(Modifier.fillMaxSize().background(Ink.Bg))
                // the photograph's own light, spilled under the tray so that the glass has something to hold
                Ambient(
                    proof,
                    if (wide) Modifier.align(Alignment.CenterEnd).width(trayWidth + 120.dp).fillMaxHeight()
                    else Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(trayHeight + 90.dp + safe),
                    sideways = wide,
                )
                PhotoStage(
                    state, frame, histogram, printing, run, veil.value, collapsed,
                    onExpand = { collapsed = !collapsed },
                    onRemove = {
                        state.commit()
                        overlays?.toast("已从工作台移除，原图和项目均已保留")
                        onRemove(frame.id)
                    },
                    modifier = Modifier
                        .padding(
                            start = shell.insetLeft + safeLeft + side, end = safeRight + stageRight + side,
                            top = stageTop, bottom = stageBottom + stageFoot,
                        )
                        .fillMaxSize(),
                )
            },
            chrome = {
                state.failure?.let {
                    Txt(it, Type.body, Modifier.align(Alignment.Center).padding(32.dp), color = Ink.Dim, maxLines = 4)
                }
                if (!collapsed) TopBar(
                    tabs = tabs,
                    actions = {
                        IconButton(Glyph.Histogram, if (histogram) "隐藏直方图" else "显示直方图", {
                            histogram = !histogram
                            prefs.edit().putBoolean("histogram", histogram).apply()
                        }, on = histogram)
                        Btn("冲印", { ordering = true }, Modifier.padding(horizontal = 2.dp), BtnKind.Key, BtnSize.Bar, enabled = state.ready && !printing)
                    },
                )

                // ---- under the photograph: the bench, and what this photograph is; while a print runs, its progress
                if (!collapsed) Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = shell.insetLeft + safeLeft + side, end = safeRight + stageRight + side, bottom = stageBottom + footBottom)
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    val bar by animateFloatAsState(if (printing) 1f else 0f, tween(if (reduced) 0 else 300, easing = Motion.Ease), label = "bar")
                    if (bar < 1f) Foot(state, frame, frames, onFrame, onImport, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - bar })
                    if (bar > 0f && run != null) PrintBar(run, Modifier.fillMaxSize().graphicsLayer { alpha = bar })
                }

                // ---- the tray: the steps, and the settings of the step that is open
                val body = remember(step) { ScrollState(0) }
                val rail = rememberScrollState()
                if (!wide || !collapsed) Box(
                    (if (wide) Modifier.align(Alignment.TopEnd).padding(top = top, end = GAP + safeRight, bottom = GAP + safe).width(trayWidth).fillMaxHeight()
                    else Modifier.align(Alignment.BottomCenter).padding(start = 8.dp + safeLeft, end = 8.dp + safeRight, bottom = 8.dp + safe).fillMaxWidth().height(trayHeight))
                        .graphicsLayer { alpha = if (printing) .5f else 1f }
                        .glass(28.dp, GlassTone.Strong)
                        .pointerInput(Unit) { },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (!wide) Grab(collapsed) { collapsed = !collapsed }
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, bottom = 10.dp, top = if (wide) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                            var viewport by remember { mutableStateOf(0) }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    // the row of steps fades out at the right: there are more than fit
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        viewport = size.width.toInt()
                                        drawContent()
                                        drawRect(
                                            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - 26.dp.toPx(), endX = size.width),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                    .horizontalScroll(rail),
                            ) {
                                Seg(
                                    Step.entries.map { Opt(it.name, it.title) }, step.name,
                                    {
                                        step = Step.valueOf(it)
                                        collapsed = false
                                        prefs.edit().putString("step", it).apply()
                                    },
                                    style = SegStyle.Steps,
                                    // keep the chosen step in view inside its rail
                                    onChosenAt = { chosen -> scope.launch { rail.animateScrollTo((chosen.center.x - viewport / 2f).roundToInt().coerceAtLeast(0)) } },
                                )
                            }
                            IconButton(Glyph.Undo, "撤销", state::undo, enabled = state.canUndo, size = 40.dp)
                            IconButton(Glyph.Redo, "重做", state::redo, enabled = state.canRedo, size = 40.dp)
                        }
                        Hairline()
                        Column(Modifier.weight(1f).verticalScroll(body).padding(start = TRAY_PAD, end = TRAY_PAD, top = 2.dp, bottom = 20.dp)) {
                            CtlColumn(Modifier.fillMaxWidth()) {
                                when (step) {
                                    Step.Tone -> TonePanel(state)
                                    Step.Film -> FilmPanel(state, frame, saved, onKeep = { keeping = true })
                                    Step.Texture -> TexturePanel(state)
                                    Step.Print -> PrintPanel(state, frame)
                                    Step.Edge -> EdgePanel(state, state.shooting)
                                }
                            }
                        }
                    }
                    // while a print runs the settings are not to be touched
                    if (printing) Box(Modifier.fillMaxSize().pointerInput(Unit) { })
                }
            },
        )
    }

    // ---- ordering a print
    val native = state.look.frameSize(frame.width, frame.height).max()
    val requested = prefs.getInt("exportEdge", 0)
    val wanted = if (requested <= 0) native else requested.coerceAtMost(native)
    val sizes = remember(native, wanted) { (listOf(2048, 3072, 4096).filter { it < native } + wanted + native).distinct().sorted() }
    var edge by remember(frame.id, wanted) { mutableStateOf(wanted) }
    var format by remember { mutableStateOf(PrintFormat.AUTO) }
    Sheet(
        ordering, { ordering = false }, title = "冲印",
        foot = {
            Btn("冲印这一张", {
                ordering = false
                state.commit()
                state.compare(false)
                state.fit()
                library.frame(frame.id)?.let { job = prints.order(it, state.look, edge, format) }
            }, Modifier.weight(1f), BtnKind.Key)
        },
    ) {
        CtlColumn(Modifier.fillMaxWidth()) {
            Ctl(
                "长边", value = "像素",
                note = when {
                    state.look.edge.on && state.look.edge.open -> "片门全开，成片就是画面本身的大小。"
                    state.look.edge.on -> "尺寸指画面本身；开着片边，成片会比它大出一圈胶片。"
                    else -> "冲印在后台进行，离开这里也会做完；成片存进相册的 ${APP_NAME} 相簿。"
                },
            ) {
                Seg(sizes.map { Opt(it.toString(), if (it == native) "原尺寸" else it.toString()) }, edge.toString(), { edge = it.toInt() }, Modifier.fillMaxWidth(), block = true)
            }
            Ctl("文件") { PrintFormatPicker(format) { format = it } }
        }
    }

    // ---- recipes: this look kept under a name; a kept one is put on from the row of chips
    var name by remember { mutableStateOf("") }
    Sheet(keeping, { keeping = false }, title = "配方") {
        Txt(
            "配方记下胶片、质感、放大机、片边、日期以及曲线等风格设置，可以用在别的照片、相机和监视器上。曝光、白平衡等照片校正单独保留。",
            Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 4,
        )
        Gap(height = 14.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val keep = {
                val label = name.trim()
                if (label.isNotEmpty()) {
                    name = ""
                    state.commit()
                    val look = state.look
                    scope.launch { recipes.save(label, look) }
                    overlays?.toast("已存为配方", label)
                }
            }
            TextEntry(name, { name = it }, "给现在的设置起个名字", keep, Modifier.weight(1f))
            Gap(width = 10.dp)
            Btn("保存", keep, kind = BtnKind.Key, enabled = name.isNotBlank())
        }
        Gap(height = 10.dp)
        if (saved.isEmpty()) Txt("还没有配方。", Type.label, Modifier.padding(vertical = 14.dp), color = Ink.Faint)
        CtlColumn(Modifier.fillMaxWidth()) {
            for (r in saved) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {
                                keeping = false
                                state.change(state.look.withRecipe(r.look))
                                state.commit()
                                overlays?.toast("已套用配方", r.name)
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Txt(r.name, Type.body, color = Ink.GlassText)
                        Txt(recipeLine(r.look), Type.numSmall, color = Ink.GlassDim)
                    }
                    IconButton(Glyph.Trash, "删除配方 ${r.name}", { scope.launch { recipes.remove(r.id) } }, tint = Ink.GlassDim)
                }
            }
        }
    }
}

/** A recipe in one line of edge lettering: its film, and what else it carries. */
internal fun recipeLine(look: Look): String = listOfNotNull(
    shortName(FilmEngine.catalog.film(look.film)?.name ?: look.film),
    Format.of(look.format).label.takeIf { look.format != "135" },
    "片边".takeIf { look.edge.on },
    "日期".takeIf { look.date.on },
).joinToString("  ·  ")

/** The grip at the top of the tray: folds it away to leave more of the photograph, and brings it back. */
@Composable
private fun Grab(collapsed: Boolean, onClick: () -> Unit) {
    val line = Ink.Line2
    Box(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = if (collapsed) "展开面板" else "收起面板" }
            .drawBehind {
                val w = 38.dp.toPx()
                drawRoundRect(line, Offset(center.x - w / 2, center.y - 2.dp.toPx()), Size(w, 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            },
    )
}

/** Under the photograph: the photographs on the bench (the one being worked on is lit), a way to add one, and what this one is. */
@Composable
private fun Foot(state: DarkroomState, frame: Frame, frames: List<Frame>, onFrame: (Long) -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val accent = Ink.Accent
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            items(frames, key = { it.id }) { f ->
                val on = f.id == frame.id
                Box(
                    Modifier
                        .padding(vertical = 5.dp)
                        .size(42.dp)
                        .graphicsLayer { alpha = if (on) 1f else .5f }
                        .drawBehind { if (on) drawRect(accent, Offset(0f, size.height + 3.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { if (!on) onFrame(f.id) }
                        .semantics { contentDescription = f.name },
                ) {
                    FileImage(f.thumb, Modifier.fillMaxSize())
                }
            }
            item(key = "add") {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).border(1.dp, Ink.Line2, CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onImport)
                        .semantics { contentDescription = "再导入" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyph.Plus, tint = Ink.Dim, size = 18.dp)
                }
            }
        }
        Column(Modifier.width(132.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(when {
                state.comparing -> "原图"
                state.adjustmentsOnly -> "原图调节"
                else -> shortName(FilmEngine.catalog.film(state.look.film)?.name ?: state.look.film)
            }, Type.film)
            Txt(if (state.adjustmentsOnly) frame.name else "${Format.of(state.look.format).label} · ${frame.name}", Type.numMicro, align = androidx.compose.ui.text.style.TextAlign.End)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Lamp(if (state.proofing) LampState.On else LampState.Off)
                Txt(when {
                    state.proofing -> if (state.adjustmentsOnly) "正在更新调节" else "正在出样张"
                    state.proof != null -> if (state.adjustmentsOnly) "未应用胶片模拟" else "样张 · 细节以成片为准"
                    else -> "正在读取原图"
                }, Type.micro.copy(lineHeight = 14.sp), color = Ink.Faint)
            }
        }
    }
}

/**
 * The photograph, small and out of focus, under the tray. It stays below the
 * frame: the surround of the photograph itself is left neutral.
 */
@Composable
private fun Ambient(proof: ImageBitmap?, modifier: Modifier, sideways: Boolean) {
    val small = remember(proof) { proof?.let { ambientOf(it.asAndroidBitmap(), 24) } }
    val light = Ink.light
    val richer = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.5f) }) }
    val shape = modifier
        .graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            alpha = if (light) .5f else .62f
        }
        .drawWithContent {
            drawContent()
            drawRect(
                if (sideways) Brush.horizontalGradient(0f to Color.Transparent, .46f to Color.Black)
                else Brush.verticalGradient(0f to Color.Transparent, .46f to Color.Black),
                blendMode = BlendMode.DstIn,
            )
        }
    if (small != null) Image(small, null, shape, contentScale = ContentScale.Crop, alignment = Alignment.BottomCenter, colorFilter = richer, filterQuality = FilterQuality.High)
}
