package com.spektra.latent.ui.darkroom

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.spektra.latent.model.Choice
import com.spektra.latent.model.DateBack
import com.spektra.latent.model.EdgeChoices
import com.spektra.latent.model.Format
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import com.spektra.latent.ui.ChoiceStrip
import com.spektra.latent.ui.Gap
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.ParamChip
import com.spektra.latent.ui.Pill
import com.spektra.latent.ui.Ruler
import com.spektra.latent.ui.Toggle
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.pressable
import kotlin.math.roundToInt

/** One thing the bench can set: a row of named choices, a ruler, or a sentence with a button. */
private sealed interface Item {
    val label: String
    val value: String
    val changed: Boolean

    class Pick(override val label: String, val choices: List<Choice>, val current: String, val default: String, val set: (String) -> Look) : Item {
        override val value get() = EdgeChoices.label(choices, current)
        override val changed get() = current != default
    }

    class Scale(
        override val label: String, val current: Float, val min: Float, val max: Float, val step: Float, val major: Float,
        val default: Float, val format: (Float) -> String, val set: (Float) -> Look,
    ) : Item {
        override val value get() = format(current)
        override val changed get() = current != default
    }

    class Switch(override val label: String, val on: Boolean, val note: String, val available: Boolean, val set: (Boolean) -> Look) : Item {
        override val value get() = if (on && available) "印" else "—"
        override val changed get() = on && available
    }

    class Act(override val label: String, override val value: String, override val changed: Boolean, val note: String, val button: String, val run: () -> Unit, val undo: (() -> Unit)? = null) : Item
}

/**
 * The film outside the frame, and the date back. Both end up on the negative,
 * so they sit between the film and the print.
 *
 * It works on any look -- the darkroom's photograph, or the film loaded in the
 * camera -- through [onChange] (every step) and [onSettled] (when the hand
 * lets go). [shooting] is what the photograph's file says; null before there
 * is a photograph.
 */
@Composable
fun EdgeBench(look: Look, shooting: Shooting?, onChange: (Look) -> Unit, onSettled: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tool by remember { mutableIntStateOf(0) }       // 0 the film's edge, 1 the date back
    var edgeAt by remember { mutableIntStateOf(0) }
    var dateAt by remember { mutableIntStateOf(0) }
    val format = Format.of(look.format)
    val edge = look.edge
    val back = look.date
    fun set(next: Look) { onChange(next); onSettled() }

    val shot = shooting?.let { s -> DateBack.parseDate(back.manual)?.let { s.withDate(it[0], it[1], it[2]) } ?: s }
    val dataLine = shot?.edgeText ?: ""
    // with the gate open the picture is the whole film: nothing beside it to show or to print on
    val open = edge.on && edge.open
    val items: List<Item> = if (tool == 0) listOfNotNull(
        // which film this is: the same choice as in the film step, here because the edge is that film's
        Item.Pick("片幅", EdgeChoices.formats, format.id, "135") { look.copy(format = it) },
        Item.Pick("片门", EdgeChoices.gates, edge.gate, "auto") { look.copy(edge = edge.copy(gate = it)) },
        if (open) null else Item.Pick("样式", EdgeChoices.views, edge.view, "strip") { look.copy(edge = edge.copy(view = it)) },
        if (format.roll) null else Item.Pick("齿孔", EdgeChoices.holes, edge.holes, "white") { look.copy(edge = edge.copy(holes = it)) },
        Item.Scale("帧号", edge.frameNumber.toFloat(), 0f, 99f, 1f, 10f, 0f, { if (it < 0.5f) "自动" else "${it.roundToInt()}" }) {
            look.copy(edge = edge.copy(frameNumber = it.roundToInt()))
        },
        Item.Scale("灰雾", edge.fog, 0f, 400f, 5f, 100f, 100f, { "${it.roundToInt()}" }) { look.copy(edge = edge.copy(fog = it)) },
        Item.Scale("漏光", edge.leaks, 0f, 400f, 5f, 100f, 0f, { "${it.roundToInt()}" }) { look.copy(edge = edge.copy(leaks = it)) },
        Item.Switch(
            "数据", edge.data,
            when {
                open -> "片门全开时画面铺满整条胶片，旁边没有空的片边可以印。"
                !format.hasDateBack -> "${format.label} 的宽幅相机没有数据后背。"
                shooting == null -> "拍下之后，把快门和光圈印在画面旁的片边上。"
                dataLine.isEmpty() -> "这张照片没有记录快门和光圈。"
                else -> "把 $dataLine 印在画面旁的片边上。"
            },
            !open && format.hasDateBack && (shooting == null || dataLine.isNotEmpty()),
        ) { look.copy(edge = edge.copy(data = it)) },
        Item.Act("机身", "#${edge.body}", edge.body != 1, "片门的形状、帧距和漏光的位置跟着机身走，同一台机身拍出的每一格都一样。", "换一台", {
            set(look.copy(edge = edge.copy(body = (1..9999).random())))
        }),
    ) else listOfNotNull(
        Item.Pick("样式", EdgeChoices.faces, back.face, "lcd") { look.copy(date = back.copy(face = it)) },
        if (back.face == "data") null else Item.Pick("顺序", EdgeChoices.orders, back.order, "ymd") { look.copy(date = back.copy(order = it)) },
        if (format.roll || open) null else Item.Pick("位置", EdgeChoices.placements, back.placement, "frame") { look.copy(date = back.copy(placement = it)) },
        if (!format.roll && !open && back.placement != "frame") null else Item.Pick("角落", EdgeChoices.corners, back.corner, "br") { look.copy(date = back.copy(corner = it)) },
        Item.Scale("字号", back.size, 40f, 300f, 5f, 100f, 100f, { "${it.roundToInt()}" }) { look.copy(date = back.copy(size = it)) },
        Item.Scale("亮度", back.brightness, -2f, 8f, 0.1f, 1f, 3.5f, { String.format(java.util.Locale.US, "%.1f", it) }) { look.copy(date = back.copy(brightness = it)) },
        if (back.face == "data") null else Item.Switch("数据", back.withData, "在日期之外，再印快门、光圈、曝光模式、补偿和焦距。", true) { look.copy(date = back.copy(withData = it)) },
        Item.Act(
            "日期", shot?.let { "${it.month}.${it.day}" } ?: "当天", back.manual.isNotEmpty(),
            if (back.manual.isNotEmpty()) "手动填写的日期。" else if (shooting == null) "印拍摄当天的日期。" else "照片自己的拍摄日期；可以改成别的。",
            "修改",
            {
                val d = shot ?: Shooting.today()
                DatePickerDialog(context, { _, y, m, day ->
                    set(look.copy(date = back.copy(manual = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, day))))
                }, d.year, d.month - 1, d.day).show()
            },
            if (back.manual.isNotEmpty()) ({ set(look.copy(date = back.copy(manual = ""))) }) else null,
        ),
    )
    val at = (if (tool == 0) edgeAt else dateAt).coerceIn(0, items.lastIndex)
    val on = if (tool == 0) edge.on else back.on
    val dateOffered = format.hasDateBack

    Column(modifier.fillMaxWidth()) {
        // ---- which of the two, and whether it is on
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((i, name) in listOf("片边", "日期后背").withIndex()) {
                val lit = if (i == 0) edge.on else back.on && dateOffered
                Box(Modifier.pressable { tool = i }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Txt(name, Type.label, color = if (i == tool) Ink.Text else Ink.Dim)
                }
                if (lit) Txt("●", Type.caption, color = Ink.Amber) else Gap(width = 8.dp)
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                Txt(
                    if (tool == 0) (if (open) "${format.label} 胶片，全开" else "${format.label} 胶片")
                    else if (!dateOffered) "" else if (back.on) dateText(look, shot) else "",
                    Type.edgeSmall,
                )
            }
            Toggle(
                on && (tool == 0 || dateOffered),
                { v -> set(if (tool == 0) look.copy(edge = edge.copy(on = v)) else look.copy(date = back.copy(on = v))) },
                if (tool == 0) "显示片边" else "日期打印",
                enabled = tool == 0 || dateOffered,
            )
        }
        if (!on || (tool == 1 && !dateOffered)) {
            Box(Modifier.fillMaxWidth().height(BENCH).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Txt(
                    when {
                        tool == 0 -> "画框外的胶片：齿孔、片边字、灰雾和漏光。\n可选片幅；片门全开时画面铺到齿孔上。"
                        !dateOffered -> "${format.label} 的宽幅相机没有日期后背。"
                        else -> "像相机的日期后背那样，把日期曝光在底片上。"
                    },
                    Type.label, color = Ink.Dim, maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Column
        }
        LazyRow(Modifier.fillMaxWidth().height(60.dp), contentPadding = PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(items.size) { i ->
                val it = items[i]
                ParamChip(it.label, it.value, it.changed, i == at, { if (tool == 0) edgeAt = i else dateAt = i })
            }
        }
        Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
            when (val it = items[at]) {
                is Item.Pick -> ChoiceStrip(it.choices, it.current, { v -> set(it.set(v)) })
                is Item.Scale -> Ruler(
                    name = it.label, value = it.current, min = it.min, max = it.max, step = it.step, major = it.major,
                    default = it.default, label = it.format, onChange = { v -> onChange(it.set(v)) }, onSettled = onSettled,
                )
                is Item.Switch -> Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(it.note, Type.label, Modifier.weight(1f).padding(end = 12.dp), color = Ink.Dim, maxLines = 2)
                    Toggle(it.on && it.available, { v -> set(it.set(v)) }, it.label, enabled = it.available)
                }
                is Item.Act -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Txt(it.note, Type.label, Modifier.weight(1f), color = Ink.Dim, maxLines = 2)
                    it.undo?.let { undo -> Pill("恢复", undo) }
                    Pill(it.button, it.run)
                }
            }
        }
    }
}

private val BENCH = 128.dp

/** The line the date back will print, for showing beside its switch. */
private fun dateText(look: Look, shot: Shooting?): String {
    val s = shot ?: Shooting.today()
    return if (look.date.face == "data") s.dataText.ifEmpty { "没有拍摄数据" } else s.dateText(look.date.order)
}
