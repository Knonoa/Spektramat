package com.spektra.latent.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spektra.latent.model.Choice

/** One of a few choices: its [id], what it says, and optionally an icon ([text] false shows the icon alone). */
class Opt(
    val id: String, val label: String, val icon: Glyph? = null, val text: Boolean = true, val swatch: Color? = null,
    /** In a control that shares its width out: this segment keeps the width of what it says. */
    val fit: Boolean = false,
)

fun List<Choice>.opts(): List<Opt> = map { Opt(it.wire, it.label) }

/** How a segmented control is cut: its segments' height, side padding and lettering. */
enum class SegStyle(val height: Dp, val pad: Dp, val font: Float) {
    Regular(36.dp, 15.dp, 12.5f),
    Small(30.dp, 11.dp, 12f),
    /** The tabs inside the top capsule. */
    Tabs(42.dp, 13.dp, 13.5f),
    /** The same on a narrow phone, where the two capsules have to share less. */
    TabsNarrow(42.dp, 9.dp, 13.5f),
    /** The darkroom's steps. */
    Steps(38.dp, 15.dp, 13.5f),
    /** Inside a small glass capsule of its own (the camera's mode and lens). */
    Capsule(34.dp, 12.dp, 12.5f),
}

/**
 * A segmented control. The chosen segment is under the droplet, which travels
 * to the next one when the choice changes.
 *
 * @param block the segments share the whole width
 * @param bare no recessed track around the segments: the capsule the control sits in is the track
 * @param onChosenAt told where the chosen segment is inside the control, so that a row that scrolls can bring it into view
 */
@Composable
fun Seg(
    options: List<Opt>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: SegStyle = SegStyle.Regular,
    block: Boolean = false,
    bare: Boolean = false,
    minItem: Dp = 0.dp,
    onChosenAt: ((Rect) -> Unit)? = null,
) {
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val droplet = rememberDroplet(selected, selected?.let { bounds[it] })
    val well = Ink.Well
    val wellLine = Ink.WellLine
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .then(if (bare) Modifier else Modifier.clip(shape).background(well).border(1.dp, wellLine, shape).padding(4.dp))
            .drawBehind { drawDroplet(droplet) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (o in options) {
            val on = o.id == selected
            Row(
                Modifier
                    .then(if (block && !o.fit) Modifier.weight(1f) else Modifier)
                    .defaultMinSize(minWidth = minItem)
                    .height(style.height)
                    .onGloballyPositioned {
                        val at = it.boundsInParent()
                        if (bounds[o.id] != at) {
                            bounds[o.id] = at
                            if (on) onChosenAt?.invoke(at)
                        }
                    }
                    .clip(shape)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                        if (!on) {
                            onSelect(o.id)
                            bounds[o.id]?.let { at -> onChosenAt?.invoke(at) }
                        }
                    }
                    .semantics {
                        this.selected = on
                        if (!o.text) contentDescription = o.label
                    }
                    .padding(horizontal = style.pad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                val ink = if (on) Ink.GlassText else Ink.GlassDim
                if (o.icon != null) Icon(o.icon, tint = ink, size = 16.dp)
                if (o.text) Txt(o.label, Type.label.copy(fontSize = style.font.sp), color = ink)
            }
        }
    }
}

/** One choice as a capsule; lit when it is the chosen one. */
@Composable
fun Chip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    icon: Glyph? = null,
    swatch: Color? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .pressable(enabled, sink = .96f, onClick = onClick)
            .semantics { selected = on }
            .height(height)
            .clip(shape)
            .background(if (on) Ink.AccentSoft else Ink.Well)
            .border(1.dp, if (on) Ink.Accent else Ink.Line2, shape)
            .padding(horizontal = if (height < 36.dp) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (swatch != null) Box(Modifier.size(8.dp).background(swatch, CircleShape))
        if (icon != null) Icon(icon, tint = if (on) Ink.GlassText else Ink.GlassDim, size = 14.dp)
        Txt(label, Type.label, color = if (on) Ink.GlassText else Ink.GlassDim)
    }
}

/**
 * Choices in a row that scrolls sideways out to the edges of what holds it
 * ([bleed] is that holder's side padding), or wrapped onto several lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(
    options: List<Opt>,
    selected: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    bleed: Dp = 0.dp,
    height: Dp = 36.dp,
    scroll: ScrollState = rememberScrollState(),
    trailing: (@Composable () -> Unit)? = null,
) {
    if (wrap) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in options) Chip(o.label, o.id == selected, { onPick(o.id) }, height = height, icon = o.icon, swatch = o.swatch)
            trailing?.invoke()
        }
    } else {
        Row(
            modifier
                .layoutBleed(bleed)
                .horizontalScroll(scroll)
                .padding(horizontal = bleed, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (o in options) Chip(o.label, o.id == selected, { onPick(o.id) }, height = height, icon = o.icon, swatch = o.swatch)
            trailing?.invoke()
        }
    }
}

/** Lets a row reach [by] past both sides of its parent's content, so that it scrolls under the parent's padding. */
fun Modifier.layoutBleed(by: Dp): Modifier = if (by == 0.dp) this else layout { measurable, constraints ->
    val bounded = constraints.hasBoundedWidth
    val wide = if (bounded) constraints.maxWidth + by.roundToPx() * 2 else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = if (bounded) wide else constraints.minWidth, maxWidth = wide))
    val width = if (bounded) constraints.maxWidth else placeable.width
    layout(width, placeable.height) { placeable.place(-(placeable.width - width) / 2, 0) }
}

private val thumbGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/** On or off. The thumb is a droplet with a pilot light in it; the light is amber when on. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val k by animateFloatAsState(if (on) 1f else 0f, tween(420, easing = Motion.Spring), label = "toggle")
    val stretch by animateFloatAsState(if (pressed) 1f else 0f, tween(200), label = "stretch")
    val shape = RoundedCornerShape(50)
    val well = Ink.Well
    val soft = Ink.AccentSoft
    val faint = Ink.Faint
    Box(
        modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Switch) { onChange(!on) }
            .semantics { contentDescription = label }
            .drawBehind {
                val alpha = if (enabled) 1f else .38f
                drawRect(well.copy(alpha = well.alpha * alpha))
                drawRect(soft.copy(alpha = soft.alpha * k.coerceIn(0f, 1f) * alpha))
                // the thumb grows a little toward where it is going while it is held
                val w = (24f + 4f * stretch).dp.toPx()
                val travel = size.width - 6.dp.toPx() - 24.dp.toPx()
                val left = 3.dp.toPx() + travel * k - (w - 24.dp.toPx()) * k.coerceIn(0f, 1f)
                val thumb = Rect(left, 3.dp.toPx(), left + w, 27.dp.toPx())
                drawLens(thumb, 12.dp.toPx())
                val lit = k.coerceIn(0f, 1f)
                if (lit > 0f) drawIntoCanvas {
                    thumbGlow.color = Ink.Amber.copy(alpha = .7f * lit * alpha).toArgb()
                    thumbGlow.maskFilter = android.graphics.BlurMaskFilter(4.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                    it.nativeCanvas.drawCircle(thumb.center.x, thumb.center.y, 4.dp.toPx(), thumbGlow)
                }
                drawCircle(faint.copy(alpha = .5f * (1f - lit) * alpha), 3.dp.toPx(), thumb.center)
                drawCircle(Ink.Amber.copy(alpha = lit * alpha), 3.dp.toPx(), thumb.center)
            }
            .border(1.dp, Ink.WellLine, shape),
    )
}

// ------------------------------------------------------------------ a control row: label · value · widget

/**
 * Rows of settings, one under another with a hairline between them. A child
 * marked [plain] (a group's heading, a note) has no line above or below it.
 */
@Composable
fun CtlColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val lines = remember { mutableStateOf(IntArray(0)) }
    val line = Ink.Line
    Layout(
        content,
        modifier.drawBehind {
            for (y in lines.value) drawLine(line, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1.dp.toPx())
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val between = ArrayList<Int>()
        var y = 0
        placeables.forEachIndexed { i, p ->
            if (i > 0 && p.height > 0 && measurables[i].layoutId != PLAIN && measurables[i - 1].layoutId != PLAIN) between += y
            y += p.height
        }
        val next = between.toIntArray()
        if (!next.contentEquals(lines.value)) lines.value = next
        layout(constraints.maxWidth, y) {
            var top = 0
            for (p in placeables) {
                p.place(0, top)
                top += p.height
            }
        }
    }
}

private const val PLAIN = "plain"

/** For a child of [CtlColumn] that is not a setting. */
val Modifier.plain: Modifier get() = layoutId(PLAIN)

/**
 * One setting: its name at the left, its value (amber once it has been
 * changed) or a small widget at the right, and under them the control itself.
 */
@Composable
fun Ctl(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueSet: Boolean = false,
    valueStyle: androidx.compose.ui.text.TextStyle? = null,
    note: String? = null,
    muted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Type.body, Modifier.weight(1f).padding(end = 12.dp), color = if (muted) Ink.GlassDim else Ink.GlassText)
            if (value != null) {
                Txt(value, valueStyle ?: Type.num.copy(fontSize = 13.5.sp), color = if (valueSet) Ink.Accent else Ink.GlassDim)
            }
            trailing?.invoke()
        }
        if (body != null) {
            Gap(height = 6.dp)
            body()
        }
        if (note != null) {
            Gap(height = 6.dp)
            Txt(note, Type.micro.copy(lineHeight = 17.6.sp), color = Ink.GlassDim, maxLines = 6)
        }
    }
}

/** A heading between groups of settings. */
@Composable
fun CtlGroup(label: String) {
    Txt(label, Type.micro.copy(letterSpacing = .1.em), Modifier.plain.padding(top = 14.dp, bottom = 4.dp), color = Ink.Faint)
}

/** A line of text with what it does under it, and something to press or switch at its end. */
@Composable
fun SettingRow(title: String, detail: String, modifier: Modifier = Modifier, end: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Txt(title, Type.body, maxLines = 2)
            if (detail.isNotEmpty()) {
                Gap(height = 2.dp)
                Txt(detail, Type.micro.copy(lineHeight = 16.5.sp), maxLines = 4)
            }
        }
        end()
    }
}

/** One line to type into: a name. */
@Composable
fun TextEntry(value: String, onChange: (String) -> Unit, hint: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(Ink.Well)
            .border(1.dp, Ink.Line2, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Txt(hint, Type.body, color = Ink.Faint)
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 40) onChange(it.replace("\n", "")) },
            singleLine = true,
            textStyle = Type.body.copy(color = Ink.GlassText),
            cursorBrush = SolidColor(Ink.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
