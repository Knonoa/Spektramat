package com.spektra.latent.ui.darkroom

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** The three places a look can be changed, in the order light passes them. */
private enum class Stage(val title: String) { Scene("原片"), Film("胶片"), Print("印相") }

/**
 * The darkroom: the photograph above, and below it the three stages of the
 * process -- scene, film, print -- each with its own controls. One ruler at
 * the bottom sets whichever control is selected.
 */
@Composable
fun DarkroomScreen(library: Library, frameId: Long, onBack: () -> Unit, onDevelop: (Look, Int) -> Unit) {
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
            val r = state.placement.rect(view.width.toFloat(), view.height.toFloat(), frame.width, frame.height)
            AnimatedVisibility(!state.ready, enter = fadeIn(tween(0)), exit = fadeOut(tween(260))) {
                FileImage(
                    frame.thumb,
                    Modifier
                        .offset { IntOffset(r[0].roundToInt(), r[1].roundToInt()) }
                        .size(with(density) { r[2].toDp() }, with(density) { r[3].toDp() }),
                    scale = ContentScale.FillBounds,
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

        if (state.comparing) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Ink.Body.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Txt("原片", Type.caption, color = Ink.Text)
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
            Gap(width = 6.dp)
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
                Stage.Scene -> ControlBench(state, Controls.scene, sceneAt, { sceneAt = it }, picker = null)
                Stage.Film -> ControlBench(
                    state, Controls.film, filmAt, { filmAt = it },
                    picker = Picker("型号", shortName(FilmEngine.catalog.film(state.look.film)?.name ?: "")) {
                        StockStrip(
                            items = filmStocks(),
                            selected = state.look.film,
                            onPick = { state.change(state.look.withFilm(it)); state.commit() },
                        )
                    },
                )
                Stage.Print -> if (state.look.scanFilm) {
                    Box(Modifier.fillMaxWidth().height(BENCH_HEIGHT), contentAlignment = Alignment.Center) {
                        Txt("反转片不用印相：冲出来的胶片本身就是成片。", Type.label, color = Ink.Dim, maxLines = 2)
                    }
                } else {
                    ControlBench(
                        state, Controls.print, printAt, { printAt = it },
                        picker = Picker("相纸", shortName(FilmEngine.catalog.paper(state.look.paper)?.name ?: "")) {
                            StockStrip(
                                items = FilmEngine.catalog.papers.map {
                                    Stock(it.id, it.name, if (it.blackAndWhite) "黑白相纸" else if (it.cine) "电影拷贝片" else "彩色相纸")
                                },
                                selected = state.look.paper,
                                onPick = { state.change(state.look.copy(paper = it)); state.commit() },
                            )
                        },
                    )
                }
            }
        }

        // ---- ordering a print
        Sheet(ordering, { ordering = false }) {
            Txt("冲印这张照片", Type.heading)
            Gap(height = 4.dp)
            Txt("冲印在后台进行，不耽误继续调整；成片保存到相册的 Latent 相簿。尺寸是长边的像素数。", Type.label, color = Ink.Dim, maxLines = 3)
            Gap(height = 14.dp)
            val native = frame.longEdge
            val sizes = listOf(2048, 4096).filter { it < native } + native
            for (edge in sizes) {
                Row(
                    Modifier.fillMaxWidth().pressable { ordering = false; state.commit(); onDevelop(state.look, edge) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("$edge", Type.edgeLarge, Modifier.width(88.dp))
                    Txt(if (edge == native) "原尺寸" else if (edge <= 2048) "适合手机上看和分享" else "适合大屏和小幅打印", Type.label, color = Ink.Dim)
                }
            }
        }
    }
}

private val BENCH_HEIGHT = 128.dp

private class Picker(val label: String, val value: String, val content: @Composable () -> Unit)

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
 * A stage's controls: a row of chips (an optional picker first, then one chip
 * per ruler) and, under it, either the picker's strip or the ruler of the
 * selected chip. [at] is -1 for the picker.
 */
@Composable
private fun ControlBench(state: DarkroomState, controls: List<Control>, at: Int, onAt: (Int) -> Unit, picker: Picker?) {
    val index = if (picker == null) at.coerceIn(0, controls.lastIndex) else at.coerceIn(-1, controls.lastIndex)
    Column(Modifier.fillMaxWidth().height(BENCH_HEIGHT)) {
        LazyRow(Modifier.fillMaxWidth().height(60.dp), contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picker != null) {
                item(key = "picker") {
                    ParamChip(picker.label, picker.value, changed = false, selected = index == -1, onClick = { onAt(-1) })
                }
            }
            items(controls.size, key = { controls[it].id }) { i ->
                val c = controls[i]
                val v = c.get(state.look)
                ParamChip(c.label, c.format(v), changed = v != c.default, selected = i == index, onClick = { onAt(i) })
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (index == -1 && picker != null) {
                picker.content()
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
