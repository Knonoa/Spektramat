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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.spektra.latent.data.Decoder
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.Dispatchers
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
 * one is shown as that photograph wearing it -- a small print made by the
 * engine; without, by name. Films can be narrowed to one kind.
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
) {
    val films = items.isNotEmpty() && items.all { stock -> FilmEngine.catalog.films.any { it.id == stock.id } }
    var kind by remember { mutableStateOf("") }
    val visible = if (films && kind.isNotEmpty()) items.filter { it.kind == kind } else items
    val pictured = frame != null && look != null
    Column(modifier) {
        if (films) {
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
                    if (pictured) StockSample(s, films, frame!!, look!!, Modifier.size(92.dp))
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

/** Each visible swatch is a small full-engine print of this photograph with that stock. */
@Composable
private fun StockSample(stock: Stock, film: Boolean, frame: Frame, look: Look, modifier: Modifier) {
    val context = LocalContext.current
    val candidate =
        if (film) look.withFilm(stock.id).copy(edge = look.edge.copy(on = false), date = look.date.copy(on = false))
        else look.copy(
            paper = if (stock.id == SCAN) look.paper else stock.id, scan = stock.id == SCAN,
            edge = look.edge.copy(on = false), date = look.date.copy(on = false),
        )
    val key = "${frame.id}-${frame.revision}-${candidate.hashCode()}-${FilmEngine.renderCacheKey}"
    var image by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        image = withContext(Dispatchers.IO) {
            sampleQueue.withPermit {
                val dir = File(context.cacheDir, "stock-samples").apply { mkdirs() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
                val file = File(dir, "$key.jpg")
                val cached = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
                if (cached != null) cached.asImageBitmap()
                else runCatching {
                    Decoder.decode(frame.file, 180, candidate.lensShading, candidate.aspect).use { source ->
                        val bitmap = FilmEngine.develop(
                            source, candidate.photoGrade(),
                            candidate.photoOrder(frame.id * 7919L + 1L, Shooting.of(frame), Library.frameSeed(frame), frame.number),
                            candidate.finish(), candidate.finishTable(), lane = FilmEngine.THUMB,
                        )
                        try {
                            val bytes = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
                            android.graphics.Canvas(bytes).drawBitmap(bitmap, 0f, 0f, null)
                            file.outputStream().use { bytes.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                            bytes.asImageBitmap()
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }.getOrNull()
            }
        }
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
