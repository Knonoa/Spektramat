package com.spektra.latent.ui.darkroom

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.APP_NAME
import com.spektra.latent.data.Recipes
import com.spektra.latent.data.PrintFormat
import com.spektra.latent.model.Aspects
import com.spektra.latent.model.Format
import com.spektra.latent.ui.TextEntry
import com.spektra.latent.ui.PrintFormatPicker
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.spektra.latent.data.Library
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Control
import com.spektra.latent.model.Controls
import com.spektra.latent.model.Look
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.Hairline
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.ParamChip
import com.spektra.latent.ui.Pill
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.Stock
import com.spektra.latent.ui.StockStrip
import com.spektra.latent.ui.filmStocks
import com.spektra.latent.ui.shortName
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.pressable
import kotlin.math.roundToInt

/** The places a look can be changed, in the order light passes them; the last is the finished print itself. */
private enum class Stage(val title: String) { Scene("原片"), Film("胶片"), Edge("片边"), Print("印相"), Finish("整饰") }

/**
 * The darkroom: the photograph above, and below it the three stages of the
 * process -- scene, film, print -- each with its own controls. One ruler at
 * the bottom sets whichever control is selected.
 */
@Composable
fun DarkroomScreen(library: Library, recipes: Recipes, frameId: Long, onBack: () -> Unit, onDevelop: (Look, Int, PrintFormat) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val frame = remember(frameId) { library.frame(frameId) }
    if (frame == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val state = remember(frameId) { DarkroomState(context, library, frame, scope) }
    DisposableEffect(state) { onDispose { state.dispose() } }

    val density = LocalDensity.current
    var view by remember { mutableStateOf(IntSize.Zero) }
    var topBar by remember { mutableIntStateOf(0) }
    var panel by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf(Stage.Film) }
    // which control each stage last had under the ruler; -1 is the stock / paper picker
    var sceneAt by remember { mutableIntStateOf(0) }
    var filmAt by remember { mutableIntStateOf(-1) }
    var printAt by remember { mutableIntStateOf(-1) }
    var ordering by remember { mutableStateOf(false) }
    var printFormat by remember { mutableStateOf(PrintFormat.AUTO) }
    var recipesOpen by remember { mutableStateOf(false) }
    var recipeName by remember { mutableStateOf("") }
    val saved by recipes.all.collectAsStateWithLifecycle()

    LaunchedEffect(view, topBar, panel) {
        if (view.width > 0 && panel > 0) {
            val margin = with(density) { 12.dp.toPx() }
            state.layout(view.width.toFloat(), view.height.toFloat(), topBar + margin, panel + margin)
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.Curtain).onSizeChanged { view = it }) {
        // ---- the picture: one GPU surface under everything
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = state.preview.attach(s, w, h)
                        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = state.preview.resize(w, h)
                        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                            state.preview.detach()
                            return true
                        }
                        override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
                    }
                }
            },
        )

        // until the GPU has drawn it, the thumbnail stands in the same place
        if (view.width > 0) {
            val shown = state.shownSize
            val r = state.placement.rect(view.width.toFloat(), view.height.toFloat(), shown[0], shown[1])
            AnimatedVisibility(!state.ready && !state.comparing, enter = fadeIn(tween(0)), exit = fadeOut(tween(260))) {
                FileImage(
                    frame.thumb,
                    Modifier
                        .offset { IntOffset(r[0].roundToInt(), r[1].roundToInt()) }
                        .size(with(density) { r[2].toDp() }, with(density) { r[3].toDp() }),
                    scale = ContentScale.Fit,
                )
            }
            // the edge of the film and the date are the engine's work: its proof stands where the GPU picture was
            val proof = state.proof
            if (proof != null && !state.comparing) {
                Box(Modifier.fillMaxSize().background(Ink.Curtain))
                Image(
                    bitmap = proof, contentDescription = null,
                    modifier = Modifier
                        .offset { IntOffset(r[0].roundToInt(), r[1].roundToInt()) }
                        .size(with(density) { r[2].toDp() }, with(density) { r[3].toDp() }),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }

        // ---- gestures on the picture: pinch and drag, double-tap to zoom, hold to see it without the film
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
                }
                .pointerInput(state) {
                    detectTapGestures(
                        onDoubleTap = { state.toggleZoom(it) },
                        onLongPress = { state.compare(true) },
                        onPress = {
                            tryAwaitRelease()
                            state.compare(false)
                        },
                    )
                },
        )

        val tag = when {
            state.comparing -> "原片"
            state.proofing -> "正在出样张"
            state.proof != null -> "样张 · 颗粒与细节以成片为准"
            else -> null
        }
        if (tag != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Ink.Body.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Txt(tag, Type.caption, color = Ink.Text)
            }
        }
        state.failure?.let {
            Txt(it, Type.body, Modifier.align(Alignment.Center).padding(32.dp), color = Ink.Dim, maxLines = 4)
        }

        // ---- top bar
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { topBar = it.height }   // status bar inset included: the picture stays clear of both
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(Glyph.Back, "回到片库", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Txt(FilmEngine.catalog.film(state.look.film)?.name ?: state.look.film, Type.edge)
                Txt("${frame.number}  ${frame.name}", Type.edgeSmall)
            }
            IconButton(Glyph.Undo, "撤销", state::undo, enabled = state.canUndo)
            IconButton(Glyph.Redo, "重做", state::redo, enabled = state.canRedo)
            IconButton(Glyph.Recipe, "配方", { recipesOpen = true })
            Gap(width = 2.dp)
            Pill("冲印", { ordering = true }, filled = true, enabled = state.ready)
            Gap(width = 8.dp)
        }
        // ---- the bench: stages, the stage's controls, the ruler
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Ink.Body)
                .onSizeChanged { panel = it.height }
                .navigationBarsPadding(),
        ) {
            StageTabs(stage) { stage = it }
            Hairline(Modifier.fillMaxWidth())
            when (stage) {
                Stage.Scene -> ControlBench(
                    state, Controls.scene, sceneAt, { sceneAt = it },
                    pickers = listOf(
                        Picker("画幅", Aspects.label(state.look.aspect), state.look.aspect.isNotEmpty()) {
                            StockStrip(
                                items = Aspects.all.map { Stock(it.id, Aspects.label(it.id), it.note) },
                                selected = state.look.aspect,
                                onPick = { state.change(state.look.copy(aspect = it)); state.commit() },
                            )
                        },
                    ),
                    first = 0,
                )
                Stage.Film -> ControlBench(
                    state, Controls.film, filmAt, { filmAt = it },
                    pickers = listOf(
                        Picker("型号", shortName(FilmEngine.catalog.film(state.look.film)?.name ?: "")) {
                            StockStrip(
                                items = filmStocks(),
                                selected = state.look.film,
                                onPick = { state.change(state.look.withFilm(it)); state.commit() },
                            )
                        },
                        // how large the frame is on the film: grain and halation are sized by it, and the edge follows it
                        Picker("片幅", Format.of(state.look.format).label, state.look.format != "135") {
                            StockStrip(
                                items = Format.entries.map { Stock(it.id, it.label, "长边 ${it.longMm.roundToInt()} mm") },
                                selected = state.look.format,
                                onPick = { state.change(state.look.copy(format = it)); state.commit() },
                            )
                        },
                    ),
                )
                Stage.Edge -> EdgeBench(state.look, state.shooting, state::change, state::commit)
                Stage.Finish -> FinishBench(state)
                Stage.Print -> if (FilmEngine.catalog.film(state.look.film)?.positive == true) {
                    Box(Modifier.fillMaxWidth().height(BENCH_HEIGHT), contentAlignment = Alignment.Center) {
                        Txt("反转片不用印相：冲出来的胶片本身就是成片。", Type.label, color = Ink.Dim, maxLines = 2)
                    }
                } else {
                    ControlBench(
                        // looked at directly there is no enlarger and no paper: nothing here but the choice itself
                        state, if (state.look.scan) emptyList() else Controls.print, printAt, { printAt = it },
                        pickers = listOf(
                            Picker("相纸", if (state.look.scan) "直接扫描" else shortName(FilmEngine.catalog.paper(state.look.paper)?.name ?: ""), state.look.scan) {
                                StockStrip(
                                    items = listOf(Stock(SCAN, "直接扫描", "不经相纸，看底片本身")) + FilmEngine.catalog.papers.map {
                                        Stock(it.id, it.name, if (it.blackAndWhite) "黑白相纸" else if (it.cine) "电影拷贝片" else "彩色相纸")
                                    },
                                    selected = if (state.look.scan) SCAN else state.look.paper,
                                    onPick = {
                                        state.change(if (it == SCAN) state.look.copy(scan = true) else state.look.copy(scan = false, paper = it))
                                        state.commit()
                                    },
                                )
                            },
                        ),
                    )
                }
            }
        }

        // ---- ordering a print
        Sheet(ordering, { ordering = false }) {
            Txt("冲印这张照片", Type.heading)
            Gap(height = 4.dp)
            Txt("冲印在后台进行，不耽误继续调整；成片保存到相册的 ${APP_NAME} 相簿。尺寸是长边的像素数。", Type.label, color = Ink.Dim, maxLines = 3)
            Gap(height = 14.dp)
            PrintFormatPicker(printFormat) { printFormat = it }
            Gap(height = 14.dp)
            val native = state.look.frameSize(frame.width, frame.height).max()
            val sizes = listOf(2048, 3072, 4096).filter { it < native } + native
            for (edge in sizes) {
                Row(
                    Modifier.fillMaxWidth().pressable { ordering = false; state.commit(); onDevelop(state.look, edge, printFormat) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("$edge", Type.edgeLarge, Modifier.width(88.dp))
                    Txt(if (edge == native) "原尺寸" else if (edge <= 2048) "适合手机上看和分享" else "适合大屏和小幅打印", Type.label, color = Ink.Dim)
                }
            }
            if (state.look.edge.on) {
                Gap(height = 6.dp)
                Txt(
                    if (state.look.edge.open) "片门全开，成片就是画面本身的大小。" else "尺寸指画面本身；开着片边，成片会比它大出一圈胶片。",
                    Type.caption, maxLines = 2,
                )
            }
        }

        // ---- recipes: this look kept under a name, or a kept one put on this photograph
        Sheet(recipesOpen, { recipesOpen = false }) {
            Txt("配方", Type.heading)
            Gap(height = 4.dp)
            Txt("配方记下胶片、片边、日期、印相和整饰，可以用在别的照片、相机和监视器上。这张照片自己的校正（原片一栏）不进配方。", Type.label, color = Ink.Dim, maxLines = 4)
            Gap(height = 14.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val keep = {
                    val name = recipeName
                    recipeName = ""
                    state.commit()
                    val look = state.look
                    scope.launch { recipes.save(name, look) }
                    Unit
                }
                TextEntry(recipeName, { recipeName = it }, "给现在的设置起个名字", keep, Modifier.weight(1f))
                Gap(width = 10.dp)
                Pill("保存", keep, filled = true, enabled = recipeName.isNotBlank())
            }
            Gap(height = 10.dp)
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (saved.isEmpty()) Txt("还没有配方。", Type.label, Modifier.padding(vertical = 14.dp), color = Ink.Faint)
                for (r in saved) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).pressable {
                                recipesOpen = false
                                state.change(state.look.withRecipe(r.look))
                                state.commit()
                            }.padding(vertical = 10.dp),
                        ) {
                            Txt(r.name, Type.body)
                            Txt(recipeLine(r.look), Type.edgeSmall)
                        }
                        IconButton(Glyph.Trash, "删除配方 ${r.name}", { scope.launch { recipes.remove(r.id) } }, tint = Ink.Dim)
                    }
                }
            }
        }
    }
}

private const val SCAN = "@scan"

/** A recipe in one line of edge lettering: its film, and what else it carries. */
internal fun recipeLine(look: Look): String = listOfNotNull(
    shortName(FilmEngine.catalog.film(look.film)?.name ?: look.film),
    Format.of(look.format).label.takeIf { look.format != "135" },
    "片边".takeIf { look.edge.on },
    "日期".takeIf { look.date.on },
).joinToString("  ·  ")

private val BENCH_HEIGHT = 128.dp

private class Picker(val label: String, val value: String, val changed: Boolean = false, val content: @Composable () -> Unit)

@Composable
private fun StageTabs(current: Stage, onPick: (Stage) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Stage.entries.forEachIndexed { i, s ->
            if (i > 0) Txt("›", Type.label, Modifier.padding(horizontal = 2.dp), color = Ink.Faint)
            Box(Modifier.pressable { onPick(s) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Txt(s.title, Type.heading, color = if (s == current) Ink.Amber else Ink.Dim)
            }
        }
    }
}

/**
 * The controls of a stage: a row of chips (pickers first, then one chip per
 * ruler) and, under it, either the strip of a picker or the ruler of the
 * selected chip. [at] counts rulers from 0; picker i is -(i + 1). [first] is
 * where a stage with nothing chosen yet opens.
 */
@Composable
private fun ControlBench(state: DarkroomState, controls: List<Control>, at: Int, onAt: (Int) -> Unit, pickers: List<Picker>, first: Int = -1) {
    val wanted = if (at < -pickers.size || at > controls.lastIndex) first else at
    val index = wanted.coerceIn(-pickers.size, controls.lastIndex).let { if (it >= 0 && controls.isEmpty()) -1 else it }
    Column(Modifier.fillMaxWidth().height(BENCH_HEIGHT)) {
        LazyRow(Modifier.fillMaxWidth().height(60.dp), contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(pickers.size, key = { "picker-$it" }) { i ->
                val picker = pickers[i]
                ParamChip(picker.label, picker.value, changed = picker.changed, selected = index == -(i + 1), onClick = { onAt(-(i + 1)) })
            }
            items(controls.size, key = { controls[it].id }) { i ->
                val c = controls[i]
                val v = c.get(state.look)
                ParamChip(c.label, c.format(v), changed = v != c.default, selected = i == index, onClick = { onAt(i) })
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (index < 0) {
                pickers[-index - 1].content()
            } else {
                val c = controls[index]
                Ruler(
                    name = c.label,
                    value = c.get(state.look),
                    min = c.min, max = c.max, step = c.step, major = c.major, default = c.default,
                    label = c.format,
                    onChange = { state.change(c.set(state.look, it)) },
                    onSettled = state::commit,
                )
            }
        }
    }
}
