package com.spektra.latent.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Thumbnails already decoded, by file path; a new revision of a frame has a new path. */
private val thumbs = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** A picture file from the library's own storage, decoded off the main thread and remembered. */
@Composable
fun FileImage(file: File, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    var image by remember(file.path) { mutableStateOf(thumbs.get(file.path)) }
    LaunchedEffect(file.path) {
        // A migrated exact preview may still be rendering after startup.
        // Keep this row alive until its versioned file is atomically written.
        while (!file.exists()) delay(250)
        if (image == null) {
            image = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { thumbs.put(file.path, it) }
            }
        }
    }
    val shown = image
    if (shown != null) {
        Image(bitmap = shown, contentDescription = null, modifier = modifier, contentScale = scale)
    } else {
        Box(modifier.background(Ink.Bg3))
    }
}

// ------------------------------------------------------------------ what lies over the whole app

@Stable
internal class SheetEntry {
    var visible by mutableStateOf(false)
    var title by mutableStateOf<String?>(null)
    var onDismiss: () -> Unit = {}
    var content by mutableStateOf<@Composable ColumnScope.() -> Unit>({})
    var foot by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
}

internal class ToastEntry(val id: Int, val text: String, val detail: String?) {
    var leaving by mutableStateOf(false)
}

/**
 * Sheets and toasts are drawn by the shell, above every page and outside what
 * the pages draw: that is what lets their glass look through the whole page.
 */
@Stable
class Overlays {
    internal val sheets = mutableStateListOf<SheetEntry>()
    internal val toasts = mutableStateListOf<ToastEntry>()
    private var next = 0

    /** A short message that leaves by itself. [detail] is set smaller beside it: a name, a size. */
    fun toast(text: String, detail: String? = null) {
        if (toasts.size >= 3) toasts.removeAt(0)
        toasts += ToastEntry(next++, text, detail)
    }
}

val LocalOverlays = staticCompositionLocalOf<Overlays?> { null }

/**
 * A panel that rises from the bottom over a dimmed screen. Tapping outside,
 * pressing back, the cross, or dragging its grip down dismisses it.
 *
 * @param title shown at its head beside the cross; without one the content brings its own heading
 * @param foot buttons kept at the bottom while the content scrolls
 */
@Composable
fun Sheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    foot: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalOverlays.current
    val entry = remember { SheetEntry() }
    SideEffect {
        entry.visible = visible
        entry.title = title
        entry.onDismiss = onDismiss
        entry.content = content
        entry.foot = foot
    }
    if (host != null) {
        DisposableEffect(host, entry) {
            host.sheets += entry
            onDispose { host.sheets -= entry }
        }
    } else {
        SheetLayer(entry)
    }
}

/** Draws what the pages have asked to lie over them. Called once, by the shell. */
@Composable
fun OverlayHost(overlays: Overlays, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        for (entry in overlays.sheets) key(entry) { SheetLayer(entry) }
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 78.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (toast in overlays.toasts) key(toast.id) { Toast(toast) { overlays.toasts.remove(toast) } }
        }
    }
}

@Composable
private fun SheetLayer(entry: SheetEntry) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(0) }
    var pulled by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entry.visible) {
        if (entry.visible) pulled = 0f
        shown.animateTo(
            if (entry.visible) 1f else 0f,
            tween(if (reduced) 0 else if (entry.visible) 500 else 380, easing = Motion.Ease),
        )
    }
    if (!entry.visible && shown.value == 0f) return
    BackHandler(enabled = entry.visible) { entry.onDismiss() }
    val scrim = Ink.Scrim
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * shown.value.coerceIn(0f, 1f))) }
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = entry.visible, onClick = { entry.onDismiss() }),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .navigationBarsPadding()
                .padding(8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = minOf(maxHeight * .78f, 720.dp))
                .onSizeChanged { height = it.height }
                .offset { IntOffset(0, ((1f - shown.value) * (height + 60.dp.toPx()) + pulled).roundToInt()) }
                .glass(28.dp, GlassTone.Strong)
                // the panel itself swallows taps so they do not reach the scrim
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .semantics { entry.title?.let { paneTitle = it } },
        ) {
            val line2 = Ink.Line2
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (pulled > 90.dp.toPx()) entry.onDismiss()
                                else scope.launch {
                                    val back = Animatable(pulled)
                                    back.animateTo(0f, tween(260, easing = Motion.Ease)) { pulled = value }
                                }
                            },
                        ) { change, dy ->
                            change.consume()
                            pulled = (pulled + dy).coerceAtLeast(0f)
                        }
                    }
                    .drawBehind {
                        val w = 38.dp.toPx()
                        drawRoundRect(
                            line2, Offset(center.x - w / 2, center.y - 2.dp.toPx()), androidx.compose.ui.geometry.Size(w, 4.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        )
                    },
            )
            val title = entry.title
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(title, Type.title, Modifier.weight(1f))
                    IconButton(Glyph.Close, "关闭", { entry.onDismiss() })
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = if (title == null) 2.dp else 0.dp),
                content = entry.content,
            )
            val foot = entry.foot
            if (foot != null) {
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    content = foot,
                )
            }
        }
    }
}

@Composable
private fun Toast(entry: ToastEntry, onGone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(entry) {
        shown.animateTo(1f, tween(if (reduced) 0 else 450, easing = Motion.Ease))
        delay(if (entry.text.length > 18) 3600 else 2400)
        entry.leaving = true
        shown.animateTo(0f, tween(if (reduced) 0 else 220, easing = Motion.EaseIn))
        onGone()
    }
    Row(
        Modifier
            .graphicsLayer {
                val k = shown.value
                alpha = k.coerceIn(0f, 1f)
                translationY = (if (entry.leaving) -6f else -10f) * (1f - k) * density
                val s = if (entry.leaving) .97f + .03f * k else .94f + .06f * k
                scaleX = s
                scaleY = s
            }
            .glass(21.dp, GlassTone.Strong, shadow = false)
            .defaultMinSize(minHeight = 42.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Lamp()
        Txt(entry.text, Type.body.copy(fontSize = 13.sp, lineHeight = 19.sp), Modifier.weight(1f, fill = false), color = Ink.GlassText, maxLines = 4)
        if (entry.detail != null) Txt(entry.detail, Type.numSmall, color = Ink.GlassDim)
    }
}

/**
 * A short message that leaves by itself. Kept for the screens that hold their
 * message as a state: it hands the text to the toasts and clears the state.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun Notice(text: String?, onGone: () -> Unit, modifier: Modifier = Modifier) {
    val overlays = LocalOverlays.current
    LaunchedEffect(text) {
        if (text != null) {
            overlays?.toast(text)
            onGone()
        }
    }
}
