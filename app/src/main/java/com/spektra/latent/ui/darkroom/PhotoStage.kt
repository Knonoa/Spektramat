package com.spektra.latent.ui.darkroom

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.spektra.latent.data.Frame
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.gl.Placement
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnSize
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.GlassTone
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.GlyphVeil
import com.spektra.latent.ui.IconButton
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Opt
import com.spektra.latent.ui.Seg
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.glass
import com.spektra.latent.ui.softShadow
import kotlin.math.roundToInt

/** A fixed viewport: only its photograph scales; gestures and drawing stay inside its edges. */
@Composable
internal fun PhotoStage(
    state: DarkroomState,
    frame: Frame,
    histogram: Boolean,
    printing: Boolean,
    run: PrintRunState?,
    veil: Float,
    expanded: Boolean,
    onExpand: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val proof = state.proof
    val original = state.original
    val placement = state.placement
    val comparisonMode = state.comparisonMode
    val sourceGeometry = remember(state.proofSourceQuad) { PhotoComparison.from(state.proofSourceQuad) }
    val split = comparisonMode == ComparisonMode.Split && !printing && proof != null && original != null && sourceGeometry != null
    var choosingComparison by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .onSizeChanged { state.layout(it.width.toFloat(), it.height.toFloat()) }
            .semantics {
                contentDescription = "照片预览"
                stateDescription = "缩放 ${(placement.scale * 100).roundToInt()}%"
            }
            .pointerInput(state, printing) {
                if (!printing) detectTransformGestures { centroid, pan, zoom, _ -> state.transform(centroid, pan, zoom) }
            }
            .pointerInput(state, printing, comparisonMode) {
                if (!printing) detectTapGestures(
                    onDoubleTap = { state.toggleZoom(it) },
                    onLongPress = if (comparisonMode == ComparisonMode.Hold) ({ state.compare(true) }) else null,
                    onPress = { try { tryAwaitRelease() } finally { state.compare(false) } },
                )
            },
    ) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val shown = state.shownSize
        val fit = PhotoViewport.fit(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), shown[0], shown[1])
        val shade = Ink.PhotoShadow
        // Measure at fit size. Scaling a measured size instead would make Compose constrain
        // the zoomed image to the parent and distort both the image and its pan limits.
        Box(
            Modifier.align(Alignment.Center)
                .size(with(density) { fit.width.toDp() }, with(density) { fit.height.toDp() })
                .graphicsLayer {
                    scaleX = placement.scale
                    scaleY = placement.scale
                    translationX = placement.panX
                    translationY = placement.panY
                }
                .drawBehind {
                    val all = Rect(Offset.Zero, size)
                    softShadow(all, 0f, shade, 16.dp.toPx(), 28.dp.toPx(), -14.dp.toPx())
                },
        ) {
            when {
                state.comparing && original != null -> Image(original, "原片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                proof != null -> Image(proof, if (state.adjustmentsOnly) "调节后的原图" else "样张", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                state.adjustmentsOnly -> original?.let { Image(it, "原图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                else -> FileImage(frame.thumb, Modifier.fillMaxSize(), scale = ContentScale.Fit)
            }
            if (split) {
                OriginalComparison(
                    original!!, sourceGeometry!!,
                    fraction = state.comparisonFraction, viewportWidth = viewportWidth, placement = placement,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (printing && proof != null && run != null) {
                val gate = gateBounds(state.proofGate)
                val film = FilmEngine.catalog.film(state.look.film)
                PrintRun(
                    original, proof, gate,
                    kind = if (film?.blackAndWhite == true) "bw" else if (film?.positive == true) "pos" else if (film?.cine == true) "cine" else "neg",
                    grain = state.look.grain / 200f, halo = state.look.halation / 200f, run = run,
                    modifier = Modifier.offset(with(density) { (gate.left * fit.width).toDp() }, with(density) { (gate.top * fit.height).toDp() })
                        .size(with(density) { (gate.width * fit.width).toDp() }, with(density) { (gate.height * fit.height).toDp() }),
                )
            }
            if (histogram && placement.scale <= 1.01f) {
                val picture = if (state.comparing) original else proof
                if (picture != null) Histogram(
                    picture, state.comparing,
                    if (state.comparing) WHOLE else state.proofGate,
                    if (state.comparing) floatArrayOf() else state.proofDateBounds,
                    Modifier.fillMaxSize(),
                    adjustedSource = state.adjustmentsOnly,
                )
            }
            if (veil < 1f) GlyphVeil(veil, Modifier.fillMaxSize())
        }
        if (split) {
            ComparisonDivider(state, viewportWidth, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).glass(16.dp, GlassTone.Thin, shadow = false).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Txt("原图  ← 拖动分隔线 →  效果", Type.micro, color = Ink.GlassText)
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(4.dp).glass(20.dp, GlassTone.Thin, shadow = false)) {
            IconButton(Glyph.Close, "从工作台移除", onRemove, size = 36.dp, enabled = !printing)
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(4.dp).glass(20.dp, GlassTone.Thin, shadow = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(Glyph.Compare, "对比方式：${comparisonMode.label}", { choosingComparison = true }, size = 36.dp, enabled = !printing)
            IconButton(Glyph.Fit, "适合画框", state::fit, size = 36.dp, enabled = !printing)
            Btn(if (expanded) "显示调节" else "查看大图", onExpand, size = BtnSize.Small, enabled = !printing)
        }
    }
    Sheet(choosingComparison, { choosingComparison = false }, title = "查看原图") {
        Seg(
            ComparisonMode.entries.map { Opt(it.name, it.label) }, comparisonMode.name,
            { state.comparisonMode(ComparisonMode.valueOf(it)); choosingComparison = false },
            Modifier.fillMaxWidth(), block = true,
        )
        Txt("长按照片查看原图，松开恢复；滑动对比可拖动中间分隔线，并支持双指缩放。", Type.micro, color = Ink.GlassDim, modifier = Modifier.padding(top = 12.dp), maxLines = 3)
    }
}

/** Both sides use the same fitted print and its transform. Only the original's crop is remapped. */
@Composable
private fun OriginalComparison(
    original: ImageBitmap, geometry: PhotoComparison, fraction: Float, viewportWidth: Float,
    placement: Placement, modifier: Modifier,
) {
    val bitmap = remember(original) { original.asAndroidBitmap() }
    val source = remember(original, geometry) { geometry.sourceCorners(original.width, original.height) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val background = Ink.Bg
    Canvas(modifier) {
        val splitX = PhotoComparison.imageSplitX(fraction, viewportWidth, size.width, placement.scale, placement.panX)
        val end = splitX.coerceIn(0f, size.width)
        if (end <= 0f) return@Canvas
        val corners = geometry.printCorners(size.width, size.height)
        val path = Path().apply {
            moveTo(corners[0], corners[1])
            for (i in 1..3) lineTo(corners[i * 2], corners[i * 2 + 1])
            close()
        }
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, corners, 0, 4)) return@Canvas
        clipRect(right = end) {
            // The original has no film rebate or date. Keep its placement in
            // the common print canvas so the divider cannot shift the subject.
            drawRect(background)
            clipPath(path) {
                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(bitmap, matrix, paint) }
            }
        }
    }
}

@Composable
private fun ComparisonDivider(state: DarkroomState, viewportWidth: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val handleWidth = with(density) { 44.dp.toPx() }
    Box(modifier) {
        Box(
            Modifier.offset { IntOffset((viewportWidth * state.comparisonFraction - handleWidth / 2f).roundToInt(), 0) }
                .width(44.dp).fillMaxHeight()
                .semantics {
                    contentDescription = "原图与效果分隔线"
                    progressBarRangeInfo = ProgressBarRangeInfo(state.comparisonFraction, 0f..1f)
                    setProgress { state.comparisonFraction(it); true }
                }
                .pointerInput(state, viewportWidth) {
                    if (viewportWidth > 0f) detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        state.comparisonFraction(state.comparisonFraction + amount / viewportWidth)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                drawLine(Color.Black.copy(alpha = .5f), Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                drawLine(Ink.OnPhoto, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                val w = 30.dp.toPx()
                val h = 42.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = .68f), Offset(x - w / 2f, (size.height - h) / 2f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(15.dp.toPx()))
                val y = size.height / 2f
                val arrow = 4.dp.toPx()
                for (sign in listOf(-1f, 1f)) {
                    val tip = x + sign * 9.dp.toPx()
                    drawLine(Ink.OnPhoto, Offset(tip - sign * arrow, y - arrow), Offset(tip, y), 1.5.dp.toPx())
                    drawLine(Ink.OnPhoto, Offset(tip, y), Offset(tip - sign * arrow, y + arrow), 1.5.dp.toPx())
                }
            }
        }
    }
}

private val WHOLE = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

private fun gateBounds(gate: FloatArray): Rect {
    val xs = gate.filterIndexed { i, _ -> i % 2 == 0 }
    val ys = gate.filterIndexed { i, _ -> i % 2 == 1 }
    return Rect(xs.min().coerceIn(0f, 1f), ys.min().coerceIn(0f, 1f), xs.max().coerceIn(0f, 1f), ys.max().coerceIn(0f, 1f))
}
