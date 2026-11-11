package com.spektra.latent.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.LatentApp
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.Icon
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Notice
import com.spektra.latent.ui.Pill
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.pressable
import kotlinx.coroutines.launch

/**
 * The library: every photograph, roll by roll, shown as it currently prints.
 * Tap a frame to take it into the darkroom; hold one to select.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    library: Library,
    pendingImport: List<Uri>,
    onImportTaken: () -> Unit,
    onOpen: (Long) -> Unit,
    onCamera: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LatentApp
    val scope = rememberCoroutineScope()
    val rolls by library.rolls.collectAsStateWithLifecycle()
    val importing by library.importing.collectAsStateWithLifecycle()
    val failures by library.importFailures.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var chooseSource by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // an import belongs to the app, not to this screen: it goes on if the person walks into the darkroom
    fun import(uris: List<Uri>) {
        if (uris.isNotEmpty()) app.scope.launch { library.import(uris) }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { import(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it) }
    fun pickPhotos() = photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    // RAW files rarely carry an image MIME type, so the file picker must not filter on one
    fun pickFiles() = files.launch(arrayOf("*/*"))

    LaunchedEffect(pendingImport) {
        if (pendingImport.isNotEmpty()) {
            import(pendingImport)
            onImportTaken()
        }
    }
    LaunchedEffect(failures) {
        if (failures.isNotEmpty()) {
            notice = if (failures.size == 1) "没能导入 ${failures[0]}" else "${failures.size} 个文件没能导入，例如 ${failures[0]}"
            library.clearImportFailures()
        }
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Box(Modifier.fillMaxSize().background(Ink.Curtain)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar: the place, or what is selected
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selection.isEmpty()) {
                    Txt("片库", Type.title, Modifier.weight(1f))
                    if (rolls.isNotEmpty()) Pill("导入", { chooseSource = true })
                } else {
                    IconButton(Glyph.Close, "取消选择", { selection = emptySet() })
                    Txt("已选 ${selection.size} 张", Type.heading, Modifier.weight(1f).padding(start = 4.dp))
                    IconButton(Glyph.Trash, "删除", { confirmDelete = true }, tint = Ink.Red)
                }
            }
            importing?.let { (done, total) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("正在导入", Type.label, color = Ink.Dim)
                    Gap(width = 8.dp)
                    Txt("${done + 1} / $total", Type.edge, color = Ink.Amber)
                }
            }

            if (rolls.isEmpty() && importing == null) {
                Empty(onPhotos = ::pickPhotos, onFiles = ::pickFiles)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),   // three across on a phone, more on wider screens
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (roll in rolls) {
                        item(key = "roll-${roll.id}", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Txt(roll.name, Type.edge, Modifier.weight(1f))
                                Txt("${roll.frames.size} 张", Type.caption)
                            }
                        }
                        items(roll.frames, key = { it.id }) { frame ->
                            FrameCell(
                                frame = frame,
                                selected = frame.id in selection,
                                onClick = {
                                    if (selection.isEmpty()) onOpen(frame.id)
                                    else selection = if (frame.id in selection) selection - frame.id else selection + frame.id
                                },
                                onLongClick = { selection = selection + frame.id },
                            )
                        }
                    }
                }
            }
        }

        // ---- the way to the camera (and, later, to a tethered one): the library is home, these start from it
        if (selection.isEmpty()) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp)
                    .pressable(onClick = onCamera)
                    .clip(RoundedCornerShape(50))
                    .background(Ink.Raised)
                    .border(1.dp, Ink.Line, RoundedCornerShape(50))
                    .padding(horizontal = 24.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyph.Camera, size = 20.dp)
                Gap(width = 8.dp)
                Txt("相机", Type.label)
            }
        }

        Notice(notice, { notice = null }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp))

        Sheet(chooseSource, { chooseSource = false }) {
            Txt("导入到片库", Type.heading)
            Gap(height = 4.dp)
            Txt("原文件会被复制进来，之后的调整都不会改动它。", Type.label, color = Ink.Dim, maxLines = 2)
            Gap(height = 16.dp)
            SheetRow("从相册选照片", "JPEG、HEIF、PNG") { chooseSource = false; pickPhotos() }
            SheetRow("从文件选 RAW", "DNG、ARW、CR2 / CR3、NEF、RAF、RW2、ORF …") { chooseSource = false; pickFiles() }
        }

        Sheet(confirmDelete, { confirmDelete = false }) {
            Txt("删除这 ${selection.size} 张底片？", Type.heading)
            Gap(height = 6.dp)
            Txt("片库里的原片和调整会一起删除。已经冲印并保存到相册的成片不受影响。", Type.label, color = Ink.Dim, maxLines = 3)
            Gap(height = 18.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("保留", { confirmDelete = false }, Modifier.weight(1f))
                Pill("删除", {
                    val ids = selection
                    confirmDelete = false
                    selection = emptySet()
                    scope.launch { library.delete(ids) }
                }, Modifier.weight(1f), filled = true, danger = true)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrameCell(frame: Frame, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            FileImage(frame.thumb, Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)))
            if (selected) {
                Box(Modifier.fillMaxSize().border(2.dp, Ink.Amber, RoundedCornerShape(2.dp)))
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(Ink.Amber, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyph.Check, tint = Ink.Curtain, size = 14.dp)
                }
            }
        }
        // the rebate: the frame number, as on the edge of a roll
        Row(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${frame.number}", Type.edgeSmall, Modifier.weight(1f), color = if (selected) Ink.Amber else Ink.Dim)
            if (frame.raw) Txt("RAW", Type.edgeSmall, color = Ink.Faint)
        }
    }
}

@Composable
private fun SheetRow(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 12.dp)) {
        Txt(title, Type.body)
        Gap(height = 2.dp)
        Txt(detail, Type.caption)
    }
}

/** Nothing here yet: say what goes here and how to put it in. */
@Composable
private fun Empty(onPhotos: () -> Unit, onFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 36.dp).padding(bottom = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.width(132.dp).height(96.dp)) {
            val line = 1.5.dp.toPx()
            val hole = Size(9.dp.toPx(), 6.dp.toPx())
            // an unexposed frame between its perforations
            drawRoundRect(Ink.Faint, Offset(0f, 18.dp.toPx()), Size(size.width, size.height - 36.dp.toPx()), CornerRadius(3.dp.toPx()), style = Stroke(line))
            for (i in 0 until 7) {
                val x = 6.dp.toPx() + i * (size.width - 12.dp.toPx() - hole.width) / 6f
                drawRoundRect(Ink.Line, Offset(x, 3.dp.toPx()), hole, CornerRadius(1.5.dp.toPx()))
                drawRoundRect(Ink.Line, Offset(x, size.height - 9.dp.toPx()), hole, CornerRadius(1.5.dp.toPx()))
            }
        }
        Gap(height = 28.dp)
        Txt("片库里还没有底片", Type.heading)
        Gap(height = 8.dp)
        Txt("导入照片或 RAW，原文件会完整地存进来，\n然后就可以选一款胶片冲洗它。", Type.label, color = Ink.Dim, maxLines = 3, align = TextAlign.Center)
        Gap(height = 28.dp)
        Pill("从相册导入照片", onPhotos, Modifier.width(220.dp), filled = true)
        Gap(height = 10.dp)
        Pill("从文件导入 RAW", onFiles, Modifier.width(220.dp))
    }
}
