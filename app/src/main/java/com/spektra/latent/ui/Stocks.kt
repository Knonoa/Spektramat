package com.spektra.latent.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spektra.latent.data.Frame
import com.spektra.latent.data.CreativeSource
import com.spektra.latent.data.PhotoProxyStore
import com.spektra.latent.data.PhotoLutCache
import com.spektra.latent.gl.PhotoPreviewRenderer
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** A film stock or a paper as the pickers show it. */
class Stock(val id: String, val name: String, val kind: String)

/** Every film the engine has, in its own order. */
fun filmStocks(): List<Stock> =
    FilmEngine.catalog.films.map { Stock(it.id, it.name, filmKind(it.positive, it.blackAndWhite, it.cine)) }

private val KINDS = listOf(Opt("", "全部"), Opt("彩色负片", "彩色负"), Opt("反转片", "反转片"), Opt("电影负片", "电影负"), Opt("黑白负片", "黑白负"))

/**
 * Film stocks or papers to choose from. With a [frame] and its [look] every
 * one is shown as that photograph wearing it, using a cached colour cube
 * on the shared source proxy. The film picker can replace those samples with
 * lightweight, stock-specific cartridge artwork. This is only a browser
 * preference: choosing a stock still changes the photograph's look.
 *
 * @param bleed the side padding of what holds this: the row scrolls out to the edges through it
 */
@Composable
fun StockRail(
    items: List<Stock>,
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    frame: Frame? = null,
    look: Look? = null,
    bleed: Dp = 0.dp,
    previewToggle: Boolean = false,
) {
    val films = items.isNotEmpty() && items.all { stock -> FilmEngine.catalog.films.any { it.id == stock.id } }
    var kind by remember { mutableStateOf("") }
    val visible = if (films && kind.isNotEmpty()) items.filter { it.kind == kind } else items
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", 0) }
    var samplesEnabled by remember { mutableStateOf(preferences.getBoolean("filmPickerPreview", true)) }
    val canPreview = frame != null && look != null
    val pictured = canPreview && (!films || !previewToggle || samplesEnabled)
    // No renderer, proxy acquisition, cube generation or sample jobs in artwork mode.
    val renderer = remember(context, pictured) { if (pictured) PhotoPreviewRenderer(context) else null }
    DisposableEffect(renderer) { onDispose { renderer?.release() } }
    Column(modifier) {
        if (films) {
            if (canPreview && previewToggle) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("效果预览", Type.label, Modifier.weight(1f), color = Ink.GlassDim)
                    Toggle(samplesEnabled, {
                        samplesEnabled = it
                        preferences.edit().putBoolean("filmPickerPreview", it).apply()
                    }, "胶片型号效果预览")
                }
            }
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 300.dp * fontScale) {
                    Seg(KINDS, kind, { kind = it }, Modifier.fillMaxWidth(), style = SegStyle.Small, block = true)
                } else {
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Seg(KINDS, kind, { kind = it }, style = SegStyle.Small)
                    }
                }
            }
            Gap(height = 12.dp)
        }
        // open on the chosen one
        val start = visible.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
        LaunchedEffect(kind) { list.scrollToItem(visible.indexOfFirst { it.id == selected }.coerceAtLeast(0)) }
        val accent = Ink.Accent
        LazyRow(
            Modifier.layoutBleed(bleed).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = bleed),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.id }) { s ->
                val on = s.id == selected
                Column(
                    Modifier
                        .width(92.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { onPick(s.id) }
                        .semantics { this.selected = on }
                        .drawBehind {
                            if (on) drawRect(accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                        }
                        .padding(bottom = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (pictured) StockSample(s, films, frame!!, look!!, renderer!!, Modifier.size(92.dp))
                    else if (films && previewToggle) FilmCartridge(s, Modifier.size(92.dp))
                    Txt(
                        shortName(s.name), Type.film.copy(fontSize = 11.sp, letterSpacing = .04.em, lineHeight = 14.3.sp),
                        Modifier.heightIn(min = 28.6.dp), color = if (on) Ink.GlassText else Ink.GlassDim, maxLines = 2,
                    )
                    Txt(s.kind, Type.micro.copy(lineHeight = 14.sp), color = Ink.Faint)
                }
            }
        }
    }
}

private val sampleQueue = Semaphore(1)

/** Visible swatches share the floating source proxy and cached pointwise photo cubes. */
@Composable
private fun StockSample(stock: Stock, film: Boolean, frame: Frame, look: Look, renderer: PhotoPreviewRenderer, modifier: Modifier) {
    val context = LocalContext.current
    val candidate =
        if (film) look.withFilm(stock.id).copy(edge = look.edge.copy(on = false), date = look.date.copy(on = false))
        else look.copy(
            paper = if (stock.id == SCAN) look.paper else stock.id, scan = stock.id == SCAN,
            edge = look.edge.copy(on = false), date = look.date.copy(on = false),
        )
    val key = "lut-v1-${frame.id}-${frame.file.length()}-${frame.file.lastModified()}-${candidate.hashCode()}-${FilmEngine.renderCacheKey}"
    var image by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        var pending: android.graphics.Bitmap? = null
        try {
        val result = withContext(Dispatchers.IO) {
            sampleQueue.withPermit {
                val dir = File(context.cacheDir, "stock-samples").apply { mkdirs() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
                val file = File(dir, "$key.jpg")
                val cached = (if (file.isFile) BitmapFactory.decodeFile(file.path) else null).also { pending = it }
                if (cached != null) cached.asImageBitmap()
                else try {
                    val cube = PhotoLutCache.get(context, candidate.photoOrder(0L), PhotoLutCache.SAMPLE_SIZE, interactive = false)
                    PhotoProxyStore.get(context).acquire(frame, candidate.aspect, candidate.lensShading, 128).use { source ->
                        currentCoroutineContext().ensureActive()
                        val sourceKey = "${frame.file.path}:${frame.file.length()}:${frame.file.lastModified()}:${candidate.aspect}:${candidate.lensShading}:${candidate.creative}:128"
                        val creative = if (candidate.creative.active) CreativeSource(context).apply(source, candidate, 128) else null
                        val bitmap = try {
                            renderer.render(creative ?: source, sourceKey, candidate, cube, false, 128)
                        } finally { creative?.close() }
                        pending = bitmap
                        currentCoroutineContext().ensureActive()
                        try {
                            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                        } catch (_: java.io.IOException) { /* a display cache is disposable */ }
                        bitmap.asImageBitmap()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
        currentCoroutineContext().ensureActive()
        image = result
        pending = null
        } finally { pending?.recycle() }
    }
    image?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) } ?: Box(modifier.background(Ink.Well))
}

/** In a list of papers: no paper at all, the film looked at directly. */
const val SCAN = "@scan"

fun filmKind(positive: Boolean, bw: Boolean, cine: Boolean) = when {
    bw -> "黑白负片"
    positive -> "反转片"
    cine -> "电影负片"
    else -> "彩色负片"
}

/** "Kodak Portra 400" -> "PORTRA 400": the maker is dropped, the way a film's edge print names it ("Kodak Professional" too). */
fun shortName(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotEmpty() }
    val makers = setOf("kodak", "fujifilm", "fuji", "ilford")
    var kept = if (words.size > 1 && words[0].lowercase() in makers) words.drop(1) else words
    if (kept.size > 1 && kept[0].equals("Professional", ignoreCase = true)) kept = kept.drop(1)
    return kept.joinToString(" ").uppercase()
}
