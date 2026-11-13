package com.spektra.latent.ui.library

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.LatentApp
import com.spektra.latent.data.Library
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnKind
import com.spektra.latent.ui.BtnSize
import com.spektra.latent.ui.CtlColumn
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.GlassPage
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.LocalOverlays
import com.spektra.latent.ui.LocalShell
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.TopBar
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.bottomInset
import com.spektra.latent.ui.leftInset
import com.spektra.latent.ui.rightInset
import com.spektra.latent.ui.topInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private data class AlbumPhoto(val uri: Uri, val name: String, val taken: Long)

/** "11 月 12 日"; the year is said only when it is not this one. */
private fun dayLabel(time: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = time }
    val year = c.get(Calendar.YEAR)
    val day = "${c.get(Calendar.MONTH) + 1} 月 ${c.get(Calendar.DAY_OF_MONTH)} 日"
    return if (year == Calendar.getInstance().get(Calendar.YEAR)) day else "$year 年 $day"
}

/**
 * 全部: the phone's album, read directly and shown by day. A photograph enters
 * the app's own library only when it is asked for: tap one to put it on the
 * workbench, or choose several to put them there or into a project.
 */
@Composable
internal fun AlbumBrowser(library: Library, onBench: (Long) -> Unit, tabs: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LatentApp
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shell = LocalShell.current
    val overlays = LocalOverlays.current
    var refresh by remember { mutableIntStateOf(0) }
    var photos by remember { mutableStateOf<List<AlbumPhoto>>(emptyList()) }
    var permitted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    var projects by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val rolls by library.rolls.collectAsStateWithLifecycle()
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) app.scope.launch {
            importing = true
            try { library.import(uris).added.firstOrNull()?.let(onBench) } finally { importing = false }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        permitted = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!permitted) {
            photos = emptyList()
            loading = false
            return@LaunchedEffect
        }
        photos = withContext(Dispatchers.IO) {
            runCatching {
                val out = ArrayList<AlbumPhoto>()
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
                    null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext()) out += AlbumPhoto(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                        c.getString(1) ?: "照片", c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000),
                    )
                }
                out.sortedByDescending { it.taken }
            }.getOrElse { emptyList() }
        }
        loading = false
    }
    fun leave() {
        selecting = false
        selected = emptySet()
    }
    /** Copies the chosen photographs into the library: onto the bench, or into a project. */
    fun bring(uris: List<Uri>, project: Long? = null, label: String = "") {
        if (importing || uris.isEmpty()) return
        importing = true
        app.scope.launch {
            try {
                val report = library.import(uris, project)
                leave()
                if (project == null) report.added.firstOrNull()?.let(onBench)
                else overlays?.toast("已归入项目", "$label · ${report.added.size} 张")
            } finally {
                importing = false
            }
        }
    }
    BackHandler(selecting) { leave() }

    GlassPage(
        behind = {
            Box(Modifier.fillMaxSize().background(Ink.Bg))
            when {
                !permitted && !loading -> BoxWithConstraints(
                    Modifier.fillMaxSize().padding(start = 24.dp + shell.insetLeft + leftInset(), end = 24.dp + rightInset(), top = topInset(), bottom = 16.dp + bottomInset()),
                ) {
                    val compact = maxHeight < 360.dp
                    Column(
                        Modifier.align(Alignment.BottomStart).verticalScroll(rememberScrollState()).padding(bottom = if (compact) 0.dp else 48.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                    ) {
                        Txt("看手机里的照片", if (compact) Type.h1 else Type.display)
                        Txt(
                            "允许访问以后，这里按日期排出相册里的照片。也可以用系统的选图器，只交出你选中的那几张。",
                            Type.body.copy(lineHeight = 24.5.sp), Modifier.widthIn(max = 336.dp), color = Ink.Dim, maxLines = 4,
                        )
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("允许访问相册", { access.launch(permissions) }, kind = BtnKind.Key, icon = Glyph.Album)
                            Btn("选照片", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, kind = BtnKind.Line)
                        }
                    }
                }
                photos.isEmpty() -> Txt(
                    if (loading) "正在读取相册…" else "相册里还没有照片。", Type.body,
                    Modifier.padding(start = 24.dp + shell.insetLeft + leftInset(), end = 24.dp + rightInset(), top = topInset() + 8.dp), color = Ink.Dim,
                )
                else -> {
                    val days = remember(photos) { photos.groupBy { dayLabel(it.taken) }.toList() }
                    LazyVerticalGrid(
                        GridCells.Adaptive(108.dp), Modifier.fillMaxSize().padding(start = shell.insetLeft + leftInset(), end = rightInset()),
                        contentPadding = PaddingValues(top = topInset(), bottom = (if (selecting) 96.dp else 28.dp) + bottomInset()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        days.forEachIndexed { index, (day, group) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = day) { GridLabel(day, group.size, index == 0) }
                            items(group, key = { it.uri.toString() }) { photo ->
                                Tile(
                                    selecting, photo.uri in selected, raw = false,
                                    onClick = {
                                        if (selecting) selected = if (photo.uri in selected) selected - photo.uri else selected + photo.uri
                                        else bring(listOf(photo.uri))
                                    },
                                    onHold = {
                                        selecting = true
                                        selected = selected + photo.uri
                                    },
                                ) { AlbumImage(photo.uri, Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        },
        chrome = {
            TopBar(
                tabs = tabs,
                actions = if (photos.isEmpty()) null else ({
                    IconButton(if (selecting) Glyph.Close else Glyph.Select, if (selecting) "取消选择" else "选择", { if (selecting) leave() else selecting = true })
                }),
            )
            SelectionTray(selecting, selected.size) {
                Btn("归入项目", { projects = true }, size = BtnSize.Small, enabled = selected.isNotEmpty() && !importing)
                Btn("放上工作台", { bring(selected.toList()) }, kind = BtnKind.Key, size = BtnSize.Small, enabled = selected.isNotEmpty() && !importing)
            }
        },
    )

    Sheet(projects, { projects = false }, title = "归入项目") {
        CtlColumn(Modifier.fillMaxWidth()) {
            for (roll in rolls) SheetRow(null, roll.name, "${roll.frames.size} 张") {
                projects = false
                bring(selected.toList(), roll.id, roll.name)
            }
            SheetRow(Glyph.Plus, "新建项目", "") { projects = false; creating = true }
        }
    }
    NewProject(creating, { creating = false }, name, { name = it }) {
        val label = name.trim()
        val uris = selected.toList()
        app.scope.launch {
            val id = library.createProject(label)
            name = ""
            creating = false
            bring(uris, id, label)
        }
    }
}

@Composable
private fun AlbumImage(uri: Uri, modifier: Modifier) {
    val resolver = LocalContext.current.contentResolver
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, android.util.Size(320, 320), null).asImageBitmap()
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val opts = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 320).coerceAtLeast(1) }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap() }
                }
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) } ?: Box(modifier.background(Ink.Bg3))
}
