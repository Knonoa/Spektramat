package com.spektra.latent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Line icons on a 24-unit grid, 1.5 units thick, round caps and joins.
 * The outlines are the ones in the H5 design (js/core.js), kept as path data
 * so that an icon is one line here as it is there. No icon font, no dependency.
 */

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 0f): String =
    if (r <= 0f) "M$x ${y}h${w}v${h}h${-w}z"
    else "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

/** One outline of an icon; [fill] for the few parts that are solid. */
class GlyphPart(data: String, val fill: Boolean = false) {
    val path: Path by lazy { PathParser().parsePathString(data).toPath() }
}

private fun line(vararg data: String) = data.map { GlyphPart(it) }

enum class Glyph(val parts: List<GlyphPart>) {
    Camera(line("M3.5 8.5a2 2 0 0 1 2-2h2.2l1.3-2h6l1.3 2h2.2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z", circle(12f, 13f, 3.6f))),
    Monitor(line(rect(3f, 4.5f, 18f, 12f, 2f), "M8.5 20h7M12 16.5V20", "m10.4 8.4 3.6 2.1-3.6 2.1z")),
    Lab(line(rect(3.5f, 3.5f, 7f, 7f, 1f), rect(13.5f, 3.5f, 7f, 7f, 1f), rect(3.5f, 13.5f, 7f, 7f, 1f), rect(13.5f, 13.5f, 7f, 7f, 1f))),
    Darkroom(line(circle(12f, 12f, 8.5f), "M12 3.5v17M12 7.5h7.2M12 12h8.5M12 16.5h7.2")),
    Table(line(rect(3f, 5f, 18f, 12f, 1.5f), "M3 20.5h18", circle(10f, 10.6f, 2.6f), "m12 12.6 2.4 2.4")),
    Settings(line("M4 7h8M17 7h3M4 12h3M12 12h8M4 17h10M19 17h1", circle(14.5f, 7f, 2.2f), circle(9.5f, 12f, 2.2f), circle(16.5f, 17f, 2.2f))),
    Plus(line("M12 5v14M5 12h14")),
    Close(line("M6.5 6.5l11 11M17.5 6.5l-11 11")),
    Back(line("M14.5 5.5 8 12l6.5 6.5")),
    Chevron(line("M9.5 5.5 16 12l-6.5 6.5")),
    Check(line("m5 12.5 4.5 4.5L19 7.5")),
    Undo(line("m8.5 5.5-4 4 4 4M5 9.5h9a5.5 5.5 0 0 1 0 11h-3")),
    Redo(line("m15.5 5.5 4 4-4 4M19 9.5h-9a5.5 5.5 0 0 0 0 11h3")),
    Share(line("M12 15V4M8 7.5l4-4 4 4M5 13v5.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V13")),
    Select(line(circle(12f, 12f, 8.5f), "m8.4 12.2 2.5 2.5 4.7-5.1")),
    Album(line(rect(3.5f, 4.5f, 17f, 15f, 2f), "m3.5 15.5 4.5-4.5 4 4 3-3 5.5 5.5", circle(15.5f, 9f, 1.4f))),
    File(line("M6.5 3.5h7l4 4v12a1 1 0 0 1-1 1h-10a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1z", "M13.5 3.5v4h4")),
    Folder(line("M3.5 7A1.5 1.5 0 0 1 5 5.5h4l2 2.2h8A1.5 1.5 0 0 1 20.5 9.2v8.3A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z")),
    Trash(line("M4.5 7h15M9.5 7V4.5h5V7M6.5 7l.8 12a1 1 0 0 0 1 .9h7.4a1 1 0 0 0 1-.9l.8-12M10 11v5M14 11v5")),
    Flash(line("M13 3 6 13.5h5L10.5 21l7-10.5h-5z")),
    Grid(line(rect(4f, 4f, 16f, 16f, 1.5f), "M9.3 4v16M14.7 4v16M4 9.3h16M4 14.7h16")),
    Flip(line("M4.5 12a7.5 7.5 0 0 1 13-5.1L19.5 9M19.5 12a7.5 7.5 0 0 1-13 5.1L4.5 15", "M19.5 4.5V9H15M4.5 19.5V15H9")),
    Sun(line(circle(12f, 12f, 3.6f), "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4")),
    Moon(line("M19.5 14.2A8 8 0 0 1 9.8 4.5a8 8 0 1 0 9.7 9.7z")),
    Auto(listOf(GlyphPart(circle(12f, 12f, 8.5f)), GlyphPart("M12 3.5a8.5 8.5 0 0 1 0 17z", fill = true))),
    Loupe(line(circle(10.5f, 10.5f, 6f), "m15 15 5.5 5.5")),
    Lock(line(rect(5.5f, 10.5f, 13f, 9.5f, 2f), "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")),
    Front(line("m12 4 8.5 4.5L12 13 3.5 8.5z", "m3.5 12.5 8.5 4.5 8.5-4.5M3.5 16.5 12 21l8.5-4.5")),
    Plug(line("M9 3.5v5M15 3.5v5M6.5 8.5h11v3a5.5 5.5 0 0 1-11 0zM12 17v3.5")),
    More(listOf(GlyphPart(circle(5.5f, 12f, 1.3f), true), GlyphPart(circle(12f, 12f, 1.3f), true), GlyphPart(circle(18.5f, 12f, 1.3f), true))),
    Film(line(rect(4f, 5f, 16f, 14f, 1.5f), "M8 5v14M16 5v14M4 9.5h4M4 14.5h4M16 9.5h4M16 14.5h4")),
    Recipe(line("M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z")),
    Compare(line(rect(3.5f, 5.5f, 17f, 13f, 1.5f), "M12 3v18M8.5 9.5 6 12l2.5 2.5M15.5 9.5 18 12l-2.5 2.5")),
    Arrange(line(rect(4f, 4f, 6.5f, 9f, 1f), rect(13.5f, 4f, 6.5f, 5.5f, 1f), rect(13.5f, 12.5f, 6.5f, 7.5f, 1f), rect(4f, 16f, 6.5f, 4f, 1f))),
    Fit(line("M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15")),
    Lamp(line("M9 17.5h6M10 20.5h4M8.2 14.5a5.5 5.5 0 1 1 7.6 0c-.7.7-.8 1.3-.8 3H9c0-1.7-.1-2.3-.8-3z")),
    Shutter(line(circle(12f, 12f, 8.5f), circle(12f, 12f, 4.5f))),
    Date(line(rect(4f, 5.5f, 16f, 14.5f, 2f), "M4 10h16M8.5 3.5v4M15.5 3.5v4")),
    Video(line(rect(3.5f, 6.5f, 12f, 11f, 2f), "m15.5 10.5 5-2.5v8l-5-2.5")),
    Magnet(line("M6 4.5v8a6 6 0 0 0 12 0v-8h-4v8a2 2 0 0 1-4 0v-8zM6 8h4M14 8h4")),
    Info(line(circle(12f, 12f, 8.5f), "M12 11v5.5M12 7.7v.3")),
    Download(line("M12 4v11M8 11.5l4 4 4-4M5 19.5h14")),
    /** The back of a 35 mm camera: what is loaded in it. */
    Back35(line(rect(3.5f, 5f, 17f, 14f, 2.5f), rect(7f, 8.5f, 10f, 7f, 1f), "M5.5 8v0M5.5 12v0M5.5 16v0")),
    Panel(line(rect(3.5f, 4.5f, 17f, 15f, 2.5f), "M9.5 4.5v15")),
    Histogram(line("M3.5 19.5h17", "M4.5 16.5c2.2 0 2.5-8 4.5-8s2.2 4.5 3.8 4.5 2.3-7.5 3.9-7.5 1.9 11 2.8 11")),
    /** Turning a print on the table. Not in the H5 set: the app has a rotation tool the prototype did not. */
    Turn(line("M19.5 12a7.5 7.5 0 1 1-2.4-5.5", "M19.5 4.5V9H15")),
}

private val LINE = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun Icon(glyph: Glyph, modifier: Modifier = Modifier, tint: Color = Ink.Text, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        scale(u, u, pivot = Offset.Zero) {
            for (part in glyph.parts) {
                if (part.fill) drawPath(part.path, tint) else drawPath(part.path, tint, style = LINE)
            }
        }
    }
}

// the brand mark: a hexagonal prism seen down its axis, one amber point of light at the centre
private val MARK_HEX by lazy { PathParser().parsePathString("M16 5.8 24.9 10.9v10.2L16 26.2 7.1 21.1V10.9L16 5.8Z").toPath() }
private val MARK_AXES by lazy { PathParser().parsePathString("M16 5.8v10.5l8.9 5.1M16 16.3l-8.9 5.1M16 16.3v9.9").toPath() }

@Composable
fun Mark(modifier: Modifier = Modifier, tint: Color = Ink.Text, size: Dp = 29.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 32f
        scale(u, u, pivot = Offset.Zero) {
            drawCircle(tint.copy(alpha = tint.alpha * .38f), 13.4f, Offset(16f, 16f), style = Stroke(1f))
            drawPath(MARK_HEX, tint, style = Stroke(1.05f, join = StrokeJoin.Round))
            drawPath(MARK_AXES, tint.copy(alpha = tint.alpha * .5f), style = Stroke(.85f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Ink.Amber, 2.1f, Offset(16f, 16.2f))
        }
    }
}

/** The mark as a button: the handle of the sidebar. */
@Composable
fun AppMark(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "打开导航") {
    Box(
        modifier.size(44.dp).pressable(onClick = onClick, sink = .9f).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Mark()
    }
}
