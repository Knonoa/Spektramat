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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektra.latent.LatentApp
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Library
import com.spektra.latent.data.LightTableStore
import com.spektra.latent.data.Loupe
import com.spektra.latent.data.Placement
import com.spektra.latent.data.TableDoc
import com.spektra.latent.model.Choice
import com.spektra.latent.ui.ChoiceStrip
import com.spektra.latent.ui.FileImage
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.GlyphField
import com.spektra.latent.ui.GlyphSubject
import com.spektra.latent.ui.Icon
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Notice
import com.spektra.latent.ui.Pill
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
fun TableScreen(library: Library, store: LightTableStore, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LatentApp
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
        val b = bounds(doc, shape)
        if (b == null || size.width == 0) {
            view = View()
            return
        }
        val padTop = 96f * density
        val padBottom = 150f * density
        val s = min((size.width - 48f * density) / b.width, (size.height - padTop - padBottom) / b.height).coerceIn(0.05f, 4f)
        val target = View(-(b.left + b.right) / 2 * s, -(b.top + b.bottom) / 2 * s + (padTop - padBottom) / 2, s)
        gliding?.cancel()
        if (!animate) {
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
    // a lit table is a bright screen: the clock and the battery are drawn dark over it, and light again on leaving
    DisposableEffect(lamp.on) {
        val window = (context as? android.app.Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.isAppearanceLightStatusBars = lamp.on
        onDispose { bars?.isAppearanceLightStatusBars = false }
    }

    Box(Modifier.fillMaxSize().background(lampColour(lamp)).onSizeChanged { size = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= 33 && glass != null) Modifier.glass(glass as RuntimeShader) { scene } else Modifier)
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

        // ---- nothing on the table yet
        if (loaded && doc.placements.isEmpty()) {
            GlyphField(
                GlyphSubject.Room, Modifier.fillMaxSize(), strength = 0.55f,
                ink = Color.Black.copy(alpha = 0.26f), faint = Color.Black.copy(alpha = 0.12f), spark = Color.Black.copy(alpha = 0.5f),
            )
            Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val ink = if (lamp.on) Color(0xFF121316) else Ink.Text
                Txt("一张没有边的观片台", Type.heading, color = ink)
                Gap(height = 8.dp)
                Txt(
                    "把片库里的照片摊开来看，\n拖动、放大、转个角度；\n再放一枚放大镜，透过玻璃看细节。", Type.label,
                    color = ink.copy(alpha = 0.7f), maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Gap(height = 22.dp)
                Pill("放照片上来", { picked = emptySet(); picking = true }, filled = true, enabled = frames.isNotEmpty())
                if (frames.isEmpty()) {
                    Gap(height = 8.dp)
                    Txt("片库里还没有照片。", Type.caption, color = ink.copy(alpha = 0.6f))
                }
            }
        }

        // ---- top: the way out, what lies here, the lamp, undo, saving a picture of it
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Knob(Glyph.Back, "回到冲印室", true, onClick = onBack)
            Box(Modifier.clip(RoundedCornerShape(50)).background(GLASS).padding(horizontal = 14.dp, vertical = 9.dp)) {
                Txt(if (doc.placements.isEmpty()) "观片台" else "${doc.placements.size} 张照片", Type.label)
            }
            Box(Modifier.weight(1f))
            Knob(Glyph.Lamp, if (lamp.on) "关灯" else "开灯", true, lit = lamp.on) {
                lamp = lamp.copy(on = !lamp.on)
                prefs.edit().putBoolean("on", lamp.on).apply()
            }
            history.let { Knob(Glyph.Undo, "撤销", undone.isNotEmpty()) { step(undone, redone) } }
            history.let { Knob(Glyph.Redo, "重做", redone.isNotEmpty()) { step(redone, undone) } }
            Box(
                Modifier.pressable(enabled = !exporting && doc.placements.isNotEmpty()) { exportOpen = true }
                    .clip(RoundedCornerShape(50)).background(Ink.Amber).padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Txt(if (exporting) "正在导出" else "导出", Type.label, color = Color.Black)
            }
        }
        hud?.let {
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).clip(RoundedCornerShape(50)).background(GLASS).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Txt(it, Type.edge)
            }
        }

        // ---- bottom: a panel for the thing being set, and the tools
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
            val photo = doc.placements.firstOrNull { it.id == selectedPhoto }
            val loupe = doc.loupes.firstOrNull { it.id == selectedLoupe }
            when {
                panel == "lamp" -> Panel {
                    SettingRow("背光", if (lamp.on) "照片被灯从背后照亮。" else "关了灯，底片只映着屋里的暗光。") {
                        Toggle(lamp.on, { lamp = lamp.copy(on = it); prefs.edit().putBoolean("on", it).apply() }, "背光")
                    }
                    ChoiceStrip(LAMPS, lamp.kelvin.roundToInt().toString(), {
                        lamp = lamp.copy(kelvin = it.toFloat())
                        prefs.edit().putFloat("kelvin", lamp.kelvin).apply()
                    }, Modifier.padding(vertical = 4.dp))
                    Ruler(
                        name = "色温", value = lamp.kelvin, min = 2700f, max = 9300f, step = 100f, major = 1000f, default = 5000f,
                        label = { "${it.roundToInt()}K" },
                        onChange = { lamp = lamp.copy(kelvin = it) }, onSettled = { prefs.edit().putFloat("kelvin", lamp.kelvin).apply() },
                    )
                    Ruler(
                        name = "亮度", value = lamp.level * 100f, min = 20f, max = 100f, step = 1f, major = 10f, default = 90f,
                        label = { "${it.roundToInt()}%" },
                        onChange = { lamp = lamp.copy(level = it / 100f) }, onSettled = { prefs.edit().putFloat("level", lamp.level).apply() },
                    )
                }
                panel == "turn" && photo != null -> Panel {
                    val shownRot = normAngle((doc.with(delta).placements.firstOrNull { it.id == photo.id } ?: photo).rot)
                    Ruler(
                        name = "角度", value = shownRot, min = -180f, max = 180f, step = 1f, major = 15f, default = 0f,
                        label = { (if (it > 0) "+" else "") + "${it.roundToInt()}°" },
                        onChange = { delta = Delta(photo.id, false, rotation = snapAngle(it) - photo.rot) },
                        onSettled = { delta?.let { d -> delta = null; commit(doc.with(d)) } },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        fun turnTo(deg: Float) = commit(doc.copy(placements = doc.placements.map { if (it.id == photo.id) it.copy(rot = normAngle(deg)) else it }))
                        Pill("左转 90°", { turnTo((kotlin.math.ceil(shownRot / 90f) - 1f) * 90f) })
                        Pill("摆正", { turnTo(0f) }, enabled = shownRot != 0f)
                        Pill("右转 90°", { turnTo((kotlin.math.floor(shownRot / 90f) + 1f) * 90f) })
                    }
                }
                loupe != null -> Panel {
                    Ruler(
                        name = "放大倍率", value = loupe.zoom, min = 1.2f, max = 12f, step = 0.1f, major = 1f, default = 2.5f,
                        label = { String.format(java.util.Locale.US, "%.1f×", it) },
                        onChange = { z -> doc = doc.copy(loupes = doc.loupes.map { if (it.id == loupe.id) it.copy(zoom = z) else it }) },
                        onSettled = { keep(doc) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(GLASS).pointerInput(Unit) { }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                when {
                    photo != null -> {
                        Tool(Glyph.Turn, "旋转", on = panel == "turn", enabled = !photo.locked) { panel = if (panel == "turn") null else "turn" }
                        Tool(Glyph.Front, "置顶") {
                            commit(doc.copy(placements = doc.placements.map { if (it.id == photo.id) it.copy(z = (doc.placements.maxOf { p -> p.z }) + 1) else it }))
                        }
                        Tool(Glyph.Lock, if (photo.locked) "已锁定" else "锁定", on = photo.locked) {
                            commit(doc.copy(placements = doc.placements.map { if (it.id == photo.id) it.copy(locked = !it.locked) else it }))
                        }
                        Tool(Glyph.Film, "冲洗") { onOpen(photo.frameId) }
                        Tool(Glyph.Trash, "拿走", danger = true) {
                            select(null, null)
                            commit(doc.copy(placements = doc.placements.filter { it.id != photo.id }))
                        }
                    }
                    loupe != null -> {
                        Tool(Glyph.Trash, "拿走", danger = true) {
                            select(null, null)
                            commit(doc.copy(loupes = doc.loupes.filter { it.id != loupe.id }))
                        }
                        Tool(Glyph.Check, "好了") { select(null, null) }
                    }
                    else -> {
                        Tool(Glyph.Plus, "照片", enabled = frames.isNotEmpty()) { picked = emptySet(); picking = true }
                        Tool(Glyph.Loupe, "放大镜", enabled = doc.placements.isNotEmpty() && doc.loupes.size < 6) {
                            val c = centre()
                            val id = doc.nextId
                            commit(doc.copy(loupes = doc.loupes + Loupe(id, c.x, c.y, size = 190f * density / view.s)))
                            select(null, id)
                        }
                        Tool(Glyph.Lamp, "灯光", on = panel == "lamp") { panel = if (panel == "lamp") null else "lamp" }
                        Tool(Glyph.Magnet, "吸附", on = snap) {
                            snap = !snap
                            prefs.edit().putBoolean("snap", snap).apply()
                            notice = if (snap) "吸附开了：拖动的照片会和别的照片对齐，或者落在网格上。" else "吸附关了。"
                        }
                        Tool(Glyph.Arrange, "整理", enabled = doc.placements.isNotEmpty()) {
                            commit(arranged(doc, shape))
                            fit()
                        }
                        Tool(Glyph.Fit, "全部", enabled = doc.placements.isNotEmpty()) { fit() }
                    }
                }
            }
        }

        Notice(notice, { notice = null }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 110.dp))

        // ---- choosing photographs from the library
        Sheet(picking, { picking = false }) {
            Txt("放哪些照片上来", Type.heading)
            Gap(height = 4.dp)
            Txt("观片台上的是片库里的同一张底片：在暗房里改了它，这里跟着变。", Type.label, color = Ink.Dim, maxLines = 2)
            Gap(height = 12.dp)
            LazyVerticalGrid(
                GridCells.Adaptive(76.dp), Modifier.fillMaxWidth().heightIn(max = 340.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(rolls.flatMap { it.frames }, key = { it.id }) { f ->
                    val on = f.id in picked
                    Box(
                        Modifier.aspectRatio(1f).pressable { picked = if (on) picked - f.id else picked + f.id }
                            .border(2.dp, if (on) Ink.Amber else Color.Transparent, RoundedCornerShape(3.dp)),
                    ) {
                        FileImage(f.thumb, Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)))
                    }
                }
            }
            Gap(height = 12.dp)
            Pill(if (picked.isEmpty()) "选几张照片" else "放上 ${picked.size} 张", {
                picking = false
                val chosen = rolls.flatMap { it.frames }.filter { it.id in picked }
                commit(placed(doc, chosen, centre(), view))
                fitted = true
                scope.launch { delay(60); fit() }
            }, Modifier.fillMaxWidth(), filled = true, enabled = picked.isNotEmpty())
        }

        // ---- saving a picture of the table
        Sheet(exportOpen, { exportOpen = false }) {
            Txt("把观片台存成一张图", Type.heading)
            Gap(height = 4.dp)
            Txt("存下来的和看到的一样，带着灯光和色温；不带网格点和选择框。保存到相册的 ${APP_NAME} 相簿。", Type.label, color = Ink.Dim, maxLines = 3)
            Gap(height = 12.dp)
            for ((all, title, note) in listOf(Triple(false, "当前窗口", "屏幕上现在看到的这一块"), Triple(true, "全部照片", "台上所有的照片，四周留一点边"))) {
                Column(
                    Modifier.fillMaxWidth().pressable {
                        exportOpen = false
                        exporting = true
                        val d = doc
                        val v = view
                        val l = lamp
                        val window = size
                        app.scope.launch {
                            notice = try {
                                val (w, h) = export(context, library, d, frames, shape, l, v, window, all, glass != null)
                                "已保存到相册：$w × $h"
                            } catch (e: OutOfMemoryError) {
                                "内存不够导出这么大的一张，换「当前窗口」再试。"
                            } catch (e: Exception) {
                                "没有导出：${e.message ?: "出了点问题"}"
                            }
                            exporting = false
                        }
                    }.padding(vertical = 12.dp),
                ) {
                    Txt(title, Type.body)
                    Txt(note, Type.caption)
                }
            }
        }
    }
}

private val GLASS = Color(0xE6161616)

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

/** A round button on a dark glass disc: readable over a bright table. */
@Composable
private fun Knob(glyph: Glyph, label: String, enabled: Boolean, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(GLASS).pressable(enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, tint = if (lit) Ink.Amber else Ink.Text, size = 22.dp)
    }
}

@Composable
private fun Tool(glyph: Glyph, label: String, on: Boolean = false, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) Ink.Red else if (on) Ink.Amber else Ink.Text
    Column(
        Modifier.pressable(enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(glyph, tint = tint, size = 22.dp)
        Gap(height = 3.dp)
        Txt(label, Type.caption, color = tint)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(GLASS)
            .pointerInput(Unit) { }.padding(horizontal = 14.dp, vertical = 10.dp),
    ) { content() }
}

/** The loupes as glass: the whole table is drawn into a layer and the lens shader bends what it shows. */
@RequiresApi(33)
private fun Modifier.glass(shader: RuntimeShader, scene: () -> Scene): Modifier =
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
    lamp: Lamp, view: View, window: IntSize, all: Boolean, glass: Boolean,
): Pair<Int, Int> = withContext(Dispatchers.Default) {
    val x0: Float
    val y0: Float
    val x1: Float
    val y1: Float
    if (all) {
        val b = bounds(doc, shape) ?: throw IOException("观片台是空的")
        val m = max(60f, 0.06f * max(b.width, b.height))
        x0 = b.left - m; y0 = b.top - m; x1 = b.right + m; y1 = b.bottom + m
    } else {
        x0 = (-window.width / 2f - view.x) / view.s; x1 = (window.width / 2f - view.x) / view.s
        y0 = (-window.height / 2f - view.y) / view.s; y1 = (window.height / 2f - view.y) / view.s
    }
    val longEdge = if (all) 4096 else min(4096, max(window.width, window.height) * 2)
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
