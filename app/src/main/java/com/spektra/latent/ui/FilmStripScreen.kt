package com.spektra.latent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.Journal
import com.spektra.latent.data.Frame
import com.spektra.latent.data.PrintFormat
import com.spektra.latent.data.Recipes
import com.spektra.latent.data.StripArt
import com.spektra.latent.data.StripPrinter
import com.spektra.latent.model.FilmStrip
import com.spektra.latent.model.Look
import com.spektra.latent.ui.library.SheetRow
import com.spektra.latent.model.StripFrame
import com.spektra.latent.model.StripLayout
import com.spektra.latent.model.StripMode
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

private data class StripProof(val revision: Int, val recipe: Look?, val image: android.graphics.Bitmap?)

@Composable
internal fun FilmStripScreen(
    strip: FilmStrip, frames: Map<Long, Frame>, recipes: Recipes,
    onChange: (FilmStrip) -> Unit, onBack: () -> Unit, onExport: (PrintFormat) -> Unit,
) {
    val context = LocalContext.current
    val shell = LocalShell.current
    val saved by recipes.all.collectAsStateWithLifecycle()
    val relevant = strip.frames.mapNotNull { frames[it.id] }.sortedBy { it.id }
    val proofs = remember { mutableStateMapOf<Long, StripProof>() }
    val recipe = strip.recipe
    val missing = relevant.size != strip.frames.size
    var cropId by remember { mutableStateOf<Long?>(null) }
    var order by remember { mutableStateOf(false) }
    var chooseRecipe by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(PrintFormat.JPEG) }
    val layout = remember(strip.mode, strip.perRow, strip.frames.size) { StripLayout(strip.mode, strip.frames.size, strip.perRow, 12) }
    val output = remember(layout) { StripLayout.output(strip) }
    val pending = relevant.count { proofs[it.id]?.let { p -> p.revision == it.revision && p.recipe == recipe } != true }
    val unavailable = relevant.count { proofs[it.id]?.let { p -> p.revision == it.revision && p.recipe == recipe && p.image == null } == true }

    LaunchedEffect(relevant, recipe) {
        proofs.keys.toSet().filter { id -> relevant.none { it.id == id } }.forEach { proofs.remove(it) }
        for (frame in relevant) {
            if (proofs[frame.id]?.let { it.revision == frame.revision && it.recipe == recipe } == true) continue
            val image = try { StripPrinter.proxy(context, frame, recipe) }
            catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { null }
            catch (e: Exception) { Journal.w("FilmStrip", "proxy unavailable: ${frame.id}", e); null }
            proofs[frame.id] = StripProof(frame.revision, recipe, image)
        }
    }

    fun move(id: Long, direction: Int) {
        val i = strip.frames.indexOfFirst { it.id == id }
        val j = i + direction
        if (i !in strip.frames.indices || j !in strip.frames.indices) return
        onChange(strip.copy(frames = strip.frames.toMutableList().apply { add(j, removeAt(i)) }))
    }
    val moveCurrent by rememberUpdatedState<(Long, Int) -> Unit>(::move)

    @Composable
    fun Settings() {
        Ctl("版式") {
            Chips(StripMode.entries.map { Opt(it.name, it.label) }, strip.mode.name,
                { onChange(strip.copy(mode = StripMode.valueOf(it))) }, wrap = true)
        }
        if (strip.mode == StripMode.Sleeve) {
            Ctl("每行格数", value = "${layout.columns} 格 · ${layout.rows} 行",
                note = "照片按当前片号从左到右排，排满后自动换行。") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn("−", { onChange(strip.copy(perRow = layout.columns - 1)) }, enabled = layout.columns > 1, size = BtnSize.Small)
                    Ruler("每行格数", layout.columns.toFloat(), 1f, strip.frames.size.toFloat(), 1f, minOf(5, strip.frames.size).toFloat(),
                        { onChange(strip.copy(perRow = it.roundToInt().coerceIn(1, strip.frames.size))) }, {}, Modifier.weight(1f))
                    Btn("+", { onChange(strip.copy(perRow = layout.columns + 1)) }, enabled = layout.columns < strip.frames.size, size = BtnSize.Small)
                }
            }
        }
        Ctl("整卷定调", value = if (recipe == null) "保留各格调色" else saved.firstOrNull { it.look == recipe }?.name ?: "共用配方") {
            Btn("选择配方", { chooseRecipe = true }, size = BtnSize.Small, icon = Glyph.Recipe)
        }
        Ctl("片序与裁切", note = "点预览中的任一格可单独裁切。片序调整不会改动原片或项目。") {
            Btn("整理 ${strip.frames.size} 格", { order = true }, size = BtnSize.Small, icon = Glyph.Film)
        }
    }

    @Composable
    fun Preview(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("卷名", { name = strip.name; naming = true }, size = BtnSize.Small, kind = BtnKind.Line)
                Txt(strip.name, Type.label, Modifier.weight(1f), color = Ink.GlassText, maxLines = 1)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Well)) {
                val ratio = layout.width.toFloat() / layout.height
                val width = minOf(maxWidth, maxHeight * ratio)
                val height = width / ratio
                val current by rememberUpdatedState(strip.frames)
                Canvas(Modifier.align(Alignment.Center).size(width, height)
                    .semantics { contentDescription = "${strip.mode.label}预览，${strip.frames.size} 格" }
                    .pointerInput(layout) {
                        detectTapGestures { p ->
                            val x = p.x * layout.width / size.width
                            val y = p.y * layout.height / size.height
                            layout.cells.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { cropId = current.getOrNull(it)?.id }
                        }
                    }) {
                    drawIntoCanvas { target ->
                        val native = target.nativeCanvas
                        val save = native.save()
                        native.clipRect(0f, 0f, size.width, size.height)
                        native.scale(size.width / layout.width, size.height / layout.height)
                        StripArt.draw(native, layout, strip) { i ->
                            val cell = strip.frames[i]
                            proofs[cell.id]?.image?.let { cell to it }
                        }
                        native.restoreToCount(save)
                    }
                }
            }
            val status = when {
                missing -> "有照片已移出片库；移除对应格后可导出。"
                pending > 0 -> "正在生成代理预览 ${relevant.size - pending}/${relevant.size}"
                unavailable > 0 -> "$unavailable 格代理预览暂不可用"
                else -> "${output.width} × ${output.height} px · 导出一张完整作品"
            }
            Txt(status, Type.micro, color = if (missing) Ink.Rec else Ink.GlassDim)
        }
    }

    GlassPage(behind = {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Bg)
            .padding(start = shell.insetLeft + leftInset(), end = rightInset())) {
            val wide = maxWidth > 640.dp
            val body = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = topInset(), bottom = bottomInset() + 12.dp)
            if (wide) {
                Row(body, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Preview(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    Column(Modifier.weight(.67f).verticalScroll(rememberScrollState())) { Settings() }
                }
            } else {
                Column(body.verticalScroll(rememberScrollState())) {
                    Preview(Modifier.fillMaxWidth())
                    Settings()
                }
            }
        }
    }, chrome = {
        TopBar(title = "整卷胶片", actions = {
            IconButton(Glyph.Close, "退出整卷编辑", onBack)
            Btn("导出", { exporting = true }, kind = BtnKind.Key, size = BtnSize.Bar, icon = Glyph.Share, enabled = !missing)
        })
    })

    Sheet(naming, { naming = false }, title = "卷名", foot = {
        Btn("确定", { onChange(strip.copy(name = name.trim())); naming = false }, Modifier.weight(1f), BtnKind.Key, enabled = name.isNotBlank())
    }) {
        TextEntry(name, { name = it }, "输入卷名", { if (name.isNotBlank()) { onChange(strip.copy(name = name.trim())); naming = false } })
    }
    Sheet(chooseRecipe, { chooseRecipe = false }, title = "整卷定调") {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            SheetRow(null, "保留各格调色", "每张照片沿用工作台的设置") {
                onChange(strip.copy(recipe = null)); chooseRecipe = false
            }
            frames[strip.frames.first().id]?.let { first ->
                SheetRow(Glyph.Film, "采用首格配方", "整卷沿用第 1 格的胶片与相纸风格") {
                    onChange(strip.copy(recipe = first.look.asRecipe())); chooseRecipe = false
                }
            }
            for (r in saved) SheetRow(Glyph.Recipe, r.name, "统一风格；保留各格曝光和白平衡") {
                onChange(strip.copy(recipe = r.look)); chooseRecipe = false
            }
        }
    }
    Sheet(order, { order = false }, title = "片序与裁切") {
        Txt("拖动左侧把手或用箭头换序。点照片调整裁切。", Type.micro, color = Ink.GlassDim)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(strip.frames, key = { _, cell -> cell.id }) { i, cell ->
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(width = 47.dp, height = 52.dp)
                        .semantics { contentDescription = "拖动第 ${i + 1} 格" }.pointerInput(cell.id) {
                            var distance = 0f
                            detectVerticalDragGestures(onDragStart = { distance = 0f }) { change, delta ->
                                change.consume()
                                distance += delta
                                val step = 48.dp.toPx()
                                if (abs(distance) >= step) {
                                    val direction = if (distance < 0) -1 else 1
                                    moveCurrent(cell.id, direction)
                                    distance -= step * direction
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                        Txt("☰ ${i + 1}", Type.numSmall, color = Ink.Accent)
                    }
                    Box(Modifier.size(45.dp).clip(RoundedCornerShape(5.dp)).background(Ink.Well)
                        .clickable { if (frames[cell.id] != null) { order = false; cropId = cell.id } }) {
                        frames[cell.id]?.let { FileImage(it.thumb, Modifier.fillMaxSize()) }
                    }
                    Txt(frames[cell.id]?.name ?: "原片已移除", Type.micro, Modifier.weight(1f)
                        .clickable { if (frames[cell.id] != null) { order = false; cropId = cell.id } }, maxLines = 1)
                    IconButton(Glyph.Chevron, "上移第 ${i + 1} 格", { move(cell.id, -1) }, modifier = Modifier.rotate(-90f), enabled = i > 0)
                    IconButton(Glyph.Chevron, "下移第 ${i + 1} 格", { move(cell.id, 1) }, modifier = Modifier.rotate(90f), enabled = i < strip.frames.lastIndex)
                    IconButton(Glyph.Close, "移出第 ${i + 1} 格", {
                        onChange(strip.copy(frames = strip.frames.filterNot { it.id == cell.id }))
                    }, enabled = strip.frames.size > 2)
                }
            }
        }
    }
    val cell = strip.frames.firstOrNull { it.id == cropId }
    if (cell != null) {
        fun crop(next: StripFrame) = onChange(strip.copy(frames = strip.frames.map { if (it.id == next.id) next else it }))
        Sheet(true, { cropId = null }, title = "第 ${strip.frames.indexOf(cell) + 1} 格 · 裁切", foot = {
            Btn("重置", { crop(StripFrame(cell.id)) }, Modifier.weight(1f))
            Btn("完成", { cropId = null }, Modifier.weight(1f), BtnKind.Key)
        }) {
            val image = proofs[cell.id]?.image
            if (image != null) Canvas(Modifier.align(Alignment.CenterHorizontally).height(128.dp)
                .aspectRatio(if (layout.vertical) 2f / 3 else 3f / 2)) {
                val src = cell.crop(image.width, image.height, layout.vertical)
                drawIntoCanvas {
                    it.nativeCanvas.drawBitmap(image, android.graphics.Rect(src.x, src.y, src.right, src.bottom),
                        android.graphics.RectF(0f, 0f, size.width, size.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                }
            }
            Ruler("缩放", cell.zoom, 1f, 3f, .05f, 1f, { crop(cell.copy(zoom = it)) }, {})
            Ruler("水平位置", cell.x, -1f, 1f, .05f, 0f, { crop(cell.copy(x = it)) }, {})
            Ruler("垂直位置", cell.y, -1f, 1f, .05f, 0f, { crop(cell.copy(y = it)) }, {})
        }
    }
    Sheet(exporting, { exporting = false }, title = "导出 ${strip.mode.label}", foot = {
        Btn("加入冲印队列", { exporting = false; onExport(format) }, Modifier.weight(1f), BtnKind.Key, enabled = !missing)
    }) {
        Txt("${strip.frames.size} 格 · ${output.width} × ${output.height} px。逐张读取原片渲染，保存为一张图片。", Type.body)
        Gap(height = 10.dp)
        Chips(listOf(Opt("JPEG", "JPEG"), Opt("PNG", "PNG · 16 位")), format.name, { format = PrintFormat.valueOf(it) })
    }
}
