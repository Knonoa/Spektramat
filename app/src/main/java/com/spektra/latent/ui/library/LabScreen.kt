package com.spektra.latent.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.APP_NAME
import com.spektra.latent.LatentApp
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.data.PrintDesk
import com.spektra.latent.data.PrintFormat
import com.spektra.latent.data.Recipes
import com.spektra.latent.data.Roll
import com.spektra.latent.tether.Monitor
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
import com.spektra.latent.ui.GlyphField
import com.spektra.latent.ui.GlyphSubject
import com.spektra.latent.ui.Icon
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Lamp
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
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.bottomInset
import com.spektra.latent.ui.darkroom.Darkroom
import com.spektra.latent.ui.darkroom.recipeLine
import com.spektra.latent.ui.glass
import com.spektra.latent.ui.leftInset
import com.spektra.latent.ui.rightInset
import com.spektra.latent.ui.topInset
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val TABS = listOf(Opt("0", "工作台"), Opt("1", "项目"), Opt("2", "全部"))

/**
 * The lab. Three ways in, one place to work:
 *
 *     工作台  import a photograph and work on it (the darkroom lives here)
 *     项目    photographs gathered by project -> its photographs -> onto the workbench
 *     全部    the phone's album, read directly -> onto the workbench
 *
 * @param frames photographs currently on the workbench, newest first
 * @param bench the photograph on the workbench, if there is one
 * @param fresh that photograph has only just been put there
 * @param onBench puts a photograph on the workbench and brings the workbench forward; true when it has just been imported
 */
@Composable
fun LabScreen(
    library: Library,
    recipes: Recipes,
    prints: PrintDesk,
    monitor: Monitor,
    frames: List<Frame>,
    tab: Int,
    onTab: (Int) -> Unit,
    bench: Frame?,
    fresh: Boolean,
    onArrived: () -> Unit,
    onBench: (Long, Boolean) -> Unit,
    onRemoveBench: (Long) -> Unit,
    project: Long?,
    onProject: (Long?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as LatentApp
    val importing by library.importing.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf(false) }

    // an import belongs to the app, not to this screen: it goes on if the person walks away
    fun import(uris: List<Uri>, into: Long? = null) {
        if (uris.isNotEmpty()) app.scope.launch {
            val report = library.import(uris, into)
            if (into == null) report.added.firstOrNull()?.let { onBench(it, true) }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { import(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it) }
    fun pickPhotos() = photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    // RAW files rarely carry an image MIME type, so the file picker must not filter on one
    fun pickFiles() = files.launch(arrayOf("*/*"))

    val usableWidth = LocalConfiguration.current.screenWidthDp.dp - LocalShell.current.insetLeft - leftInset() - rightInset()
    val narrow = usableWidth < 390.dp
    val tabs: @Composable () -> Unit = {
        Seg(TABS, tab.toString(), { onTab(it.toInt()) }, style = if (narrow) SegStyle.TabsNarrow else SegStyle.Tabs, bare = true)
    }
    val reduced = LocalReducedMotion.current
    val view = when (tab) {
        0 -> if (bench == null) "empty" else "darkroom"
        1 -> if (project == null) "projects" else "project"
        else -> "album"
    }
    AnimatedContent(
        view,
        transitionSpec = { fadeIn(tween(if (reduced) 0 else 300, easing = Motion.Ease)) togetherWith fadeOut(tween(if (reduced) 0 else 150)) },
        label = "lab",
    ) { shown ->
        when (shown) {
            "darkroom" -> if (bench != null) Darkroom(
                library, recipes, prints, bench, frames, fresh, onArrived,
                onFrame = { onBench(it, false) }, onImport = { source = true }, onRemove = onRemoveBench, tabs = tabs,
            )
            "empty" -> BenchEmpty(importing != null, ::pickPhotos, ::pickFiles, tabs)
            "projects" -> Projects(library, monitor, onProject, tabs)
            "project" -> ProjectPhotos(library, recipes, prints, project, { onProject(null) }, { onBench(it, false) }, tabs)
            else -> AlbumBrowser(library, { onBench(it, true) }, tabs)
        }
    }
    importing?.let { (done, total) -> Importing(done, total) }

    Sheet(source, { source = false }, title = "导入") {
        Txt("原文件会被复制进来，之后的调整都不会改动它。", Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 2)
        Gap(height = 8.dp)
        CtlColumn(Modifier.fillMaxWidth()) {
            SheetRow(Glyph.Album, "从相册选照片", "JPEG、HEIF、PNG") { source = false; pickPhotos() }
            SheetRow(Glyph.File, "从文件选 RAW", "DNG、ARW、CR2 / CR3、NEF、RAF、RW2、ORF …") { source = false; pickFiles() }
        }
    }
}

@Composable
internal fun SheetRow(icon: Glyph?, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, tint = Ink.GlassDim)
            Gap(width = 14.dp)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, Type.body, color = Ink.GlassText)
            if (detail.isNotEmpty()) Txt(detail, Type.micro, color = Ink.GlassDim, maxLines = 2)
        }
        Icon(Glyph.Chevron, tint = Ink.Faint, size = 18.dp)
    }
}

/** Something is being read in: said under the top capsules, wherever in the lab the person is. */
@Composable
private fun Importing(done: Int, total: Int) {
    val shell = LocalShell.current
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = topInset() - 2.dp, start = shell.insetLeft + leftInset(), end = rightInset())
                .clip(CircleShape).background(Ink.Bg2.copy(alpha = .86f)).border(1.dp, Ink.Line, CircleShape)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Lamp()
            Txt("正在读取原片", Type.label, color = Ink.Dim)
            Txt("${done + 1} / $total", Type.num.copy(fontSize = 12.5.sp), color = Ink.Accent)
        }
    }
}

// ------------------------------------------------------------------ 工作台 · waiting for a photograph

@Composable
private fun BenchEmpty(busy: Boolean, onPhotos: () -> Unit, onFiles: () -> Unit, tabs: @Composable () -> Unit) {
    val shell = LocalShell.current
    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            // an empty strip of film, slowly running past
            GlyphField(GlyphSubject.Film(.3f, .36f), Modifier.fillMaxSize(), cell = 8.dp, noise = .13f)
        },
        chrome = {
            TopBar(tabs = tabs)
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(start = 24.dp + shell.insetLeft + leftInset(), end = 24.dp + rightInset(), top = topInset(), bottom = 16.dp + bottomInset()),
            ) {
                val compact = maxHeight < 360.dp
                Column(
                    Modifier.align(Alignment.BottomStart).verticalScroll(rememberScrollState()).padding(bottom = if (compact) 0.dp else maxHeight * .09f),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    Txt("把底片放上来", if (compact) Type.h1 else Type.display)
                    Txt(
                        if (busy) "正在读取原片…" else "RAW、DNG、JPEG、HEIF 都可以，导入后直接开始冲印。",
                        Type.body.copy(lineHeight = 24.5.sp), Modifier.widthIn(max = 336.dp), color = Ink.Dim, maxLines = 3,
                    )
                    Row(
                        Modifier.padding(top = 10.dp).glass(50.dp, shadow = false).padding(7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Btn("从相册导入", onPhotos, kind = BtnKind.Key, icon = Glyph.Album, enabled = !busy)
                        Btn("文件", onFiles, icon = Glyph.File, enabled = !busy)
                    }
                }
            }
        },
    )
}

// ------------------------------------------------------------------ 项目

private fun day(time: Long) = SimpleDateFormat("MM.dd", Locale.US).format(Date(time))

@Composable
private fun Projects(library: Library, monitor: Monitor, onOpen: (Long) -> Unit, tabs: @Composable () -> Unit) {
    val rolls by library.rolls.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as LatentApp
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlays.current
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf<Long?>(null) }
    var confirming by remember { mutableStateOf<Long?>(null) }
    var removing by remember { mutableStateOf<Long?>(null) }
    val managed = rolls.firstOrNull { it.id == managing }
    val confirmed = rolls.firstOrNull { it.id == confirming }

    fun remove(roll: Roll, deleteFiles: Boolean) {
        if (removing != null) return
        managing = null
        confirming = null
        removing = roll.id
        // File operations finish even if the person switches pages while deletion is running.
        app.scope.launch {
            try {
                val result = library.removeProject(roll.id, deleteFiles)
                when {
                    result.failedFiles.isNotEmpty() -> overlays?.toast(
                        "部分照片未能删除", "${result.failedFiles.size} 张仍保留在项目中，可稍后重试",
                    )
                    result.projectRemoved -> overlays?.toast(
                        if (deleteFiles) "已从硬盘中删除项目" else "已从 App 中删除项目", roll.name,
                    )
                }
            } catch (_: Exception) {
                overlays?.toast("未能删除项目", "请稍后重试")
            } finally {
                removing = null
            }
        }
    }
    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            LazyVerticalGrid(
                GridCells.Fixed(2), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp + shell.insetLeft + leftInset(), end = 16.dp + rightInset(), top = topInset(), bottom = 28.dp + bottomInset()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(rolls, key = { it.id }) { roll ->
                    val live = monitor.connected && monitor.projectId == roll.id
                    ProjectCard(roll, live, onOpen = { onOpen(roll.id) }, onHold = {
                        if (removing == null) managing = roll.id
                    })
                }
                item(key = "new", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.height(48.dp).clickable(remember { MutableInteractionSource() }, indication = null) { creating = true },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Glyph.Plus, tint = Ink.Dim, size = 18.dp)
                        Txt("新建项目", Type.body, color = Ink.Dim)
                    }
                }
            }
        },
        chrome = { TopBar(tabs = tabs) },
    )
    NewProject(creating, { creating = false }, name, { name = it }) {
        val label = name.trim()
        scope.launch {
            val id = library.createProject(label)
            name = ""
            creating = false
            overlays?.toast("已新建项目", label)
            onOpen(id)
        }
    }
    Sheet(managed != null, { managing = null }, title = managed?.name ?: "项目") {
        Txt("${managed?.frames?.size ?: 0} 张照片", Type.micro, color = Ink.GlassDim)
        Gap(height = 8.dp)
        CtlColumn(Modifier.fillMaxWidth()) {
            SheetRow(Glyph.Close, "从 App 中删除", "移除项目及照片入口，保留照片文件") {
                managed?.let { remove(it, false) }
            }
            SheetRow(Glyph.Trash, "从硬盘中删除", "同时删除 App 保存的照片副本，需要再次确认") {
                confirming = managing
                managing = null
            }
        }
    }
    Sheet(
        confirmed != null, { confirming = null }, title = "从硬盘中删除项目？",
        foot = {
            Btn("取消", { confirming = null }, Modifier.weight(1f))
            Btn("确认删除", { confirmed?.let { remove(it, true) } }, Modifier.weight(1f), BtnKind.Danger)
        },
    ) {
        Txt(confirmed?.name.orEmpty(), Type.h1, color = Ink.GlassText, maxLines = 2)
        Gap(height = 10.dp)
        Txt(
            "将删除项目内 ${confirmed?.frames?.size ?: 0} 张照片在 App 中的原片副本、缩略图及调整，无法撤销。相册原文件和已导出的成片会保留。",
            Type.body.copy(lineHeight = 24.sp), color = Ink.GlassDim, maxLines = 6,
        )
    }
}

/** A project is a small stack of its first photographs, with the first one on top. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(roll: Roll, live: Boolean, onOpen: () -> Unit, onHold: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.Card.copy(alpha = .72f))
            .border(1.dp, Ink.Line, shape)
            .combinedClickable(
                remember { MutableInteractionSource() }, indication = null,
                onClick = onOpen, onLongClick = onHold, onLongClickLabel = "删除项目",
            )
            .padding(10.dp),
    ) {
        Box {
            ProjectCover(roll.frames.take(5))
            // Live archiving stays visible without squeezing a narrow card's title.
            if (live) Row(
                Modifier.align(Alignment.TopEnd)
                    .clip(CircleShape).background(Ink.Bg2.copy(alpha = .92f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Lamp()
                Txt("联机归档中", Type.micro, color = Ink.Accent)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Txt(roll.name, Type.body.copy(fontSize = 15.sp, letterSpacing = .02.em), Modifier.weight(1f))
            Icon(Glyph.Chevron, tint = Ink.Faint, size = 16.dp)
        }
        Txt(
            "${roll.frames.size} 张" + (roll.frames.maxOfOrNull { it.taken }?.let { " · ${day(it)}" } ?: ""),
            Type.numSmall, Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
        )
    }
}

/** Layer the actual thumbnails inside a bounded cover; no photo extends into its neighbour. */
@Composable
private fun ProjectCover(first: List<Frame>) {
    val availableHeight = LocalConfiguration.current.screenHeightDp.dp - topInset() - bottomInset()
    val maxCoverHeight = (availableHeight * .65f).coerceIn(112.dp, 260.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverWidth = maxWidth
        val coverHeight = (maxWidth * .86f).coerceIn(112.dp, maxCoverHeight)
        val photoHeight = coverHeight * .76f
        val photoWidth = minOf(maxWidth * .80f, photoHeight * 1.25f)
        val photoShape = RoundedCornerShape(5.dp)
        Box(Modifier.fillMaxWidth().height(coverHeight).clip(RoundedCornerShape(10.dp))) {
            if (first.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).size(photoWidth, photoHeight)
                        .clip(photoShape).background(Ink.Bg3).border(1.dp, Ink.Line, photoShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Icon(Glyph.Lab, tint = Ink.Faint, size = 26.dp)
                    Txt("暂无照片", Type.micro, color = Ink.Faint)
                }
            }
            // Draw the back of the stack first so every supplied thumbnail has a visible edge.
            for (index in first.indices.reversed()) {
                val turn = when (index) { 1 -> 3f; 2 -> -3f; 3 -> 5f; 4 -> -5f; else -> 0f }
                val shift = when (index) { 1 -> .025f; 2 -> -.025f; 3 -> .04f; 4 -> -.04f; else -> 0f }
                Box(
                    Modifier.align(Alignment.Center)
                        .offset(x = coverWidth * shift, y = coverHeight * (.05f - index * .025f))
                        .size(photoWidth, photoHeight)
                        .graphicsLayer { rotationZ = turn }
                        .shadow(3.dp, photoShape)
                        .clip(photoShape).background(Ink.Paper).padding(3.dp),
                ) {
                    FileImage(first[index].thumb, Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
internal fun NewProject(visible: Boolean, onDismiss: () -> Unit, name: String, onName: (String) -> Unit, onCreate: () -> Unit) {
    Sheet(
        visible, onDismiss, title = "新建项目",
        foot = { Btn("创建", onCreate, Modifier.weight(1f), BtnKind.Key, enabled = name.isNotBlank()) },
    ) {
        Txt("项目把一组照片放在一起，也是联机拍摄时原片归档的地方。", Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 2)
        Gap(height = 12.dp)
        TextEntry(name, onName, "项目名称", { if (name.isNotBlank()) onCreate() })
    }
}

/** The photographs of one project. Tap one to work on it; hold one, or use the capsule at the top right, to choose several. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectPhotos(
    library: Library, recipes: Recipes, prints: PrintDesk, projectId: Long?,
    onBack: () -> Unit, onOpen: (Long) -> Unit, tabs: @Composable () -> Unit,
) {
    val rolls by library.rolls.collectAsStateWithLifecycle()
    val roll = rolls.firstOrNull { it.id == projectId }
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlays.current
    val saved by recipes.all.collectAsStateWithLifecycle()
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var grouping by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun leave() {
        selecting = false
        selection = emptySet()
    }
    fun chosen() = roll?.frames.orEmpty().filter { it.id in selection }
    BackHandler { if (selecting) leave() else onBack() }

    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            val frames = roll?.frames.orEmpty().sortedByDescending { it.taken }
            LazyVerticalGrid(
                GridCells.Adaptive(108.dp), Modifier.fillMaxSize().padding(start = shell.insetLeft + leftInset(), end = rightInset()),
                contentPadding = PaddingValues(top = topInset(), bottom = (if (selecting) 96.dp else 28.dp) + bottomInset()),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(Glyph.Back, "返回项目", onBack)
                        Column {
                            Txt(roll?.name ?: "项目", Type.h1)
                            Txt("${frames.size} 张", Type.numSmall)
                        }
                    }
                }
                if (frames.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    Txt(
                        "这个项目还是空的。到「全部」里选几张照片，归入这个项目。", Type.body.copy(lineHeight = 25.sp),
                        Modifier.padding(horizontal = 16.dp, vertical = 26.dp).widthIn(max = 336.dp), color = Ink.Dim, maxLines = 3,
                    )
                }
                items(frames, key = { it.id }) { f ->
                    Tile(
                        selecting, f.id in selection, f.raw,
                        onClick = {
                            if (!selecting) onOpen(f.id)
                            else selection = if (f.id in selection) selection - f.id else selection + f.id
                        },
                        onHold = {
                            selecting = true
                            selection = selection + f.id
                        },
                    ) { FileImage(f.thumb, Modifier.fillMaxSize()) }
                }
            }
        },
        chrome = {
            // (an empty project has nothing to select: no button is offered there)
            TopBar(
                tabs = tabs,
                actions = if (roll?.frames.isNullOrEmpty() && !selecting) null else ({
                    IconButton(if (selecting) Glyph.Close else Glyph.Select, if (selecting) "取消选择" else "选择", { if (selecting) leave() else selecting = true })
                }),
            )
            SelectionTray(selecting, selection.size) {
                val any = selection.isNotEmpty()
                IconButton(Glyph.Folder, "归入项目", { grouping = true }, enabled = any)
                IconButton(Glyph.Recipe, "套用配方", { applying = true }, enabled = any)
                IconButton(Glyph.Trash, "删除", { deleting = true }, enabled = any, tint = Ink.Rec)
                Btn("冲印", { printing = true }, kind = BtnKind.Key, size = BtnSize.Small, enabled = any)
            }
        },
    )

    // ---- into another project
    Sheet(grouping, { grouping = false }, title = "归入项目") {
        CtlColumn(Modifier.fillMaxWidth()) {
            for (r in rolls) if (r.id != projectId) SheetRow(null, r.name, "${r.frames.size} 张") {
                val ids = selection
                grouping = false
                leave()
                scope.launch { library.moveToProject(ids, r.id) }
                overlays?.toast("已归入项目", "${r.name} · ${ids.size} 张")
            }
            SheetRow(Glyph.Plus, "新建项目", "") { grouping = false; creating = true }
        }
    }
    NewProject(creating, { creating = false }, name, { name = it }) {
        val label = name.trim()
        val ids = selection
        scope.launch {
            val id = library.createProject(label)
            if (ids.isNotEmpty()) library.moveToProject(ids, id)
            name = ""
            creating = false
            leave()
            overlays?.toast("已归入项目", "$label · ${ids.size} 张")
        }
    }
    // ---- one recipe on everything that is selected
    Sheet(applying, { applying = false }, title = "给这 ${selection.size} 张套用配方") {
        Txt("配方包含胶片、质感、放大机、片边、日期及曲线等设置；每张照片的曝光、白平衡等基础校正单独保留。配方在「胶片」中管理。", Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 4)
        Gap(height = 8.dp)
        if (saved.isEmpty()) Txt("还没有配方。", Type.label, Modifier.padding(vertical = 14.dp), color = Ink.Faint)
        CtlColumn(Modifier.fillMaxWidth()) {
            for (r in saved) SheetRow(Glyph.Recipe, r.name, recipeLine(r.look)) {
                val frames = chosen()
                applying = false
                leave()
                scope.launch { for (f in frames) library.saveLook(f.id, f.look.withRecipe(r.look)) }
                overlays?.toast("已套用配方", "${r.name} · ${frames.size} 张")
            }
        }
    }
    // ---- printing everything that is selected, each with its own settings
    var edge by remember { mutableStateOf(3072) }
    var format by remember { mutableStateOf(PrintFormat.AUTO) }
    Sheet(
        printing, { printing = false }, title = "冲印这 ${selection.size} 张",
        foot = {
            Btn("排进冲印队列", {
                val frames = chosen()
                printing = false
                leave()
                for (f in frames) prints.order(f, f.look, if (edge == 0) f.longEdge else edge, format)
                overlays?.toast("已经排进冲印队列", "${frames.size} 张")
            }, Modifier.weight(1f), BtnKind.Key)
        },
    ) {
        CtlColumn(Modifier.fillMaxWidth()) {
            Ctl("长边", value = "像素", note = "每一张按它自己现在的设置冲印，在后台一张接一张地做，成片存进相册的 ${APP_NAME} 相簿。") {
                Seg(listOf(Opt("2048", "2048"), Opt("3072", "3072"), Opt("4096", "4096"), Opt("0", "原尺寸")), edge.toString(), { edge = it.toInt() }, Modifier.fillMaxWidth(), block = true)
            }
            Ctl("文件") { PrintFormatPicker(format) { format = it } }
        }
    }
    Sheet(
        deleting, { deleting = false }, title = "删除这 ${selection.size} 张底片？",
        foot = {
            Btn("保留", { deleting = false }, Modifier.weight(1f))
            Btn("删除", {
                val ids = selection
                deleting = false
                leave()
                scope.launch { library.delete(ids) }
            }, Modifier.weight(1f), BtnKind.Danger)
        },
    ) {
        Txt("片库里的原片和调整会一起删除。已经冲印并保存到相册的成片不受影响。", Type.body, color = Ink.GlassDim, maxLines = 3)
    }
}

// ------------------------------------------------------------------ shared: the photo grid

/** One photograph in a grid: square, edge to edge. While choosing, a mark in its corner says whether it is chosen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tile(selecting: Boolean, on: Boolean, raw: Boolean, onClick: () -> Unit, onHold: (() -> Unit)? = null, picture: @Composable () -> Unit) {
    val k by animateFloatAsState(if (on) 1f else 0f, tween(250, easing = Motion.Ease), label = "tile")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(Ink.Bg3)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onHold)
            .semantics { if (selecting) selected = on },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - .3f * k
                val s = 1f - .07f * k
                scaleX = s
                scaleY = s
            },
        ) { picture() }
        if (raw) Txt(
            "RAW", Type.film.copy(fontSize = 11.sp, letterSpacing = .1.em, shadow = Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 1f), 4f)),
            Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 5.dp), color = Color.White,
        )
        if (selecting) Box(
            Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp).clip(CircleShape)
                .background(if (on) Ink.Amber else Color.Black.copy(alpha = .28f))
                .border(1.5.dp, if (on) Ink.Amber else Color.White.copy(alpha = .9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(Glyph.Check, tint = Ink.AmberInk, size = 14.dp)
        }
    }
}

/** What can be done with the chosen photographs: a capsule that comes up from the bottom while choosing. */
@Composable
internal fun BoxScope.SelectionTray(open: Boolean, count: Int, actions: @Composable RowScope.() -> Unit) {
    val shell = LocalShell.current
    val reduced = LocalReducedMotion.current
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (reduced) 0 else 500, easing = Motion.Ease), label = "tray")
    if (k == 0f) return
    val lift: Dp = 60.dp + 28.dp + GAP + bottomInset()
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = GAP + shell.insetLeft + leftInset(), end = GAP + rightInset(), bottom = GAP + bottomInset())
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(60.dp)
            .offset { IntOffset(0, ((1f - k) * lift.toPx()).roundToInt()) }
            .glass(30.dp, GlassTone.Strong)
            .padding(start = 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(if (count == 0) "点照片来选择" else "已选 $count 张", Type.body.copy(fontSize = 13.5.sp), Modifier.weight(1f), color = Ink.GlassText)
        actions()
    }
}

/** A line between groups of photographs: what they share, and how many there are. */
@Composable
internal fun GridLabel(text: String, count: Int, first: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (first) 6.dp else 20.dp, bottom = 9.dp), verticalAlignment = Alignment.Bottom) {
        Txt(text, Type.body.copy(fontSize = 13.5.sp), Modifier.weight(1f), color = Ink.Dim)
        Txt("$count 张", Type.numSmall, color = Ink.Faint)
    }
}
