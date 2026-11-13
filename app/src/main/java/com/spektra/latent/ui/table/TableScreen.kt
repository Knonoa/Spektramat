package com.spektra.latent.ui.table

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.spektra.latent.APP_NAME
import com.spektra.latent.Journal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.LatentApp
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.data.LightTableStore
import com.spektra.latent.data.Loupe
import com.spektra.latent.data.Placement
import com.spektra.latent.data.TableDoc
import com.spektra.latent.model.Choice
import com.spektra.latent.ui.*
import com.spektra.latent.ui.darkroom.TRAY_PAD
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.GlyphField
import com.spektra.latent.ui.GlyphSubject
import com.spektra.latent.ui.Icon
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Notice
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.SettingRow
import com.spektra.latent.ui.Sheet
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val EXPORT_RATIOS=listOf(Choice("free","外接矩形"),Choice("1:1","1:1"),Choice("4:5","4:5"),Choice("3:2","3:2"),Choice("16:9","16:9"),Choice("9:16","9:16"))
private fun exportRatio(id:String):Float? {
    val pair=id.split(':')
    if(pair.size!=2)return null
    val numerator=pair[0].toFloatOrNull()?:return null
    val denominator=pair[1].toFloatOrNull()?:return null
    return (numerator/denominator).takeIf {it.isFinite() && it>0f}
}

private val LAMPS = listOf(
    Choice("3200", "钨丝灯 3200K"), Choice("4000", "暖白 4000K"), Choice("5000", "观片标准 5000K"),
    Choice("5500", "日光 5500K"), Choice("6500", "D65 6500K"),
)

/** What a finger landed on. */
private class Target(val id: Long, val loupe: Boolean, val locked: Boolean, val handle: Int = 0, val centre: Offset = Offset.Zero)

/**
 * The light table: a lit surface without edges. Photographs from the library
 * are laid out on it, moved, turned and sized; a loupe stands on them; the
 * lamp behind can be warmer, cooler, dimmer or off. What is seen can be saved
 * as a picture.
 */
@Composable
fun TableScreen(library: Library, store: LightTableStore, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LatentApp
    val shell = LocalShell.current
    val reduced = LocalReducedMotion.current || animationsOff(context)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val prefs = remember { context.getSharedPreferences("table", Context.MODE_PRIVATE) }
    val rolls by library.rolls.collectAsStateWithLifecycle()
    val frames = remember(rolls) { rolls.flatMap { it.frames }.associateBy { it.id } }

    var doc by remember { mutableStateOf(TableDoc()) }
    var loaded by remember { mutableStateOf(false) }
    val undone = remember { ArrayDeque<TableDoc>() }
    val redone = remember { ArrayDeque<TableDoc>() }
    var history by remember { mutableIntStateOf(0) }     // changes whenever the two stacks do
    var view by remember { mutableStateOf(View()) }
    var fitted by remember { mutableStateOf(false) }
    var lamp by remember {
        mutableStateOf(Lamp(prefs.getBoolean("on", true), prefs.getFloat("kelvin", 5000f), prefs.getFloat("level", 0.9f)))
    }
    var ratio by remember { mutableStateOf(prefs.getString("exportRatio","free").takeIf { id->EXPORT_RATIOS.any{it.wire==id} }?:"free") }
    var snap by remember { mutableStateOf(prefs.getBoolean("snap", false)) }
    var selectedPhoto by remember { mutableStateOf<Long?>(null) }
    var selectedLoupe by remember { mutableStateOf<Long?>(null) }
    var delta by remember { mutableStateOf<Delta?>(null) }
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }
    var panel by remember { mutableStateOf<String?>(null) }   // "lamp" | "turn"
    var hud by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<Long>()) }
    var exportOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<Job?>(null) }
    var gliding by remember { mutableStateOf<Job?>(null) }

    // ---- the pictures: the thumbnail at once, then a larger copy as each is made
    val images = remember { mutableStateMapOf<Long, ImageBitmap>() }
    val imageOf = remember { HashMap<Long, String>() }
    val shape: Shapes = { id ->
        images[id]?.let { it.height.toFloat() / it.width }
            ?: frames[id]?.let { f -> f.look.frameSize(f.width, f.height).let { it[1].toFloat() / it[0] } }
            ?: 0.667f
    }
    LaunchedEffect(doc.placements.map { it.frameId }.toSet(), frames) {
        for (id in doc.placements.map { it.frameId }.toSet()) {
            val frame = frames[id] ?: continue
            val key = frame.thumb.path
            if (imageOf[id] == key) continue
            withContext(Dispatchers.IO) {
                if (imageOf[id] == null) BitmapFactory.decodeFile(frame.thumb.path)?.let { images[id] = it.asImageBitmap() }
                library.proxy(frame)?.let { file -> BitmapFactory.decodeFile(file.path)?.let { images[id] = it.asImageBitmap() } }
            }
            imageOf[id] = key
        }
    }

    // ---- the document
    fun keep(next: TableDoc) {
        saving?.cancel()
        saving = app.scope.launch {
            delay(500)
            store.save(next)
        }
    }

    fun commit(next: TableDoc) {
        if (next == doc) return
        undone.addLast(doc)
        if (undone.size > 100) undone.removeFirst()
        redone.clear()
        doc = next
        history++
        keep(next)
    }

    fun step(from: ArrayDeque<TableDoc>, to: ArrayDeque<TableDoc>) {
        val next = from.removeLastOrNull() ?: return
        to.addLast(doc)
        doc = next
        if (doc.placements.none { it.id == selectedPhoto }) selectedPhoto = null
        if (doc.loupes.none { it.id == selectedLoupe }) selectedLoupe = null
        history++
        keep(next)
    }

    fun select(photo: Long?, loupe: Long?) {
        selectedPhoto = photo
        selectedLoupe = loupe
        if (photo == null && panel == "turn") panel = null
    }

    LaunchedEffect(Unit) {
        doc = store.load()
        loaded = true
    }
    // a frame deleted from the library is taken off the table as well
    LaunchedEffect(loaded, frames) {
        if (loaded && rolls.isNotEmpty() || loaded && doc.placements.isNotEmpty() && library.rolls.value.isEmpty()) {
            val alive = doc.placements.filter { it.frameId in frames }
            if (alive.size != doc.placements.size) {
                doc = doc.copy(placements = alive)
                keep(doc)
            }
        }
    }

    // ---- the window onto the table
    fun fit(animate: Boolean = true) {
        val b = exportBounds(doc, shape,exportRatio(ratio))
        if (b == null || size.width == 0) {
            view = View()
            return
        }
        val padTop = 96f * density
        val padBottom = 200f * density
        val s = min((size.width - 48f * density) / b.width, (size.height - padTop - padBottom) / b.height).coerceIn(0.05f, 4f)
        val target = View(-(b.left + b.right) / 2 * s, -(b.top + b.bottom) / 2 * s + (padTop - padBottom) / 2, s)
        gliding?.cancel()
        if (!animate || reduced) {
            view = target
            return
        }
        val from = view
        gliding = scope.launch {
            val t0 = withFrameNanos { it }
            while (true) {
                val k = ((withFrameNanos { it } - t0) / 420e6f).coerceAtMost(1f)
                val e = 1f - (1f - k).pow(3)
                view = View(from.x + (target.x - from.x) * e, from.y + (target.y - from.y) * e, from.s * (target.s / from.s).pow(e))
                if (k >= 1f) break
            }
        }
    }
    LaunchedEffect(loaded, size, images.size) {
        if (loaded && !fitted && size.width > 0 && (doc.placements.isEmpty() || images.isNotEmpty())) {
            fit(false)
            fitted = true
        }
    }
    LaunchedEffect(hud) {
        if (hud != null) {
            delay(700)
            hud = null
        }
    }
    fun centre() = Offset(-view.x / view.s, -view.y / view.s)
    fun toTable(p: Offset) = Offset((p.x - size.width / 2f - view.x) / view.s, (p.y - size.height / 2f - view.y) / view.s)
    fun toScreen(p: Offset) = Offset(size.width / 2f + view.x + p.x * view.s, size.height / 2f + view.y + p.y * view.s)

    BackHandler(enabled = panel != null || selectedPhoto != null || selectedLoupe != null) {
        if (panel != null) panel = null else select(null, null)
    }

    val glass = remember { lensShader() }
    val shown = doc.with(delta)
    val scene = Scene(
        shown, view, lamp, { images[it] }, shape, marks = true,
        selectedPhoto = selectedPhoto, selectedLoupe = selectedLoupe, guides = guides,
        plainLoupes = glass == null,
    )
    // the table's lamp is this page's theme: over a lit table the glass is light, over a dark one dark
    PageScheme(light = lamp.on)

    GlassPage(
        modifier = Modifier.onSizeChanged { size = it },
        behind = {
        Box(Modifier.fillMaxSize().background(lampColour(lamp)))
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= 33 && glass != null) Modifier.lenses(glass as RuntimeShader) { scene } else Modifier)
                .pointerInput(Unit) {
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        gliding?.cancel()
                        val start = down.position
                        // what is under the finger: a handle of the selected photograph, a loupe, a photograph, or the table
                        var target: Target? = null
                        doc.placements.firstOrNull { it.id == selectedPhoto && !it.locked }?.let { p ->
                            val h = p.w * shape(p.frameId)
                            val off = 7f * density / view.s
                            val turn = toScreen(local(p, 0f, -h / 2 - off - HANDLE_STEM * density / view.s))
                            val grow = toScreen(local(p, p.w / 2 + off, h / 2 + off))
                            val middle = toScreen(Offset(p.cx, p.cy))
                            if ((start - turn).getDistance() < 26f * density) target = Target(p.id, false, false, 1, middle)
                            else if ((start - grow).getDistance() < 26f * density) target = Target(p.id, false, false, 2, middle)
                        }
                        if (target == null) {
                            val at = toTable(start)
                            val l = hitLoupe(doc, at.x, at.y)
                            val p = if (l == null) hitPhoto(doc, shape, at.x, at.y) else null
                            target = if (l != null) Target(l.id, true, false) else if (p != null) Target(p.id, false, p.locked) else null
                        }
                        val t = target
                        var moved = false
                        var raw = Delta(t?.id ?: 0L, t?.loupe ?: false)
                        var pinch: FloatArray? = null      // distance, angle, centroid x, y at the moment the second finger came down
                        var pinchRaw = raw
                        var pinchView = view
                        var velocity = Offset.Zero
                        var lastAngle = Float.NaN
                        var caught: String? = null

                        fun publish(d: Delta) {
                            if (t == null) return
                            if (snap && t.handle == 0) {
                                val (settled, lines) = magnet(doc, shape, d, view)
                                val now = if (settled.dx != d.dx || settled.dy != d.dy) "${settled.dx.roundToInt()},${settled.dy.roundToInt()}" else null
                                if (now != null && now != caught) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                caught = now
                                delta = settled
                                guides = lines
                            } else {
                                delta = d
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size == 1) {
                                pinch = null
                                val c = pressed[0]
                                val step = c.position - c.previousPosition
                                if (!moved && (c.position - start).getDistance() < 5f * density) continue
                                moved = true
                                velocity = velocity * 0.7f + step * 0.3f
                                if (t != null && t.handle != 0) {
                                    val p = doc.placements.firstOrNull { it.id == t.id } ?: continue
                                    if (t.handle == 1) {
                                        val a0 = atan2(start.y - t.centre.y, start.x - t.centre.x)
                                        val a1 = atan2(c.position.y - t.centre.y, c.position.x - t.centre.x)
                                        val to = snapAngle(p.rot + (a1 - a0) * 180f / Math.PI.toFloat())
                                        if (to % ANGLE_SNAP == 0f && to != lastAngle) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        lastAngle = to
                                        raw = raw.copy(rotation = to - p.rot)
                                        hud = "${normAngle(to).roundToInt()}°"
                                    } else {
                                        val f = (c.position - t.centre).getDistance() / max(8f, (start - t.centre).getDistance())
                                        raw = raw.copy(factor = f)
                                        hud = "${(f * 100).roundToInt()}%"
                                    }
                                    publish(raw)
                                } else if (t != null && !t.locked) {
                                    raw = raw.copy(dx = raw.dx + step.x / view.s, dy = raw.dy + step.y / view.s)
                                    publish(raw)
                                } else {
                                    view = view.copy(x = view.x + step.x, y = view.y + step.y)
                                }
                            } else {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val distance = (a - b).getDistance().coerceAtLeast(1f)
                                val angle = atan2(b.y - a.y, b.x - a.x)
                                val mid = (a + b) / 2f
                                val p0 = pinch ?: floatArrayOf(distance, angle, mid.x, mid.y).also {
                                    pinch = it
                                    pinchRaw = raw
                                    pinchView = view
                                    moved = true
                                }
                                if (t != null && !t.locked && t.handle == 0) {
                                    // two fingers on a photograph size and turn it; on a loupe they size it
                                    var rotation = if (t.loupe) 0f else pinchRaw.rotation + (angle - p0[1]) * 180f / Math.PI.toFloat()
                                    doc.placements.firstOrNull { !t.loupe && it.id == t.id }?.let { p ->
                                        val to = snapAngle(p.rot + rotation)
                                        rotation = to - p.rot
                                        hud = "${normAngle(to).roundToInt()}°"
                                    }
                                    raw = pinchRaw.copy(
                                        factor = pinchRaw.factor * distance / p0[0], rotation = rotation,
                                        dx = pinchRaw.dx + (mid.x - p0[2]) / view.s, dy = pinchRaw.dy + (mid.y - p0[3]) / view.s,
                                    )
                                    publish(raw)
                                } else if (t == null || t.locked) {
                                    // two fingers on the table move closer or further, about the point between them
                                    val s = (pinchView.s * distance / p0[0]).coerceIn(0.03f, 6f)
                                    val k = s / pinchView.s
                                    val ox = p0[2] - size.width / 2f
                                    val oy = p0[3] - size.height / 2f
                                    view = View(ox - (ox - pinchView.x) * k + (mid.x - p0[2]), oy - (oy - pinchView.y) * k + (mid.y - p0[3]), s)
                                    hud = "${(s * 100).roundToInt()}%"
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }

                        guides = emptyList()
                        if (!moved) {
                            if (t != null && t.handle != 0) return@awaitEachGesture
                            val now = System.currentTimeMillis()
                            if (t == null && now - lastTap < 300) {
                                lastTap = 0
                                fit()
                                return@awaitEachGesture
                            }
                            lastTap = now
                            if (t == null) select(null, null) else if (t.loupe) select(null, t.id) else select(t.id, null)
                            return@awaitEachGesture
                        }
                        val d = delta
                        delta = null
                        if (t != null && !t.locked && d != null) {
                            if (t.loupe) select(null, t.id) else select(t.id, null)
                            commit(doc.with(d))
                        } else if (t == null && velocity.getDistance() > 2f) {
                            // let go while moving, the table coasts a little
                            var v = Offset(velocity.x.coerceIn(-40f * density, 40f * density), velocity.y.coerceIn(-40f * density, 40f * density))
                            gliding = scope.launch {
                                while (v.getDistance() > 0.4f) {
                                    withFrameNanos { }
                                    v *= 0.92f
                                    view = view.copy(x = view.x + v.x, y = view.y + v.y)
                                }
                            }
                        }
                    }
                },
        ) {
            drawTable(scene)
        }
        exportBounds(shown,shape,exportRatio(ratio))?.let { region ->
            val start=toScreen(region.topLeft)
            val width=region.width*view.s
            val height=region.height*view.s
            Canvas(Modifier.fillMaxSize()) {
                val stroke=if(lamp.on)Color.Black.copy(alpha=.35f) else Color.White.copy(alpha=.5f)
                drawRect(stroke,start,Size(width,height),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(),5.dp.toPx()))))
            }
            Txt("导出范围 · ${EXPORT_RATIOS.first{it.wire==ratio}.label}",Type.micro,
                Modifier.offset { IntOffset(start.x.roundToInt(),(start.y-21*density).roundToInt()) },
                color=if(lamp.on)Color.Black.copy(alpha=.55f) else Color.White.copy(alpha=.7f))
        }

        // ---- nothing on the table yet
        if (loaded && doc.placements.isEmpty()) {
            val grain = if (lamp.on) Color.Black.copy(alpha = .5f) else Color.White.copy(alpha = .5f)
            GlyphField(GlyphSubject.Room, Modifier.fillMaxSize(), noise = .14f, ink = grain, hot = grain)
        }
        },
        chrome = {
        val onTable = if (lamp.on) Color(0xFF17150F) else Ink.OnPhoto
        if (loaded && doc.placements.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(start = 40.dp + shell.insetLeft, end = 40.dp, bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt("一张没有边的观片台", Type.h1, color = onTable)
                Gap(height = 10.dp)
                Txt(
                    "把片库里的照片摊开来看，\n拖动、放大、转个角度；\n再放一枚放大镜，透过玻璃看细节。", Type.body.copy(lineHeight = 24.5.sp),
                    color = onTable.copy(alpha = .7f), maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Gap(height = 22.dp)
                Btn("放照片上来", { picked = emptySet(); picking = true }, kind = BtnKind.Key, icon = Glyph.Plus, enabled = frames.isNotEmpty())
                if (frames.isEmpty()) {
                    Gap(height = 10.dp)
                    Txt("片库里还没有照片。", Type.micro, color = onTable.copy(alpha = .6f))
                }
            }
        }

        // ---- top: where this is, undo, saving a picture of it
        TopBar(
            title = "观片台",
            sub = if (doc.placements.isEmpty()) "" else "${doc.placements.size} 张在台上",
            actions = {
                history.let { IconButton(Glyph.Undo, "撤销", { step(undone, redone) }, enabled = undone.isNotEmpty()) }
                history.let { IconButton(Glyph.Redo, "重做", { step(redone, undone) }, enabled = redone.isNotEmpty()) }
                BarSep()
                IconButton(Glyph.Share, if (exporting) "正在导出" else "导出", { exportOpen = true }, enabled = !exporting && doc.placements.isNotEmpty())
            },
        )
        hud?.let {
            Box(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = shell.insetLeft, top = GAP + 54.dp + 10.dp)
                    .glass(15.dp, GlassTone.Thin, shadow = false).padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Txt(it, Type.num, color = Ink.GlassText) }
        }

        // ---- bottom: what can be done with the thing that is chosen, a panel for what is being set, and the tools
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = GAP + shell.insetLeft, end = GAP, bottom = GAP + bottomInset()).widthIn(max = 520.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val photo = doc.placements.firstOrNull { it.id == selectedPhoto }
            val loupe = doc.loupes.firstOrNull { it.id == selectedLoupe }
            if (photo != null || loupe != null) {
                Row(Modifier.glass(27.dp, shadow = false).height(54.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (photo != null) {
                        IconButton(Glyph.Front, "置顶", {
                            commit(doc.copy(placements = doc.placements.map { if (it.id == photo.id) it.copy(z = (doc.placements.maxOf { p -> p.z }) + 1) else it }))
                        })
                        IconButton(Glyph.Lock, if (photo.locked) "解锁" else "锁定", {
                            commit(doc.copy(placements = doc.placements.map { if (it.id == photo.id) it.copy(locked = !it.locked) else it }))
                        }, on = photo.locked)
                        IconButton(Glyph.Turn, "旋转", { panel = if (panel == "turn") null else "turn" }, enabled = !photo.locked, on = panel == "turn")
                        IconButton(Glyph.Darkroom, "去冲印", { onOpen(photo.frameId) })
                        BarSep()
                        IconButton(Glyph.Trash, "从台上拿走", {
                            select(null, null)
                            commit(doc.copy(placements = doc.placements.filter { it.id != photo.id }))
                        }, tint = Ink.Rec)
                    } else if (loupe != null) {
                        IconButton(Glyph.Trash, "拿走放大镜", {
                            select(null, null)
                            commit(doc.copy(loupes = doc.loupes.filter { it.id != loupe.id }))
                        }, tint = Ink.Rec)
                        IconButton(Glyph.Check, "好了", { select(null, null) })
                    }
                }
            }
            when {
                panel == "lamp" -> Panel {
                    Ctl("背光", note = if (lamp.on) "照片被灯从背后照亮。" else "关了灯，底片只映着屋里的暗光。", trailing = {
                        Toggle(lamp.on, { lamp = lamp.copy(on = it); prefs.edit().putBoolean("on", it).apply() }, "背光")
                    })
                    Ctl("色温", value = "${lamp.kelvin.roundToInt()}K", valueSet = lamp.kelvin != 5000f) {
                        Chips(LAMPS.opts(), lamp.kelvin.roundToInt().toString(), {
                            lamp = lamp.copy(kelvin = it.toFloat())
                            prefs.edit().putFloat("kelvin", lamp.kelvin).apply()
                        }, bleed = TRAY_PAD, height = 32.dp)
                        Gap(height = 8.dp)
                        Ruler("色温", lamp.kelvin, 2700f, 9300f, 100f, 5000f, { lamp = lamp.copy(kelvin = it) }, { prefs.edit().putFloat("kelvin", lamp.kelvin).apply() }, origin = 5000f)
                    }
                    Ctl("亮度", value = "${(lamp.level * 100f).roundToInt()}%", valueSet = (lamp.level * 100f).roundToInt() != 90) {
                        Ruler("亮度", lamp.level * 100f, 20f, 100f, 1f, 90f, { lamp = lamp.copy(level = it / 100f) }, { prefs.edit().putFloat("level", lamp.level).apply() }, sticky = false)
                    }
                }
                panel == "turn" && photo != null -> Panel {
                    val shownRot = normAngle((doc.with(delta).placements.firstOrNull { it.id == photo.id } ?: photo).rot)
                    Ctl("角度", value = (if (shownRot > 0) "+" else "") + "${shownRot.roundToInt()}°", valueSet = shownRot != 0f) {
                        Ruler(
                            "角度", shownRot, -180f, 180f, 1f, 0f,
                            { delta = Delta(photo.id, false, rotation = snapAngle(it) - photo.rot) },
                            { delta?.let { d -> delta = null; commit(doc.with(d)) } },
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            fun turnTo(deg: Float) = commit(doc.copy(placements = doc.placements.map { if (it.id == photo.id) it.copy(rot = normAngle(deg)) else it }))
                            Btn("左转 90°", { turnTo((kotlin.math.ceil(shownRot / 90f) - 1f) * 90f) }, size = BtnSize.Small)
                            Btn("摆正", { turnTo(0f) }, size = BtnSize.Small, enabled = shownRot != 0f)
                            Btn("右转 90°", { turnTo((kotlin.math.floor(shownRot / 90f) + 1f) * 90f) }, size = BtnSize.Small)
                        }
                    }
                }
                loupe != null -> Panel {
                    Ctl("放大倍率", value = String.format(java.util.Locale.US, "%.1f×", loupe.zoom), valueSet = loupe.zoom != 2.5f) {
                        Ruler(
                            "放大倍率", loupe.zoom, 1.2f, 12f, .1f, 2.5f,
                            { z -> doc = doc.copy(loupes = doc.loupes.map { if (it.id == loupe.id) it.copy(zoom = z) else it }) }, { keep(doc) }, sticky = false,
                        )
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().glass(28.dp).pointerInput(Unit) { }.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // what an export would contain is drawn on the table as a dashed frame; this sets its shape
                Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt("导出比例", Type.label.copy(fontSize = 12.sp), color = Ink.GlassDim)
                    Chips(EXPORT_RATIOS.opts(), ratio, { ratio = it; prefs.edit().putString("exportRatio", it).apply(); fit() }, Modifier.weight(1f), height = 32.dp)
                }
                Row(Modifier.fillMaxWidth()) {
                    Tool(Glyph.Lamp, "背光", on = lamp.on) { panel = if (panel == "lamp") null else "lamp" }
                    Tool(Glyph.Magnet, "吸附", on = snap) {
                        snap = !snap
                        prefs.edit().putBoolean("snap", snap).apply()
                        notice = if (snap) "吸附开了：拖动的照片会和别的照片对齐，或者落在网格上。" else "吸附关了。"
                    }
                    Tool(Glyph.Arrange, "整理", enabled = doc.placements.isNotEmpty()) {
                        commit(arranged(doc, shape))
                        fit()
                    }
                    Tool(Glyph.Fit, "看全部", enabled = doc.placements.isNotEmpty()) { fit() }
                    Tool(Glyph.Loupe, "放大镜", enabled = doc.placements.isNotEmpty() && doc.loupes.size < 6) {
                        val c = centre()
                        val id = doc.nextId
                        commit(doc.copy(loupes = doc.loupes + Loupe(id, c.x, c.y, size = 190f * density / view.s)))
                        select(null, id)
                    }
                    Tool(Glyph.Plus, "放照片", enabled = frames.isNotEmpty()) { picked = emptySet(); picking = true }
                }
            }
        }
        },
    )
    Notice(notice, { notice = null })

    // ---- choosing photographs from the library
    Sheet(
        picking, { picking = false }, title = "放照片到台上",
        foot = {
            Btn(if (picked.isEmpty()) "选几张照片" else "放上 ${picked.size} 张", {
                picking = false
                val chosen = rolls.flatMap { it.frames }.filter { it.id in picked }
                commit(placed(doc, chosen, centre(), view))
                fitted = true
                scope.launch { delay(60); fit() }
            }, Modifier.weight(1f), BtnKind.Key, enabled = picked.isNotEmpty())
        },
    ) {
        Txt("观片台上的是片库里的同一张底片：在暗房里改了它，这里跟着变。", Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 2)
        Gap(height = 12.dp)
        LazyVerticalGrid(
            GridCells.Adaptive(88.dp), Modifier.fillMaxWidth().heightIn(max = 340.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(rolls.flatMap { it.frames }.sortedByDescending { it.taken }, key = { it.id }) { f ->
                val on = f.id in picked
                Box(
                    Modifier.aspectRatio(1f)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { picked = if (on) picked - f.id else picked + f.id }
                        .border(2.dp, if (on) Ink.Amber else Color.Transparent),
                ) {
                    FileImage(f.thumb, Modifier.fillMaxSize().padding(if (on) 2.dp else 0.dp))
                }
            }
        }
    }

    // ---- saving a picture of the table
    Sheet(exportOpen, { exportOpen = false }, title = "导出") {
        Txt("存下来的和看到的一样，带着灯光和色温；不带网格点和选择框。保存到相册的 ${APP_NAME} 相簿。", Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 3)
        Gap(height = 14.dp)
        fun save(all: Boolean) {
            exportOpen = false
            exporting = true
            val d = doc
            val v = view
            val l = lamp
            val window = size
            val aspect = exportRatio(ratio)
            app.scope.launch {
                notice = try {
                    val (w, h) = export(context, library, d, frames, shape, l, v, window, all, glass != null, aspect)
                    "已保存到相册：$w × $h"
                } catch (e: OutOfMemoryError) {
                    "内存不够导出这么大的一张，换「只导出现在看到的窗口」再试。"
                } catch (e: Exception) {
                    "没有导出：${e.message ?: "出了点问题"}"
                }
                exporting = false
            }
        }
        Btn("导出虚线框内的全部照片", { save(true) }, Modifier.fillMaxWidth(), BtnKind.Key)
        Gap(height = 4.dp)
        Txt("所有照片 · ${EXPORT_RATIOS.first { it.wire == ratio }.label}", Type.micro, Modifier.fillMaxWidth(), color = Ink.GlassDim, align = androidx.compose.ui.text.style.TextAlign.Center)
        Gap(height = 12.dp)
        Btn("只导出现在看到的窗口", { save(false) }, Modifier.fillMaxWidth())
    }
}

/** New photographs in a row across the middle of the window, each a comfortable size on screen. */
private fun placed(doc: TableDoc, frames: List<Frame>, centre: Offset, view: View): TableDoc {
    if (frames.isEmpty()) return doc
    val w = 600f
    val gap = 50f
    var id = doc.nextId
    var z = (doc.placements.maxOfOrNull { it.z } ?: 0) + 1
    val total = frames.size * w + (frames.size - 1) * gap
    var x = centre.x - total / 2 + w / 2
    val added = frames.map { f ->
        Placement(id++, f.id, x, centre.y, w, 0f, z++).also { x += w + gap }
    }
    return doc.copy(placements = doc.placements + added)
}

/** One of the table's tools: an icon over its name; lit when what it switches is on. */
@Composable
private fun RowScope.Tool(glyph: Glyph, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).pressable(enabled, sink = .94f, onClick = onClick).clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = label }.padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(glyph, tint = if (on) Ink.Accent else Ink.GlassText, size = 21.dp)
        Txt(label, Type.micro.copy(lineHeight = 14.sp), color = if (on) Ink.Accent else Ink.GlassDim)
    }
}

/** A plate of glass over the tools, holding the settings of the thing being set. */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(24.dp, GlassTone.Strong).pointerInput(Unit) { }.padding(horizontal = TRAY_PAD, vertical = 4.dp)) {
        CtlColumn(Modifier.fillMaxWidth(), content)
    }
}

/** The loupes as glass: the whole table is drawn into a layer and the lens shader bends what it shows. */
@RequiresApi(33)
private fun Modifier.lenses(shader: RuntimeShader, scene: () -> Scene): Modifier =
    graphicsLayer {
        val s = scene()
        val lenses = s.lenses(Size(size.width, size.height)).take(6)
        clip = true
        if (lenses.isEmpty()) {
            renderEffect = null
            return@graphicsLayer
        }
        val packed = FloatArray(24)
        lenses.forEachIndexed { i, l -> l.copyInto(packed, i * 4) }
        val room = lampColour(s.lamp)
        shader.setFloatUniform("uRes", size.width, size.height)
        shader.setFloatUniform("uCount", lenses.size.toFloat())
        shader.setFloatUniform("uLens", packed)
        shader.setFloatUniform("uRoom", room.red, room.green, room.blue)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "uBoard").asComposeRenderEffect()
    }

/** The glass shader, or null where it cannot be had: before Android 13, or on a graphics driver that will not take it. */
private fun lensShader(): Any? {
    if (Build.VERSION.SDK_INT < 33) return null
    return try {
        RuntimeShader(LENS_SHADER)
    } catch (e: Exception) {
        Journal.w("latent-table", "the loupe is drawn plain: the glass shader was refused", e)
        null
    }
}

/**
 * Paints the table into a picture and saves it to the gallery. [all]: every
 * photograph with a margin; otherwise what the window shows. Each photograph
 * is read again at the size it will have in the picture, never enlarged from
 * the copy on screen. Returns the picture's width and height.
 */
private suspend fun export(
    context: Context, library: Library, doc: TableDoc, frames: Map<Long, Frame>, shape: Shapes,
    lamp: Lamp, view: View, window: IntSize, all: Boolean, glass: Boolean, ratio:Float?,
): Pair<Int, Int> = withContext(Dispatchers.Default) {
    val x0: Float
    val y0: Float
    val x1: Float
    val y1: Float
    if (all) {
        val b=exportBounds(doc,shape,ratio)?:throw IOException("观片台是空的")
        x0=b.left;y0=b.top;x1=b.right;y1=b.bottom
    } else {
        x0 = (-window.width / 2f - view.x) / view.s; x1 = (window.width / 2f - view.x) / view.s
        y0 = (-window.height / 2f - view.y) / view.s; y1 = (window.height / 2f - view.y) / view.s
    }
    val requested=context.getSharedPreferences("appearance",Context.MODE_PRIVATE).getInt("exportEdge",3072)
    val longEdge=if(all)requested else min(requested,max(window.width,window.height)*2)
    val k = longEdge / max(x1 - x0, y1 - y0)
    val w = max(1, ((x1 - x0) * k).roundToInt())
    val h = max(1, ((y1 - y0) * k).roundToInt())
    // each photograph at the size it will occupy
    val wanted = HashMap<Long, Int>()
    for (p in doc.placements) {
        val need = max(p.w, p.w * shape(p.frameId)) * k * 1.1f
        val size = listOf(Library.PROXY_EDGE, 3072, 4096).firstOrNull { it >= need } ?: 4096
        wanted[p.frameId] = max(wanted[p.frameId] ?: 0, size)
    }
    val pictures = HashMap<Long, ImageBitmap>()
    for ((id, edge) in wanted) {
        val frame = frames[id] ?: continue
        val file = library.proxy(frame, edge) ?: throw IOException("「${frame.name}」读不出来，没有导出")
        pictures[id] = (BitmapFactory.decodeFile(file.path) ?: throw IOException("「${frame.name}」读不出来，没有导出")).asImageBitmap()
    }
    val bitmap = ImageBitmap(w, h)
    // the loupes are saved the way the screen shows them: as glass where the screen has glass
    val scene = Scene(
        doc, View(-(x0 + x1) / 2 * k, -(y0 + y1) / 2 * k, k), lamp, { pictures[it] }, shape,
        marks = false, plainLoupes = !glass,
    )
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(bitmap), Size(w.toFloat(), h.toFloat())) { drawTable(scene) }
    if (glass) glassLoupes(bitmap.asAndroidBitmap(), scene.lenses(Size(w.toFloat(), h.toFloat())), lampColour(lamp))
    save(context, bitmap.asAndroidBitmap())
    w to h
}

private fun save(context: Context, bitmap: Bitmap) {
    val name = "LightTable_${System.currentTimeMillis()}.jpg"
    if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/${APP_NAME}")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("相册没有接受这张图")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("写不进相册")).use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) throw IOException("图片编码失败")
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    } else {
        val dir = java.io.File(context.filesDir, "prints").apply { mkdirs() }
        java.io.File(dir, name).outputStream().use {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) throw IOException("图片编码失败")
        }
    }
}
