package com.spektra.latent.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A press that the finger can feel without a ripple: the thing sinks a little
 * and dims while held.
 */
fun Modifier.pressable(enabled: Boolean = true, role: Role? = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed && enabled) 1f else 0f, label = "press")
    this
        .graphicsLayer {
            val s = 1f - 0.04f * k
            scaleX = s
            scaleY = s
            alpha = (if (enabled) 1f else 0.35f) - 0.25f * k
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** The app's one button shape. Filled amber is the screen's main action; there is at most one. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    val ink = if (danger) Ink.Red else Ink.Amber
    Box(
        modifier
            .pressable(enabled, onClick = onClick)
            .clip(shape)
            .then(if (filled) Modifier.background(ink) else Modifier.border(1.dp, if (danger) Ink.Red else Ink.Line, shape))
            .defaultMinSize(minWidth = 64.dp, minHeight = 36.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Type.label, color = if (filled) Color.Black else if (danger) Ink.Red else Ink.Text)
    }
}

enum class Glyph {
    Back, Undo, Redo, Plus, Check, Close, Share, Trash, Compare, Camera, Flip,
    Recipe, Monitor, Table, Loupe, Lamp, Lock, Front, Magnet, Arrange, Fit, Grid, Flash, Turn, Film,
}

/** Line icons drawn in a 24-unit box; no icon font, no dependency. */
@Composable
fun Icon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = Ink.Text, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(tint, Offset(x0 * u, y0 * u), Offset(x1 * u, y1 * u), 1.7f * u, StrokeCap.Round)
        fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), tint, style = stroke)
        when (glyph) {
            Glyph.Back -> path { moveTo(14.5f * u, 5f * u); lineTo(7.5f * u, 12f * u); lineTo(14.5f * u, 19f * u) }
            Glyph.Undo, Glyph.Redo -> {
                val flip = glyph == Glyph.Redo
                fun x(v: Float) = (if (flip) 24f - v else v) * u
                path {
                    moveTo(x(9f), 6f * u); lineTo(x(5f), 10f * u); lineTo(x(9f), 14f * u)
                }
                path {
                    moveTo(x(5f), 10f * u); lineTo(x(13.5f), 10f * u)
                    cubicTo(x(17.6f), 10f * u, x(19.5f), 12.2f * u, x(19.5f), 14.5f * u)
                    cubicTo(x(19.5f), 16.8f * u, x(17.6f), 19f * u, x(13.5f), 19f * u)
                    lineTo(x(11f), 19f * u)
                }
            }
            Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.Check -> path { moveTo(5f * u, 12.5f * u); lineTo(10f * u, 17.5f * u); lineTo(19f * u, 7f * u) }
            Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Glyph.Share -> {
                line(12f, 4f, 12f, 15f)
                path { moveTo(7.5f * u, 8.5f * u); lineTo(12f * u, 4f * u); lineTo(16.5f * u, 8.5f * u) }
                path { moveTo(5f * u, 13f * u); lineTo(5f * u, 20f * u); lineTo(19f * u, 20f * u); lineTo(19f * u, 13f * u) }
            }
            Glyph.Trash -> {
                line(5f, 7f, 19f, 7f)
                path { moveTo(9f * u, 7f * u); lineTo(9.6f * u, 4.5f * u); lineTo(14.4f * u, 4.5f * u); lineTo(15f * u, 7f * u) }
                path { moveTo(7f * u, 7f * u); lineTo(8f * u, 20f * u); lineTo(16f * u, 20f * u); lineTo(17f * u, 7f * u) }
            }
            Glyph.Camera -> {
                path {
                    moveTo(3.5f * u, 8f * u); lineTo(7.5f * u, 8f * u); lineTo(9f * u, 5.5f * u); lineTo(15f * u, 5.5f * u)
                    lineTo(16.5f * u, 8f * u); lineTo(20.5f * u, 8f * u); lineTo(20.5f * u, 18.5f * u); lineTo(3.5f * u, 18.5f * u); close()
                }
                drawCircle(tint, 3.4f * u, Offset(12f * u, 13f * u), style = stroke)
            }
            Glyph.Flip -> {
                // two arrows chasing each other round: the other side of the phone
                drawArc(tint, 200f, 140f, false, Offset(4.5f * u, 4.5f * u), Size(15f * u, 15f * u), style = stroke)
                drawArc(tint, 20f, 140f, false, Offset(4.5f * u, 4.5f * u), Size(15f * u, 15f * u), style = stroke)
                path { moveTo(19.6f * u, 5.2f * u); lineTo(19.2f * u, 9.6f * u); lineTo(15f * u, 8.6f * u) }
                path { moveTo(4.4f * u, 18.8f * u); lineTo(4.8f * u, 14.4f * u); lineTo(9f * u, 15.4f * u) }
            }
            Glyph.Compare -> {
                path { moveTo(4f * u, 5f * u); lineTo(20f * u, 5f * u); lineTo(20f * u, 19f * u); lineTo(4f * u, 19f * u); close() }
                line(12f, 3f, 12f, 21f)
            }
            Glyph.Recipe -> path {
                // a bookmark: a look kept for later
                moveTo(7f * u, 4f * u); lineTo(17f * u, 4f * u); lineTo(17f * u, 20f * u); lineTo(12f * u, 16f * u); lineTo(7f * u, 20f * u); close()
            }
            Glyph.Monitor -> {
                path { moveTo(3.5f * u, 5f * u); lineTo(20.5f * u, 5f * u); lineTo(20.5f * u, 16f * u); lineTo(3.5f * u, 16f * u); close() }
                line(9f, 20f, 15f, 20f)
                line(12f, 16f, 12f, 20f)
            }
            Glyph.Table -> {
                // two sheets lying on a table, one turned
                path { moveTo(4f * u, 7f * u); lineTo(13f * u, 5f * u); lineTo(15f * u, 13f * u); lineTo(6f * u, 15f * u); close() }
                path { moveTo(11f * u, 11f * u); lineTo(20f * u, 11f * u); lineTo(20f * u, 19f * u); lineTo(11f * u, 19f * u); close() }
            }
            Glyph.Loupe -> {
                drawCircle(tint, 5.6f * u, Offset(10.5f * u, 10.5f * u), style = stroke)
                line(14.8f, 14.8f, 20f, 20f)
            }
            Glyph.Lamp -> {
                drawArc(tint, 150f, 240f, false, Offset(6.5f * u, 3.5f * u), Size(11f * u, 11f * u), style = stroke)
                path { moveTo(9.3f * u, 13.6f * u); lineTo(9.8f * u, 17f * u); lineTo(14.2f * u, 17f * u); lineTo(14.7f * u, 13.6f * u) }
                line(10.3f, 20f, 13.7f, 20f)
            }
            Glyph.Lock -> {
                path { moveTo(6f * u, 11f * u); lineTo(18f * u, 11f * u); lineTo(18f * u, 20f * u); lineTo(6f * u, 20f * u); close() }
                drawArc(tint, 180f, 180f, false, Offset(8.5f * u, 4.5f * u), Size(7f * u, 9f * u), style = stroke)
            }
            Glyph.Front -> {
                path { moveTo(4f * u, 4f * u); lineTo(14f * u, 4f * u); lineTo(14f * u, 14f * u); lineTo(4f * u, 14f * u); close() }
                path { moveTo(17f * u, 10f * u); lineTo(20f * u, 10f * u); lineTo(20f * u, 20f * u); lineTo(10f * u, 20f * u); lineTo(10f * u, 17f * u) }
            }
            Glyph.Magnet -> {
                path {
                    moveTo(6f * u, 4f * u); lineTo(6f * u, 13f * u)
                    cubicTo(6f * u, 21.5f * u, 18f * u, 21.5f * u, 18f * u, 13f * u)
                    lineTo(18f * u, 4f * u); lineTo(14f * u, 4f * u); lineTo(14f * u, 13f * u)
                    cubicTo(14f * u, 16f * u, 10f * u, 16f * u, 10f * u, 13f * u)
                    lineTo(10f * u, 4f * u); close()
                }
                line(6f, 8f, 10f, 8f)
                line(14f, 8f, 18f, 8f)
            }
            Glyph.Arrange -> {
                for (cx in listOf(4.5f, 13.5f)) for (cy in listOf(4.5f, 13.5f)) {
                    path { moveTo(cx * u, cy * u); lineTo((cx + 6f) * u, cy * u); lineTo((cx + 6f) * u, (cy + 6f) * u); lineTo(cx * u, (cy + 6f) * u); close() }
                }
            }
            Glyph.Fit -> {
                path { moveTo(4f * u, 9f * u); lineTo(4f * u, 4f * u); lineTo(9f * u, 4f * u) }
                path { moveTo(15f * u, 4f * u); lineTo(20f * u, 4f * u); lineTo(20f * u, 9f * u) }
                path { moveTo(20f * u, 15f * u); lineTo(20f * u, 20f * u); lineTo(15f * u, 20f * u) }
                path { moveTo(9f * u, 20f * u); lineTo(4f * u, 20f * u); lineTo(4f * u, 15f * u) }
            }
            Glyph.Grid -> {
                path { moveTo(4f * u, 4f * u); lineTo(20f * u, 4f * u); lineTo(20f * u, 20f * u); lineTo(4f * u, 20f * u); close() }
                line(9.3f, 4f, 9.3f, 20f); line(14.7f, 4f, 14.7f, 20f)
                line(4f, 9.3f, 20f, 9.3f); line(4f, 14.7f, 20f, 14.7f)
            }
            Glyph.Flash -> path {
                moveTo(13.5f * u, 3f * u); lineTo(6.5f * u, 13f * u); lineTo(11.5f * u, 13f * u)
                lineTo(10.5f * u, 21f * u); lineTo(17.5f * u, 11f * u); lineTo(12.5f * u, 11f * u); close()
            }
            Glyph.Turn -> {
                drawArc(tint, -60f, 300f, false, Offset(5f * u, 5f * u), Size(14f * u, 14f * u), style = stroke)
                path { moveTo(15.2f * u, 2.6f * u); lineTo(15.6f * u, 6.4f * u); lineTo(11.8f * u, 6.9f * u) }
            }
            Glyph.Film -> {
                path { moveTo(3.5f * u, 6f * u); lineTo(20.5f * u, 6f * u); lineTo(20.5f * u, 18f * u); lineTo(3.5f * u, 18f * u); close() }
                for (x in listOf(6.5f, 10.2f, 13.8f, 17.5f)) { line(x, 8.3f, x, 8.9f); line(x, 15.1f, x, 15.7f) }
            }
        }
    }
}

/** A round touch target around an icon. */
@Composable
fun IconButton(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Ink.Text) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .pressable(enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, tint = tint)
    }
}

/**
 * One control in the row above the ruler: its value on top in edge lettering
 * (amber once it has been moved), its name below. The selected one carries a
 * small amber mark.
 */
@Composable
fun ParamChip(label: String, value: String, changed: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .pressable(onClick = onClick)
            .defaultMinSize(minWidth = 60.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Txt(value, Type.edge, color = if (changed) Ink.Amber else if (selected) Ink.Text else Ink.Dim)
        Txt(label, Type.caption, color = if (selected) Ink.Text else Ink.Dim)
        Box(
            Modifier
                .size(width = 14.dp, height = 2.dp)
                .background(if (selected) Ink.Amber else Color.Transparent, RoundedCornerShape(1.dp)),
        )
    }
}

/** A hairline. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.height(1.dp).background(Ink.Line))
}

@Composable
fun Gap(width: Dp = 0.dp, height: Dp = 0.dp) {
    Spacer(Modifier.width(width).height(height))
}

/** Row helper kept here so screens read as lists of things, not layout plumbing. */
@Composable
fun Bar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) { content() }
}

internal fun DrawScope.tick(x: Float, top: Float, bottom: Float, color: Color, width: Float) {
    drawLine(color, Offset(x, top), Offset(x, bottom), width, StrokeCap.Butt)
}
