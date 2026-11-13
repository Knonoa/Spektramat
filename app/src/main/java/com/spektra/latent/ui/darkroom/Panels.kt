package com.spektra.latent.ui.darkroom

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektra.latent.data.Frame
import com.spektra.latent.data.Recipe
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.model.Control
import com.spektra.latent.model.Controls
import com.spektra.latent.model.DateBack
import com.spektra.latent.model.EdgeChoices
import com.spektra.latent.model.Finishing
import com.spektra.latent.model.Format
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import com.spektra.latent.ui.Btn
import com.spektra.latent.ui.BtnSize
import com.spektra.latent.ui.Chip
import com.spektra.latent.ui.Chips
import com.spektra.latent.ui.Ctl
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Glyph
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.Opt
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.SCAN
import com.spektra.latent.ui.Seg
import com.spektra.latent.ui.SegStyle
import com.spektra.latent.ui.Stock
import com.spektra.latent.ui.StockRail
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.filmStocks
import com.spektra.latent.ui.opts
import com.spektra.latent.ui.plain
import com.spektra.latent.ui.shortName
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The settings of a look, as rows of controls: a name, its value, and under
 * them the thing that sets it. Each function here is one step of the
 * darkroom; it writes rows into the column that calls it.
 */

/** Something whose look can be worked on: the darkroom's photograph, or the film loaded in the camera. */
interface LookHost {
    val look: Look
    /** Every step of a ruler. */
    fun change(next: Look)
    /** The hand has let go: what is there now counts. */
    fun commit()
}

private fun LookHost.set(next: Look) {
    change(next)
    commit()
}

/** The side padding of the tray the rows sit in: rows that scroll sideways run out through it. */
internal val TRAY_PAD = 18.dp

private fun signed(v: Float): String {
    val n = v.roundToInt()
    return if (n > 0) "+$n" else "$n"
}

private fun whole(v: Float) = "${v.roundToInt()}"

/** One number: a row with its ruler. */
@Composable
private fun Scale(
    host: LookHost, label: String, value: Float, min: Float, max: Float, step: Float, default: Float,
    format: (Float) -> String, muted: Boolean = false, write: (Look, Float) -> Look,
) {
    Ctl(label, value = format(value), valueSet = value != default, muted = muted) {
        Ruler(label, value, min, max, step, default, { host.change(write(host.look, it)) }, host::commit)
    }
}

@Composable
private fun Scale(host: LookHost, c: Control) =
    Scale(host, c.label, c.get(host.look), c.min, c.max, c.step, c.default, c.format, write = c.set)

@Composable
private fun Switch(label: String, on: Boolean, onChange: (Boolean) -> Unit, note: String? = null, enabled: Boolean = true) {
    Ctl(label, note = note, trailing = { Toggle(on && enabled, onChange, label, enabled = enabled) })
}

@Composable
private fun Note(text: String) {
    Txt(text, Type.micro.copy(lineHeight = 17.6.sp), Modifier.plain.padding(vertical = 10.dp), color = Ink.GlassDim, maxLines = 6)
}

// ------------------------------------------------------------------ 调节

@Composable
internal fun TonePanel(host: LookHost) {
    for (c in Controls.scene) Scale(host, c)
    FinishPanel(host)
}

// ------------------------------------------------------------------ 胶片

/**
 * @param frame the photograph every stock is shown on; null where there is none (the camera)
 * @param onKeep asks for the current look to be kept as a recipe; null where recipes are only used
 */
@Composable
internal fun FilmPanel(host: LookHost, frame: Frame?, recipes: List<Recipe>, onKeep: (() -> Unit)?, bleed: Dp = TRAY_PAD) {
    val look = host.look
    Ctl("型号", value = shortName(FilmEngine.catalog.film(look.film)?.name ?: look.film), valueSet = true, valueStyle = Type.film.copy(fontSize = 12.5.sp)) {
        StockRail(filmStocks(), look.film, { host.set(host.look.withFilm(it)) }, frame = frame, look = look, bleed = bleed)
    }
    // how large the frame is on the film: grain and halation are sized by it, and the edge follows it
    Ctl("片幅", value = "长边 ${Format.of(look.format).longMm.roundToInt()} mm", note = "片幅决定颗粒和光晕的尺度。") {
        Chips(EdgeChoices.formats.opts(), look.format, { host.set(host.look.copy(format = it)) }, bleed = bleed)
    }
    if (recipes.isNotEmpty() || onKeep != null) {
        Ctl("配方") {
            Chips(
                recipes.map { Opt(it.id.toString(), it.name, Glyph.Recipe) }, null,
                { id -> recipes.firstOrNull { it.id.toString() == id }?.let { host.set(host.look.withRecipe(it.look)) } },
                bleed = bleed,
                trailing = if (onKeep == null) null else ({ Chip(if (recipes.isEmpty()) "存为配方" else "管理", false, onKeep, icon = Glyph.Plus) }),
            )
        }
    }
}

// ------------------------------------------------------------------ 放大机

@Composable
internal fun PrintPanel(host: LookHost, frame: Frame?) {
    val look = host.look
    if (FilmEngine.catalog.film(look.film)?.positive == true) {
        Note("反转片不用印相：冲出来的胶片本身就是成片。")
        return
    }
    Switch("直接扫描底片", look.scan, { host.set(host.look.copy(scan = it)) }, "不经过相纸，直接看底片扫描的结果。")
    if (look.scan) return
    Ctl("相纸", value = shortName(FilmEngine.catalog.paper(look.paper)?.name ?: ""), valueSet = true, valueStyle = Type.film.copy(fontSize = 12.5.sp)) {
        StockRail(
            FilmEngine.catalog.papers.map { Stock(it.id, it.name, if (it.blackAndWhite) "黑白相纸" else if (it.cine) "电影拷贝片" else "彩色相纸") },
            look.paper, { host.set(host.look.copy(scan = false, paper = it)) }, frame = frame, look = look, bleed = TRAY_PAD,
        )
    }
    for (c in Controls.print) if (c.id != "glare") Scale(host, c)
}

// ------------------------------------------------------------------ 质感

@Composable
internal fun TexturePanel(host: LookHost) {
    val look = host.look
    for (id in listOf("grain", "halation", "interLayer")) Controls.film.firstOrNull { it.id == id }?.let { Scale(host, it) }
    // glare happens on the paper: there is none when the film is looked at directly
    if (!look.scan && FilmEngine.catalog.film(look.film)?.positive != true) {
        Controls.print.firstOrNull { it.id == "glare" }?.let { Scale(host, it) }
    }
}

// ------------------------------------------------------------------ 片边

/** The film outside the frame and its date back. [shooting] is null before there is a photograph. */
@Composable
internal fun EdgePanel(host: LookHost, shooting: Shooting?, bleed: Dp = TRAY_PAD) {
    EdgeControls(host, shooting, bleed)
    DatePanel(host, shooting)
}

@Composable
private fun EdgeControls(host: LookHost, shooting: Shooting?, bleed: Dp) {
    val look = host.look
    val edge = look.edge
    val format = Format.of(look.format)
    Switch("片边", edge.on, { host.set(host.look.copy(edge = host.look.edge.copy(on = it))) })
    if (!edge.on) {
        Note("画框外的胶片：齿孔、片边字、灰雾和漏光。可选片幅；片门全开时画面铺到齿孔上。")
        return
    }
    // with the gate open the picture is the whole film: nothing beside it to show or to print on
    val open = edge.open
    Ctl("片幅", value = if (open) "${format.label} 胶片，全开" else "${format.label} 胶片") {
        Chips(EdgeChoices.formats.opts(), format.id, { host.set(host.look.copy(format = it)) }, bleed = bleed)
    }
    if (!open) Ctl("样式") {
        Seg(EdgeChoices.views.opts(), edge.view, { host.set(host.look.copy(edge = host.look.edge.copy(view = it))) }, Modifier.fillMaxWidth(), SegStyle.Small, block = true)
    }
    Ctl("片门", note = "全开：没有片门，画面铺满整条胶片，齿孔穿过画面。") {
        Chips(EdgeChoices.gates.opts(), edge.gate, { host.set(host.look.copy(edge = host.look.edge.copy(gate = it))) }, bleed = bleed)
    }
    if (!format.roll) Ctl("齿孔") {
        Seg(EdgeChoices.holes.opts(), edge.holes, { host.set(host.look.copy(edge = host.look.edge.copy(holes = it))) }, Modifier.fillMaxWidth(), SegStyle.Small, block = true)
    }
    Scale(host, "帧号", edge.frameNumber.toFloat(), 0f, 99f, 1f, 0f, { if (it < .5f) "自动" else whole(it) }) { l, v ->
        l.copy(edge = l.edge.copy(frameNumber = v.roundToInt()))
    }
    val shot = shooting?.let { s -> DateBack.parseDate(look.date.manual)?.let { s.withDate(it[0], it[1], it[2]) } ?: s }
    val line = shot?.edgeText ?: ""
    val can = !open && format.hasDateBack && (shooting == null || line.isNotEmpty())
    Switch(
        "印快门与光圈", edge.data, { host.set(host.look.copy(edge = host.look.edge.copy(data = it))) },
        when {
            open -> "片门全开时画面铺满整条胶片，旁边没有空的片边可以印。"
            !format.hasDateBack -> "${format.label} 的宽幅相机没有数据后背。"
            shooting == null -> "拍下之后，把快门和光圈印在画面旁的片边上。"
            line.isEmpty() -> "这张照片没有记录快门和光圈。"
            else -> "把 $line 印在画面旁的片边上。"
        },
        enabled = can,
    )
    Scale(host, "灰雾", edge.fog, 0f, 400f, 5f, 100f, ::whole) { l, v -> l.copy(edge = l.edge.copy(fog = v)) }
    Scale(host, "漏光", edge.leaks, 0f, 400f, 5f, 0f, ::whole) { l, v -> l.copy(edge = l.edge.copy(leaks = v)) }
    Ctl(
        "机身", value = "#${edge.body}", valueSet = edge.body != 1,
        note = "片门的形状、帧距和漏光的位置跟着机身走，同一台机身拍出的每一格都一样。",
    ) {
        Btn("换一台", { host.set(host.look.copy(edge = host.look.edge.copy(body = (1..9999).random()))) }, size = BtnSize.Small)
    }
}

// ------------------------------------------------------------------ 日期

/** The date back: the date exposed onto the negative, as a camera's data back does it. */
@Composable
internal fun DatePanel(host: LookHost, shooting: Shooting?) {
    val context = LocalContext.current
    val look = host.look
    val back = look.date
    val format = Format.of(look.format)
    if (!format.hasDateBack) {
        Switch("日期后背", false, {}, enabled = false)
        Note("${format.label} 的宽幅相机没有日期后背。")
        return
    }
    val shot = shooting?.let { s -> DateBack.parseDate(back.manual)?.let { s.withDate(it[0], it[1], it[2]) } ?: s }
    Switch("日期后背", back.on, { host.set(host.look.copy(date = host.look.date.copy(on = it))) })
    if (!back.on) {
        Note("像相机的日期后背那样，把日期曝光在底片上。")
        return
    }
    fun write(change: (DateBack) -> DateBack) = host.set(host.look.copy(date = change(host.look.date)))
    val open = look.edge.on && look.edge.open
    val data = back.face == "data"
    Ctl("字样", value = (shot ?: Shooting.today()).let { if (data) it.dataText.ifEmpty { "没有拍摄数据" } else it.dateText(back.order) }) {
        Seg(EdgeChoices.faces.opts(), back.face, { v -> write { it.copy(face = v) } }, Modifier.fillMaxWidth(), SegStyle.Small, block = true)
    }
    if (!data) Ctl("顺序") {
        Seg(EdgeChoices.orders.opts(), back.order, { v -> write { it.copy(order = v) } }, Modifier.fillMaxWidth(), SegStyle.Small, block = true)
    }
    if (!format.roll && !open) Ctl("位置") {
        Seg(EdgeChoices.placements.opts(), back.placement, { v -> write { it.copy(placement = v) } }, Modifier.fillMaxWidth(), SegStyle.Small, block = true)
    }
    if (format.roll || open || back.placement == "frame") Ctl("角落") {
        Seg(EdgeChoices.corners.opts(), back.corner, { v -> write { it.copy(corner = v) } }, Modifier.fillMaxWidth(), SegStyle.Small, block = true)
    }
    Scale(host, "字号", back.size, 40f, 300f, 5f, 100f, ::whole) { l, v -> l.copy(date = l.date.copy(size = v)) }
    Scale(host, "亮度", back.brightness, -2f, 8f, .1f, 3.5f, { String.format(Locale.US, "%.1f", it) }) { l, v -> l.copy(date = l.date.copy(brightness = v)) }
    if (!data) Switch("同时印拍摄数据", back.withData, { v -> write { it.copy(withData = v) } }, "在日期之外，再印快门、光圈、曝光模式、补偿和焦距。")
    Ctl(
        "日期", value = shot?.let { "${it.year}.${it.month}.${it.day}" } ?: "拍摄当天", valueSet = back.manual.isNotEmpty(),
        note = if (back.manual.isNotEmpty()) "手动填写的日期。" else if (shooting == null) "印拍摄当天的日期。" else "照片自己的拍摄日期；可以改成别的。",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("修改", {
                val d = shot ?: Shooting.today()
                DatePickerDialog(context, { _, y, m, day ->
                    write { it.copy(manual = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, day)) }
                }, d.year, d.month - 1, d.day).show()
            }, size = BtnSize.Small)
            if (back.manual.isNotEmpty()) Btn("恢复", { write { it.copy(manual = "") } }, size = BtnSize.Small)
        }
    }
}

// ------------------------------------------------------------------ 整饰

private fun List<Float>.with(index: Int, value: Float): List<Float> = toMutableList().also { it[index] = value }

/** The print itself is finished, the way a scan is corrected: tone curve, colour mixer, colour grading, vignette. */
@Composable
internal fun FinishPanel(host: LookHost) {
    CurveCtl(host)
    MixerCtl(host)
    GradingCtl(host)
    Scale(host, "暗角", host.look.vignette, -100f, 100f, 1f, 0f, ::signed) { l, v -> l.copy(vignette = v) }
}

private val CURVES = listOf(Opt("0", "亮度"), Opt("1", "红"), Opt("2", "绿"), Opt("3", "蓝"))
private val CURVE_INKS = listOf(Color.Unspecified, Color(0xFFE5605F), Color(0xFF58C06B), Color(0xFF5B8DEF))

@Composable
private fun CurveCtl(host: LookHost) {
    var channel by remember { mutableIntStateOf(0) }
    fun points(l: Look) = when (channel) { 0 -> l.curve; 1 -> l.curveR; 2 -> l.curveG; else -> l.curveB }
    fun with(l: Look, p: List<Float>) = when (channel) {
        0 -> l.copy(curve = p)
        1 -> l.copy(curveR = p)
        2 -> l.copy(curveG = p)
        else -> l.copy(curveB = p)
    }
    val current = points(host.look)
    Ctl(
        "曲线",
        trailing = { if (Finishing.curveChanged(host.look)) Chip("复位", false, { host.set(with(host.look, Finishing.IDENTITY)) }, height = 32.dp) },
    ) {
        Seg(CURVES, channel.toString(), { channel = it.toInt() }, Modifier.fillMaxWidth(), SegStyle.Small, block = true)
        Gap(height = 6.dp)
        Box(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 250.dp).fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)).background(Ink.Well).padding(12.dp),
        ) {
            CurveEditor(
                current, if (channel == 0) Ink.Accent else CURVE_INKS[channel],
                { host.change(with(host.look, it)) }, host::commit, Modifier.fillMaxSize(),
            )
        }
    }
}

private const val MAX_POINTS = 12
/** Two points are never closer than this along the tone axis. */
private const val POINT_GAP = .03f

/**
 * A tone curve to draw on: dark tones at the left, light at the right, how
 * light each comes out going up. Touch the curve to put a point on it and
 * drag; a point dragged well off the top or bottom is taken away. The two end
 * points stay at the edges and only move up and down.
 */
@Composable
private fun CurveEditor(points: List<Float>, ink: Color, onChange: (List<Float>) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val now by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val settled by rememberUpdatedState(onSettled)
    val grid = Ink.Tick
    val dot = Ink.Card
    Canvas(
        modifier
            .semantics { contentDescription = "色调曲线，${points.size / 2} 个点" }
            .pointerInput(Unit) {
                val reach = 26.dp.toPx()
                val gone = 44.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    var pts = now
                    // the point under the finger, or a new one put on the curve there
                    var index = -1
                    var best = reach
                    for (i in 0 until pts.size / 2) {
                        val d = hypot(pts[i * 2] * w - down.position.x, (1f - pts[i * 2 + 1]) * h - down.position.y)
                        if (d < best) {
                            best = d
                            index = i
                        }
                    }
                    if (index < 0) {
                        if (pts.size / 2 >= MAX_POINTS) return@awaitEachGesture
                        val x = (down.position.x / w).coerceIn(.02f, .98f)
                        val after = (0 until pts.size / 2).indexOfFirst { pts[it * 2] > x }
                        if (after <= 0) return@awaitEachGesture
                        if (x - pts[(after - 1) * 2] < POINT_GAP || pts[after * 2] - x < POINT_GAP) return@awaitEachGesture
                        val samples = Finishing.sample(pts, 256)
                        val y = samples[(x * 255f).roundToInt()]
                        pts = pts.toMutableList().also {
                            it.add(after * 2, y)
                            it.add(after * 2, x)
                        }
                        index = after
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        change(pts)
                    }
                    down.consume()
                    val last = pts.size / 2 - 1
                    var px = pts[index * 2] * w
                    var py = (1f - pts[index * 2 + 1]) * h
                    var removed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val move = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!move.pressed) break
                        val delta = move.positionChange()
                        move.consume()
                        px += delta.x
                        py += delta.y
                        val inner = index in 1 until last
                        // an inner point pulled well clear of the frame comes off; brought back, it is there again
                        val off = inner && (py < -gone || py > h + gone)
                        if (off != removed) {
                            removed = off
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        val next = pts.toMutableList()
                        if (inner) next[index * 2] = (px / w).coerceIn(pts[(index - 1) * 2] + POINT_GAP, pts[(index + 1) * 2] - POINT_GAP)
                        next[index * 2 + 1] = (1f - py / h).coerceIn(0f, 1f)
                        pts = next
                        change(if (removed) next.filterIndexed { i, _ -> i / 2 != index } else next)
                    }
                    settled()
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val hair = .7.dp.toPx()
        for (k in 1..3) {
            drawLine(grid, Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), hair)
            drawLine(grid, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), hair)
        }
        drawLine(grid, Offset(0f, h), Offset(w, 0f), hair, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
        val samples = Finishing.sample(points, 128)
        val path = Path()
        for (i in samples.indices) {
            val x = i / (samples.size - 1f) * w
            val y = (1f - samples[i]) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ink, style = Stroke(1.7.dp.toPx()))
        for (i in 0 until points.size / 2) {
            val at = Offset(points[i * 2] * w, (1f - points[i * 2 + 1]) * h)
            drawCircle(dot, 6.dp.toPx(), at)
            drawCircle(ink, 6.dp.toPx(), at, style = Stroke(1.6.dp.toPx()))
        }
    }
}

private val MIXER_PARTS = listOf("色相", "饱和度", "明度")

@Composable
private fun MixerCtl(host: LookHost) {
    var hue by remember { mutableIntStateOf(0) }
    val mixer = host.look.mixer
    Ctl(
        "混色",
        trailing = { if (Finishing.mixerChanged(host.look)) Chip("复位", false, { host.set(host.look.copy(mixer = Finishing.NO_MIXER)) }, height = 32.dp) },
    ) {
        Chips(
            Finishing.HUE_NAMES.mapIndexed { i, name -> Finishing.HUE_COLOURS[i].let { c -> Opt(i.toString(), name, swatch = Color(c[0], c[1], c[2])) } },
            hue.toString(), { hue = it.toInt() }, bleed = TRAY_PAD,
        )
        MIXER_PARTS.forEachIndexed { part, name ->
            val index = hue * 3 + part
            val v = mixer[index]
            Gap(height = 8.dp)
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(name, Type.body, Modifier.weight(1f), color = Ink.GlassDim)
                Txt(signed(v), Type.num.copy(fontSize = 13.5.sp), color = if (v != 0f) Ink.Accent else Ink.GlassDim)
            }
            Ruler(
                "${Finishing.HUE_NAMES[hue]}的$name", v, -100f, 100f, 1f, 0f,
                { host.change(host.look.copy(mixer = host.look.mixer.with(index, it))) }, host::commit,
            )
        }
    }
}

@Composable
private fun GradingCtl(host: LookHost) {
    val look = host.look
    val g = look.grading
    val changed = Finishing.gradingChanged(look) || look.gradingBalance != 0f || look.gradingBlending != 50f
    Ctl(
        "色彩分级",
        trailing = {
            if (changed) Chip("复位", false, { host.set(host.look.copy(grading = Finishing.NO_GRADING, gradingBalance = 0f, gradingBlending = 50f)) }, height = 32.dp)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (range in 0 until 3) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TintWheel(
                        g[range * 3], g[range * 3 + 1], Finishing.RANGE_NAMES[range],
                        { h, s -> host.change(host.look.copy(grading = host.look.grading.with(range * 3, h).with(range * 3 + 1, s))) },
                        host::commit, Modifier.widthIn(max = 100.dp).fillMaxWidth().aspectRatio(1f),
                    )
                    Txt(Finishing.RANGE_NAMES[range], Type.micro, color = if (g[range * 3 + 1] > 0f) Ink.GlassText else Ink.GlassDim)
                }
            }
        }
    }
    for (range in 0 until 3) {
        val index = range * 3 + 2
        Scale(host, "${Finishing.RANGE_NAMES[range]}明度", g[index], -100f, 100f, 1f, 0f, ::signed, muted = true) { l, v -> l.copy(grading = l.grading.with(index, v)) }
    }
    Scale(host, "平衡", look.gradingBalance, -100f, 100f, 1f, 0f, ::signed, muted = true) { l, v -> l.copy(gradingBalance = v) }
    Scale(host, "过渡", look.gradingBlending, 0f, 100f, 1f, 50f, ::whole, muted = true) { l, v -> l.copy(gradingBlending = v) }
}

/** Hue 0 (red) points right and the hues run clockwise, as Compose sweeps a gradient. */
private val RIM = listOf(
    Color(0xFFD9534A), Color(0xFFE3C54A), Color(0xFF6FA860), Color(0xFF4FA7A8), Color(0xFF4A78C2), Color(0xFFC2569A), Color(0xFFD9534A),
)

/**
 * A tint, picked the way colour is: which way from the centre is the hue, how
 * far out is how strong. The centre is no tint at all; a double tap goes back
 * there.
 */
@Composable
private fun TintWheel(
    hue: Float, saturation: Float, name: String,
    onChange: (Float, Float) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val settled by rememberUpdatedState(onSettled)
    val card = Ink.Card
    val mark = Ink.TickMajor
    val accent = Ink.Accent
    Canvas(
        modifier
            .semantics { contentDescription = "${name}的色调" }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    change(0f, 0f)
                    settled()
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val reach = min(size.width, size.height) / 2f * .84f
                    var moved = false
                    val start = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val move = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!move.pressed) break
                        // a tap is left to the double-tap detector; a drag sets the tint
                        if (!moved && (move.position - start).getDistance() < viewConfiguration.touchSlop) continue
                        moved = true
                        move.consume()
                        val v = move.position - centre
                        val strength = (v.getDistance() / reach).coerceIn(0f, 1f) * 100f
                        val angle = ((atan2(v.y, v.x) * 180f / PI.toFloat()) + 360f) % 360f
                        if (strength < 4f) change(angle, 0f) else change(angle, strength)
                    }
                    if (moved) settled()
                }
            },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val outer = min(size.width, size.height) / 2f
        // the hues round the rim, where they are on the wheel
        drawCircle(Brush.sweepGradient(RIM, centre), outer, centre)
        drawCircle(card.copy(alpha = .9f), outer - 3.dp.toPx(), centre)
        drawCircle(mark, 1.5.dp.toPx(), centre)
        val angle = hue * PI.toFloat() / 180f
        val r = outer * .84f * saturation / 100f
        val at = Offset(centre.x + cos(angle) * r, centre.y + sin(angle) * r)
        drawCircle(Color.Black.copy(alpha = .25f), 9.dp.toPx(), at + Offset(0f, 2.dp.toPx()))
        drawCircle(card, 8.dp.toPx(), at)
        if (saturation > 0f) Finishing.hueColour(hue).let { c -> drawCircle(Color(c[0], c[1], c[2]), 4.5.dp.toPx(), at) }
        drawCircle(accent, 8.dp.toPx(), at, style = Stroke(1.6.dp.toPx()))
    }
}
