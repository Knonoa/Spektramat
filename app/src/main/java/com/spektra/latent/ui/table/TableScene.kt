package com.spektra.latent.ui.table

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.spektra.latent.data.Loupe
import com.spektra.latent.data.Placement
import com.spektra.latent.data.TableDoc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/*
 * The light table, as geometry and paint.
 *
 * The table has no edges. Photographs and loupes lie on it in table units;
 * the window onto it (where, how close) is the viewer's business and is not
 * part of the document.
 *
 * Film is a transparency. With the lamp on, every photograph multiplies the
 * colour of the light behind it, channel by channel; where two overlap, so do
 * their densities. With the lamp off only the dim table shows through.
 */

/** Where the window is: the table point at the middle of the screen is (-x / s, -y / s). */
data class View(val x: Float = 0f, val y: Float = 0f, val s: Float = 0.45f)

/** The lamp behind the table. */
data class Lamp(val on: Boolean = true, val kelvin: Float = 5000f, val level: Float = 0.9f)

/** A line that shows while a photograph is being dragged into line with another. */
class Guide(val vertical: Boolean, val at: Float, val from: Float, val to: Float)

const val GRID = 50f
const val ANGLE_SNAP = 15f
private const val ANGLE_CATCH = 3.5f

/** Red, green and blue of a black body at [k] kelvin, each 0..1 (Tanner Helland's fit). */
private fun kelvinRgb(k: Float): FloatArray {
    val t = k / 100f
    val r: Float
    val g: Float
    val b: Float
    if (t <= 66f) {
        r = 255f
        g = 99.4708f * ln(t) - 161.1196f
        b = if (t <= 19f) 0f else 138.5177f * ln(t - 10f) - 305.0448f
    } else {
        r = 329.6987f * (t - 60f).pow(-0.1332f)
        g = 288.1222f * (t - 60f).pow(-0.0755f)
        b = 255f
    }
    return floatArrayOf(r.coerceIn(0f, 255f) / 255f, g.coerceIn(0f, 255f) / 255f, b.coerceIn(0f, 255f) / 255f)
}

/** The lamp's colour against the 5000 K a viewing booth uses (the eye takes that for white), never above 1. */
fun illuminant(kelvin: Float): FloatArray {
    val c = kelvinRgb(kelvin)
    val w = kelvinRgb(5000f)
    val rel = FloatArray(3) { c[it] / w[it] }
    val m = rel.max()
    return FloatArray(3) { rel[it] / m }
}

fun lampColour(lamp: Lamp): Color {
    if (!lamp.on) return Color(0xFF46474A)
    val c = illuminant(lamp.kelvin)
    val k = 0.3f + 0.7f * lamp.level
    return Color((c[0] * k).coerceIn(0f, 1f), (c[1] * k).coerceIn(0f, 1f), (c[2] * k).coerceIn(0f, 1f))
}

fun normAngle(a: Float): Float {
    val x = ((a % 360f) + 540f) % 360f - 180f
    return if (abs(x) < 1e-4f) 0f else x
}

fun snapAngle(a: Float): Float {
    val n = round(a / ANGLE_SNAP) * ANGLE_SNAP
    return if (abs(a - n) <= ANGLE_CATCH) n else a
}

/** What a gesture is doing to one thing on the table, before it becomes part of the document. */
data class Delta(val id: Long, val loupe: Boolean, val dx: Float = 0f, val dy: Float = 0f, val factor: Float = 1f, val rotation: Float = 0f)

fun TableDoc.with(d: Delta?): TableDoc {
    if (d == null) return this
    return if (d.loupe) {
        copy(loupes = loupes.map { if (it.id == d.id) it.copy(cx = it.cx + d.dx, cy = it.cy + d.dy, size = (it.size * d.factor).coerceIn(80f, 8000f)) else it })
    } else {
        copy(placements = placements.map {
            if (it.id == d.id) it.copy(cx = it.cx + d.dx, cy = it.cy + d.dy, w = (it.w * d.factor).coerceIn(60f, 20000f), rot = it.rot + d.rotation) else it
        })
    }
}

/** Height over width of each frame's picture, by frame id; 2:3 until it is known. */
typealias Shapes = (Long) -> Float

fun hitPhoto(doc: TableDoc, shape: Shapes, x: Float, y: Float): Placement? =
    doc.placements.sortedByDescending { it.z }.firstOrNull { p ->
        val r = -p.rot * Math.PI.toFloat() / 180f
        val dx = x - p.cx
        val dy = y - p.cy
        val lx = dx * cos(r) - dy * sin(r)
        val ly = dx * sin(r) + dy * cos(r)
        abs(lx) <= p.w / 2 && abs(ly) <= p.w * shape(p.frameId) / 2
    }

fun hitLoupe(doc: TableDoc, x: Float, y: Float): Loupe? = doc.loupes.lastOrNull { hypot(x - it.cx, y - it.cy) <= it.size / 2 }

/** The box around everything on the table: left, top, right, bottom. Null for an empty table. */
fun bounds(doc: TableDoc, shape: Shapes): Rect? {
    var x0 = Float.POSITIVE_INFINITY
    var y0 = Float.POSITIVE_INFINITY
    var x1 = Float.NEGATIVE_INFINITY
    var y1 = Float.NEGATIVE_INFINITY
    for (p in doc.placements) {
        val r = hypot(p.w, p.w * shape(p.frameId)) / 2
        x0 = min(x0, p.cx - r); y0 = min(y0, p.cy - r); x1 = max(x1, p.cx + r); y1 = max(y1, p.cy + r)
    }
    for (l in doc.loupes) {
        x0 = min(x0, l.cx - l.size / 2); y0 = min(y0, l.cy - l.size / 2); x1 = max(x1, l.cx + l.size / 2); y1 = max(y1, l.cy + l.size / 2)
    }
    return if (x0 == Float.POSITIVE_INFINITY) null else Rect(x0, y0, x1, y1)
}

/** The exact rotated photo bounds, grown outwards to an export aspect without cropping. */
internal fun exportBounds(doc:TableDoc,shape:Shapes,ratio:Float?=null):Rect? {
    if(doc.placements.isEmpty())return null
    var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
    var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY
    for(p in doc.placements) {
        val a=p.rot*Math.PI.toFloat()/180f
        val hw=(abs(cos(a))*p.w+abs(sin(a))*p.w*shape(p.frameId))/2
        val hh=(abs(sin(a))*p.w+abs(cos(a))*p.w*shape(p.frameId))/2
        left=min(left,p.cx-hw);right=max(right,p.cx+hw)
        top=min(top,p.cy-hh);bottom=max(bottom,p.cy+hh)
    }
    val margin=28f
    var w=right-left+2*margin;var h=bottom-top+2*margin
    if(ratio!=null && ratio.isFinite() && ratio>0f) {
        if(w/h<ratio)w=h*ratio else h=w/ratio
    }
    val cx=(left+right)/2;val cy=(top+bottom)/2
    return Rect(cx-w/2,cy-h/2,cx+w/2,cy+h/2)
}

/** A point of a photograph, given from its centre along its own axes, as a point of the table. */
fun local(p: Placement, lx: Float, ly: Float): Offset {
    val r = p.rot * Math.PI.toFloat() / 180f
    return Offset(p.cx + lx * cos(r) - ly * sin(r), p.cy + lx * sin(r) + ly * cos(r))
}

/** Half width and half height of a photograph lying square to the table (0, 90, 180, 270 degrees); null when it is turned. */
private fun extents(w: Float, rot: Float, shape: Float): FloatArray? {
    val a = normAngle(rot)
    if (abs(a - round(a / 90f) * 90f) > 0.5f) return null
    val h = w * shape
    val quarter = ((round(a / 90f).toInt() % 2) + 2) % 2
    return if (quarter == 1) floatArrayOf(h / 2, w / 2) else floatArrayOf(w / 2, h / 2)
}

/**
 * Where a dragged photograph settles when snapping is on. On each axis an edge
 * or the centre line catches the nearest edge or centre line of another
 * photograph within a few screen pixels (and a guide shows); otherwise it
 * settles on an unseen grid.
 */
fun magnet(doc: TableDoc, shape: Shapes, d: Delta, view: View): Pair<Delta, List<Guide>> {
    if (d.loupe) {
        val l = doc.loupes.firstOrNull { it.id == d.id } ?: return d to emptyList()
        return d.copy(dx = round((l.cx + d.dx) / GRID) * GRID - l.cx, dy = round((l.cy + d.dy) / GRID) * GRID - l.cy) to emptyList()
    }
    val p = doc.placements.firstOrNull { it.id == d.id } ?: return d to emptyList()
    val w = (p.w * d.factor).coerceIn(60f, 20000f)
    val me = extents(w, p.rot + d.rotation, shape(p.frameId))
    var cx = p.cx + d.dx
    var cy = p.cy + d.dy
    val tolerance = 9f / view.s
    class Catch(val distance: Float, val position: Float, val line: Float, val other: Placement, val otherHalf: FloatArray?)

    fun axis(position: Float, half: Float?, horizontal: Boolean): Catch? {
        val offsets = if (half != null) floatArrayOf(-half, 0f, half) else floatArrayOf(0f)
        var best: Catch? = null
        for (q in doc.placements) {
            if (q.id == p.id) continue
            val e = extents(q.w, q.rot, shape(q.frameId))
            val c = if (horizontal) q.cx else q.cy
            val lines = if (e != null) floatArrayOf(c - (if (horizontal) e[0] else e[1]), c, c + (if (horizontal) e[0] else e[1])) else floatArrayOf(c)
            for (line in lines) for (off in offsets) {
                val distance = abs(position + off - line)
                if (distance < tolerance && (best == null || distance < best.distance)) best = Catch(distance, line - off, line, q, e)
            }
        }
        return best
    }

    val bx = axis(cx, me?.get(0), true)
    val by = axis(cy, me?.get(1), false)
    cx = bx?.position ?: if (me != null) round((cx - me[0]) / GRID) * GRID + me[0] else round(cx / GRID) * GRID
    cy = by?.position ?: if (me != null) round((cy - me[1]) / GRID) * GRID + me[1] else round(cy / GRID) * GRID
    val guides = ArrayList<Guide>()
    if (bx != null) {
        val a = me?.get(1) ?: 0f
        val b = bx.otherHalf?.get(1) ?: 0f
        guides += Guide(true, bx.line, min(cy - a, bx.other.cy - b), max(cy + a, bx.other.cy + b))
    }
    if (by != null) {
        val a = me?.get(0) ?: 0f
        val b = by.otherHalf?.get(0) ?: 0f
        guides += Guide(false, by.line, min(cx - a, by.other.cx - b), max(cx + a, by.other.cx + b))
    }
    return d.copy(dx = cx - p.cx, dy = cy - p.cy) to guides
}

/** Every unlocked photograph laid square in rows on the grid, in the order they roughly lay in. */
fun arranged(doc: TableDoc, shape: Shapes): TableDoc {
    val loose = doc.placements.filter { !it.locked }
    if (loose.isEmpty()) return doc
    val order = loose.sortedWith { a, b -> if (abs(a.cy - b.cy) > 200f) a.cy.compareTo(b.cy) else a.cx.compareTo(b.cx) }
    val columns = kotlin.math.ceil(kotlin.math.sqrt(order.size.toDouble())).toInt()
    val cellW = kotlin.math.ceil(order.maxOf { it.w } / GRID) * GRID
    val cellH = kotlin.math.ceil(order.maxOf { it.w * shape(it.frameId) } / GRID) * GRID
    val b = bounds(doc.copy(placements = order, loupes = emptyList()), shape) ?: return doc
    val x0 = round(b.left / GRID) * GRID
    val y0 = round(b.top / GRID) * GRID
    val moved = HashMap<Long, Placement>()
    order.forEachIndexed { i, p ->
        val h = p.w * shape(p.frameId)
        val left = x0 + (i % columns) * (cellW + GRID) + round((cellW - p.w) / 2 / GRID) * GRID
        val top = y0 + (i / columns) * (cellH + GRID) + round((cellH - h) / 2 / GRID) * GRID
        moved[p.id] = p.copy(cx = left + p.w / 2, cy = top + h / 2, rot = 0f)
    }
    return doc.copy(placements = doc.placements.map { moved[it.id] ?: it })
}

/** What the painter needs to know besides the document. */
class Scene(
    val doc: TableDoc,
    val view: View,
    val lamp: Lamp,
    val image: (Long) -> ImageBitmap?,
    val shape: Shapes,
    /** The table's own faint dots, the selection and the guides: for the screen, never for an export. */
    val marks: Boolean = true,
    val selectedPhoto: Long? = null,
    val selectedLoupe: Long? = null,
    val guides: List<Guide> = emptyList(),
    /** Loupes drawn here as plain magnifiers (where the glass shader cannot run). */
    val plainLoupes: Boolean = false,
)

/** The loupes of a scene in screen pixels: centre x, centre y, radius, power. */
fun Scene.lenses(size: Size): List<FloatArray> = doc.loupes.map {
    floatArrayOf(size.width / 2 + view.x + it.cx * view.s, size.height / 2 + view.y + it.cy * view.s, it.size / 2 * view.s, it.zoom)
}

private fun DrawScope.film(scene: Scene) {
    for (p in scene.doc.placements.sortedBy { it.z }) {
        val h = p.w * scene.shape(p.frameId)
        rotate(p.rot, Offset(p.cx, p.cy)) {
            val topLeft = Offset(p.cx - p.w / 2, p.cy - h / 2)
            val picture = scene.image(p.frameId)
            if (picture != null) {
                translate(topLeft.x, topLeft.y) {
                    scale(p.w / picture.width, h / picture.height, pivot = Offset.Zero) {
                        drawImage(picture, blendMode = BlendMode.Multiply, filterQuality = FilterQuality.Medium)
                    }
                }
            } else {
                drawRect(Color(0xFF969698), topLeft, Size(p.w, h), blendMode = BlendMode.Multiply)
            }
            // the cut edge of the film just shows against the light, even where the film is clear
            drawRect(Color.Black.copy(alpha = 0.22f), topLeft, Size(p.w, h), style = Stroke(min(1.2f / scene.view.s, p.w * 0.004f)))
        }
    }
}

/** Paints the table: the lit surface, the film lying on it, what the loupes do to the light, and the screen's own marks. */
fun DrawScope.drawTable(scene: Scene) {
    val view = scene.view
    val lamp = scene.lamp
    val ink = if (lamp.on) Color(0xFF121316) else Color.White
    drawRect(lampColour(lamp))
    if (lamp.on) {
        // an opal diffuser is a touch brighter in the middle
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.10f)),
                center = Offset(size.width / 2, size.height * 0.45f), radius = hypot(size.width, size.height) * 0.62f,
            ),
        )
    }
    val sx = size.width / 2 + view.x
    val sy = size.height / 2 + view.y
    if (scene.marks) {
        var step = 80f
        while (step * view.s < 22f * density) step *= 2f
        val dot = (1.6f * density).coerceAtMost(max(1f, view.s * 1.8f * density))
        val colour = if (lamp.on) Color.Black.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.08f)
        var wx = kotlin.math.floor(-sx / view.s / step) * step
        while (wx * view.s + sx < size.width + 2) {
            var wy = kotlin.math.floor(-sy / view.s / step) * step
            while (wy * view.s + sy < size.height + 2) {
                drawRect(colour, Offset(wx * view.s + sx - dot / 2, wy * view.s + sy - dot / 2), Size(dot, dot))
                wy += step
            }
            wx += step
        }
    }
    translate(sx, sy) {
        scale(view.s, view.s, pivot = Offset.Zero) {
            val k = 1f / view.s
            film(scene)
            // loupes stand above the film: their soft shadows fall on everything below
            for (l in scene.doc.loupes) {
                val r = l.size / 2
                val c = Offset(l.cx + r * 0.16f, l.cy + r * 0.2f)
                val grey = if (lamp.on) 0.88f else 0.78f
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.White, 0.51f to Color.White, 0.84f to Color(grey, grey, grey), 1f to Color.White,
                        center = c, radius = r * 1.08f,
                    ),
                    r * 1.08f, c, blendMode = BlendMode.Multiply,
                )
            }
            // and each focuses a little of the light into a bright spot
            for (l in scene.doc.loupes) {
                val r = l.size / 2
                val c = Offset(l.cx + r * 0.12f, l.cy + r * 0.15f)
                drawCircle(
                    Brush.radialGradient(listOf(Color(1f, 0.98f, 0.92f, if (lamp.on) 0.4f else 0.22f), Color(1f, 0.98f, 0.92f, 0f)), center = c, radius = r * 0.24f),
                    r * 0.24f, c,
                )
            }
            if (scene.plainLoupes) {
                for (l in scene.doc.loupes) {
                    val r = l.size / 2
                    val centre = Offset(l.cx, l.cy)
                    clipPath(Path().apply { addOval(Rect(centre, r)) }) {
                        drawCircle(lampColour(lamp), r, centre)
                        scale(l.zoom, l.zoom, pivot = centre) { film(scene) }
                    }
                    drawCircle(ink.copy(alpha = 0.55f), r, centre, style = Stroke(2.5f * k))
                    drawCircle(Color.White.copy(alpha = 0.5f), r - 2f * k, centre, style = Stroke(1f * k))
                }
            }
            if (scene.marks) {
                for (g in scene.guides) {
                    val effect = PathEffect.dashPathEffect(floatArrayOf(6f * k * density, 4f * k * density))
                    val colour = MARK
                    if (g.vertical) drawLine(colour, Offset(g.at, g.from - 40 * k), Offset(g.at, g.to + 40 * k), k * density, pathEffect = effect)
                    else drawLine(colour, Offset(g.from - 40 * k, g.at), Offset(g.to + 40 * k, g.at), k * density, pathEffect = effect)
                }
                scene.doc.placements.firstOrNull { it.id == scene.selectedPhoto }?.let { p ->
                    val h = p.w * scene.shape(p.frameId)
                    val off = 7f * k * density
                    rotate(p.rot, Offset(p.cx, p.cy)) {
                        val frame = Stroke(1.5f * k * density, pathEffect = if (p.locked) PathEffect.dashPathEffect(floatArrayOf(8f * k * density, 6f * k * density)) else null)
                        drawRect(MARK, Offset(p.cx - p.w / 2 - off, p.cy - h / 2 - off), Size(p.w + off * 2, h + off * 2), style = frame)
                        if (!p.locked) {
                            val top = p.cy - h / 2 - off
                            val handleY = top - HANDLE_STEM * k * density
                            drawLine(MARK, Offset(p.cx, top), Offset(p.cx, handleY + 11f * k * density), 1.5f * k * density)
                            fun knob(c: Offset, r: Float) {
                                drawCircle(PAPER, r, c)
                                drawCircle(MARK, r, c, style = Stroke(1.5f * k * density))
                            }
                            knob(Offset(p.cx, handleY), 11f * k * density)
                            drawArc(
                                MARK, -162f, 243f, false,
                                Offset(p.cx - 5f * k * density, handleY - 5f * k * density), Size(10f * k * density, 10f * k * density),
                                style = Stroke(1.6f * k * density),
                            )
                            knob(Offset(p.cx + p.w / 2 + off, p.cy + h / 2 + off), 9f * k * density)
                        }
                    }
                }
                scene.doc.loupes.firstOrNull { it.id == scene.selectedLoupe }?.let { l ->
                    drawCircle(MARK, l.size / 2 + 4f * k * density, Offset(l.cx, l.cy), style = Stroke(1.5f * k * density))
                }
            }
        }
    }
}

/** What is chosen is outlined in the app's one light; its handles are paper. */
private val MARK = Color(0xFFFFA51F)
private val PAPER = Color(0xFFF4F1E8)

/** How far above a selected photograph its turning handle stands, in dp at any zoom. */
const val HANDLE_STEM = 34f

/**
 * A loupe as a piece of glass, for Android 13 and later (AGSL).
 *
 * Every pixel inside a lens follows the eye's ray through a plano-convex lens
 * lying above the table: bent at the spherical top (Snell), through the glass,
 * bent again leaving the flat face -- where, near the rim, it may not leave at
 * all -- and down to the table. The height of the lens above the table is
 * solved so that the middle magnifies by exactly the power asked for; the
 * growing distortion towards the rim, the dark ring of total reflection and
 * the colour fringe all come from the trace, three wavelengths of it.
 */
/** Sphere radius over lens radius. Flatter than a half ball: the picture bends towards the rim but never folds over. */
const val LENS_CURVE = 1.5f
/** Crown glass (BK7) at the yellow d line, and how much less it bends red (C line) and more it bends blue (F line). */
const val LENS_ETA = 1.517f
const val LENS_RED = -0.0025f
const val LENS_BLUE = 0.0056f

val LENS_SHADER = """
uniform shader uBoard;
uniform float2 uRes;
uniform float uCount;
uniform float4 uLens[6];
uniform float3 uRoom;

const float ETA = $LENS_ETA;
const float CURVE = $LENS_CURVE;

half3 board(float2 q) {
    if (q.x < 0.0 || q.y < 0.0 || q.x > uRes.x || q.y > uRes.y) { return half3(uRoom); }
    return uBoard.eval(q).rgb;
}

float3 trace(float2 d, float R, float zc, float h, float eta) {
    float zs = sqrt(max(R * R - dot(d, d), 0.0)) - zc;
    float3 n = normalize(float3(d, zs + zc));
    float3 t1 = refract(float3(0.0, 0.0, -1.0), n, 1.0 / eta);
    float2 q = d + t1.xy * (zs / max(-t1.z, 0.0001));
    float3 t2 = refract(t1, float3(0.0, 0.0, 1.0), eta);
    if (dot(t2, t2) < 0.000001) { return float3(0.0); }
    return float3(q + t2.xy * (h / max(-t2.z, 0.0001)), 1.0);
}

half4 main(float2 p) {
    half4 colour = uBoard.eval(p);
    for (int i = 0; i < 6; i++) {
        if (float(i) < uCount) {
            float4 L = uLens[i];
            float2 d = p - L.xy;
            float a = L.z;
            float r = length(d);
            if (r <= a + 1.0) {
                float R = a * CURVE;
                float zc = sqrt(R * R - a * a);
                float t0 = R - zc;
                float M = max(L.w, 1.0001);
                float h = max(0.0, R * (1.0 - 1.0 / M) / (ETA - 1.0) - t0 / ETA);
                float3 tr = trace(d, R, zc, h, ETA + $LENS_RED);
                float3 tg = trace(d, R, zc, h, ETA);
                float3 tb = trace(d, R, zc, h, ETA + $LENS_BLUE);
                half3 c = half3(
                    tr.z > 0.0 ? board(L.xy + tr.xy).r : 0.0,
                    tg.z > 0.0 ? board(L.xy + tg.xy).g : 0.0,
                    tb.z > 0.0 ? board(L.xy + tb.xy).b : 0.0);
                c *= 0.965;
                float zs = sqrt(max(R * R - r * r, 0.0)) - zc;
                float3 n = normalize(float3(d, zs + zc));
                float fresnel = 0.04 + 0.96 * pow(1.0 - clamp(n.z, 0.0, 1.0), 5.0);
                float3 back = reflect(float3(0.0, 0.0, -1.0), n);
                float key = pow(max(dot(back, normalize(float3(-0.42, -0.5, 0.76))), 0.0), 90.0);
                float fill = pow(max(dot(back, normalize(float3(0.5, 0.55, 0.67))), 0.0), 30.0) * 0.25;
                c = mix(c, half3(uRoom), half(fresnel)) + half3(key * 1.4 + fill);
                float rim = smoothstep(a - 2.5, a - 0.5, r);
                c = mix(c, half3(uRoom) * 0.55 + 0.25, half(rim * 0.7));
                float cover = 1.0 - smoothstep(a - 0.6, a + 0.6, r);
                colour = half4(mix(colour.rgb, c, half(cover)), 1.0);
            }
        }
    }
    return colour;
}
"""
