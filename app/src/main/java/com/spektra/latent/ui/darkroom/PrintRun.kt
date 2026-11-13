package com.spektra.latent.ui.darkroom

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spektra.latent.data.PrintJob
import com.spektra.latent.ui.Ink
import com.spektra.latent.ui.LocalReducedMotion
import com.spektra.latent.ui.ScrambleText
import com.spektra.latent.ui.Txt
import com.spektra.latent.ui.Type
import com.spektra.latent.ui.animationsOff
import com.spektra.latent.ui.mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The print run: what the frame shows while a photograph is being printed.
 *
 * The print desk goes through six steps, and so does this:
 *     读取原片 → 曝光 → 显影 → 印相 → 扫描 → 保存
 *
 * It is ONE timeline, not six scenes faded into each other. Every layer of the
 * picture (the unprocessed frame, the negative, the halation, the dye layers,
 * the characters, the paper, the grain, the scan grid, the amber of the save)
 * has its own line in SCORE, a level that changes along the whole run, and
 * every line is continuous. So where one step ends is exactly where the next
 * begins:
 *
 *     the darkroom's own picture drains to a trace of the unprocessed frame
 *     读取   blocks of the frame land on that trace and come into focus: the whole frame
 *     曝光   the frame solarises into its negative, and halation swells over it
 *     显影   the negative gives way to characters, the characters to three dye layers out of
 *            register; the layers close, and closed they ARE the finished picture
 *     印相   the finished picture settles onto paper; one faint flash of the negative, grain arrives
 *     扫描   a pixel grid comes up over the print and goes again
 *     保存   blocks light amber, in the order they were read, and the paper draws back until the
 *            picture fills the frame again: what the darkroom shows when the run is over
 *
 * Position on the timeline is `s`: step index + how far through that step, 0..6.
 *
 * The run never jumps. The engine reports the step it is in; the picture walks
 * towards it at a bounded pace, so every joint is seen even when the work is
 * over at once. The plates are the real ones: the unprocessed frame and the
 * engine's own proof. What happens between them is choreography, not the
 * engine's intermediate data.
 */

/** The six steps, and the share of the bar each takes. */
internal val PRINT_STEPS = listOf("读取原片" to .55f, "曝光" to .75f, "显影" to 2.4f, "印相" to 1.1f, "扫描" to .95f, "保存" to .5f)
private const val N = 6f

/** How much of the whole run is done at position [s], by the steps' weights. */
internal fun printDone(s: Float): Float {
    val k = min(5, floor(s).toInt()).coerceAtLeast(0)
    var before = 0f
    for (i in 0 until k) before += PRINT_STEPS[i].second
    return ((before + PRINT_STEPS[k].second * (s - k)) / PRINT_STEPS.sumOf { it.second.toDouble() }.toFloat()).coerceIn(0f, 1f)
}

private fun ease(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** One line of the score: (s, level) pairs, eased between. To retime a joint, move a number; a line must stay continuous. */
private class Line(private vararg val points: Float) {
    fun at(s: Float): Float {
        if (s <= points[0]) return points[1]
        var i = 2
        while (i < points.size) {
            if (s <= points[i]) return points[i - 1] + (points[i + 1] - points[i - 1]) * ease(points[i - 2], points[i], s)
            i += 2
        }
        return points[points.size - 1]
    }
}

private object Score {
    val veil = Line(0f, 0f, .06f, 1f)                                  // the run itself coming up over the frame
    val dev = Line(0f, 1f, .25f, 0f, 2.9f, 0f, 3f, 1f)                 // the finished look: drains off first, is back for good once the dyes close
    val ghost = Line(0f, 0f, .25f, .3f, 1f, .3f, 1.01f, 0f)            // a trace of the unprocessed frame
    val land = Line(.08f, 0f, .72f, 1f)                                // 读取: how many blocks have landed
    val blocks = Line(0f, 1f, 1f, 1f, 1.01f, 0f)
    val flat = Line(.6f, 0f, 1f, 1f, 1.7f, 1f, 1.701f, 0f)             // the frame in focus (the negative is opaque over it by 1.7)
    val invert = Line(1f, 0f, 1.7f, 1f)                                // 曝光: positive -> negative, through solarisation
    val neg = Line(1.699f, 0f, 1.7f, 1f, 2.05f, 1f, 2.55f, 0f)
    val glow = Line(1.05f, 0f, 1.6f, 1f, 2f, 1f, 2.4f, 0f)             // halation
    val wash = Line(1f, 0f, 1.9f, 1f, 2.35f, 0f)                       // the colour of the light it was exposed by
    val glyph = Line(1.7f, 0f, 2.15f, 1f, 2.3f, 1f, 2.8f, 0f)          // 显影: the picture held as characters
    val dye = Line(2f, 0f, 2.4f, 1f)                                   // the dye layers…
    val spread = Line(2f, 1f, 2.25f, 1f, 2.9f, 0f)                     // …and how far out of register they are
    val mount = Line(3f, 0f, 3.55f, 1f, 5.35f, 1f, 5.9f, 0f)           // 印相: the paper border; draws back during 保存
    val flash = Line(3f, 0f, 3.12f, 1f, 3.4f, 0f)                      // one faint return of the negative
    val grain = Line(3.1f, 0f, 3.9f, 1f)
    val lamp = Line(3f, 0f, 3.5f, 1f, 4.1f, 1f, 4.6f, 0f)              // a still top light on the paper
    val grid = Line(4f, 0f, 4.25f, 1f, 4.5f, 1f, 4.95f, 0f)            // 扫描
    val commit = Line(5f, 0f, 5.7f, 1f)                                // 保存: the amber passing over the blocks
}

/** Where a run is. */
@Stable
internal class PrintRunState {
    /** Step index + how far through it, 0..6. */
    var position by mutableFloatStateOf(0f)
    /** Seconds since the run began, for the things that breathe. */
    var time by mutableFloatStateOf(0f)
    /** The run's own opacity over the frame: it lets the frame's own picture back through at the end. */
    var fade by mutableFloatStateOf(1f)
    /** Over: the foot goes back to what it was. */
    var over by mutableStateOf(false)
    var failed by mutableStateOf<String?>(null)
}

/** Walks towards the engine's real progress at a bounded pace: never a jump, and every joint is seen. */
@Composable
internal fun rememberPrintRun(job: PrintJob?): PrintRunState? {
    val context = LocalContext.current
    val quiet = LocalReducedMotion.current || animationsOff(context)
    val run = remember(job?.id) { if (job == null) null else PrintRunState() }
    LaunchedEffect(job?.id, quiet) {
        if (job == null || run == null) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(.25f)
            last = now
            run.time += dt
            val state = job.state
            if (state is PrintJob.State.Failed) {
                run.failed = state.reason
                run.over = true
                return@LaunchedEffect
            }
            val target = when (state) {
                is PrintJob.State.Done -> N
                is PrintJob.State.Working -> (job.stage + .72f).coerceAtMost(5.72f)
                else -> 0f
            }
            val gap = target - run.position
            if (gap > 0f) run.position = if (quiet) target else min(target, run.position + (gap * 5f).coerceIn(.35f, 2.4f) * dt)
            if (state is PrintJob.State.Done && run.position >= N) break
        }
        // the full bar stays a moment while the frame's own picture comes back through
        val out = Animatable(1f)
        out.animateTo(0f, tween(if (quiet) 0 else 450)) { run.fade = value }
        run.over = true
    }
    return run
}

/** While a print runs the foot is one thing only: the step it is in, how far, one line. No row of numbered steps. */
@Composable
internal fun PrintBar(run: PrintRunState, modifier: Modifier = Modifier) {
    val done = printDone(run.position)
    val step = PRINT_STEPS[min(5, floor(run.position).toInt())].first
    val accent = Ink.Accent
    val track = Ink.Line2
    Column(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "冲印进度，$step"
            progressBarRangeInfo = ProgressBarRangeInfo(done, 0f..1f)
        },
        verticalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ScrambleText(step, Type.body.copy(fontSize = 13.sp, letterSpacing = .14.em, lineHeight = 16.sp), stagger = 35, delay = 50)
            Box(Modifier.weight(1f))
            Txt("${(done * 100).roundToInt()}%", Type.num.copy(fontWeight = FontWeight.SemiBold, lineHeight = 16.sp), color = accent)
        }
        Box(
            Modifier.fillMaxWidth().height(7.dp).drawBehind {
                val h = 3.dp.toPx()
                val y = (size.height - h) / 2
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                val w = max(h, size.width * done)
                drawRoundRect(accent, Offset(0f, y), Size(w, h), CornerRadius(h / 2))
                // the head of the bar is a bead of the same light
                drawCircle(accent.copy(alpha = .35f), 6.dp.toPx(), Offset(w, size.height / 2))
                drawCircle(accent, 3.5.dp.toPx(), Offset(w, size.height / 2))
            },
        )
    }
}

private const val RAMP = " ·:-=+<>/*|%&#$@"         // light to heavy: the character a cell's brightness picks
private const val BLOCKS = 26                        // blocks along the long edge, for the read and for the save
private const val E = .004f                          // below this a layer is not worth drawing
private val PAPER = Color(0xFFEDE7DA)

private fun hash(x: Float, y: Float): Float {
    val v = sin(x * 127.1f + y * 311.7f) * 43758.547f
    return v - floor(v)
}

/** Everything the run draws from, made once at the start: nothing is allocated while it plays. */
private class Plates(
    val flat: ImageBitmap, val flatFrom: Rect,
    val dev: ImageBitmap, val devFrom: Rect,
    val glow: ImageBitmap,
    val cols: Int, val rows: Int, val block: IntArray, val order: FloatArray,
    val gc: Int, val gr: Int, val cells: IntArray,
    val grain: Brush, val lines: Brush,
)

private fun intRect(r: Rect) = android.graphics.Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())

private fun sample(src: Bitmap, from: Rect, w: Int, h: Int): IntArray {
    val small = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(small).drawBitmap(
        src, intRect(from), android.graphics.Rect(0, 0, small.width, small.height), Paint(Paint.FILTER_BITMAP_FLAG),
    )
    val px = IntArray(small.width * small.height)
    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
    small.recycle()
    return px
}

private fun makePlates(original: ImageBitmap?, proof: ImageBitmap, gate: Rect, w: Int, h: Int, dp: Float, bw: Boolean): Plates {
    val dev = proof.asAndroidBitmap()
    val devFrom = Rect(gate.left * dev.width, gate.top * dev.height, gate.right * dev.width, gate.bottom * dev.height)
    val flat = original ?: proof
    val flatBitmap = flat.asAndroidBitmap()
    // the unprocessed frame, laid out to cover the picture the way the proof does
    val flatFrom = if (original == null) devFrom else {
        val k = max(w.toFloat() / flatBitmap.width, h.toFloat() / flatBitmap.height)
        val sw = w / k
        val sh = h / k
        Rect((flatBitmap.width - sw) / 2, (flatBitmap.height - sh) / 2, (flatBitmap.width + sw) / 2, (flatBitmap.height + sh) / 2)
    }
    // the block grid, shared by the read and the save, and the scattered order the blocks go in
    val cols = if (w >= h) BLOCKS else max(2, (BLOCKS.toFloat() * w / h).roundToInt())
    val rows = if (w >= h) max(2, (BLOCKS.toFloat() * h / w).roundToInt()) else BLOCKS
    val order = FloatArray(cols * rows)
    for (j in 0 until rows) for (i in 0 until cols) order[j * cols + i] = hash(i * .7f, j * .9f) * .85f + .15f * (i + j) / (cols + rows)
    // the picture as characters: one sample per cell
    val cell = max(dp * 7f, (max(w, h) / 72f).roundToInt().toFloat())
    val gc = ceil(w / cell).toInt()
    val gr = ceil(h / cell).toInt()
    // halation: the look, small, to be drawn back large
    val glow = Bitmap.createBitmap(max(1, w / 24), max(1, h / 24), Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(glow).drawBitmap(dev, intRect(devFrom), android.graphics.Rect(0, 0, glow.width, glow.height), Paint(Paint.FILTER_BITMAP_FLAG))
    // grain and the scan grid, as patterns
    val noise = IntArray(96 * 96) {
        val v = (Math.random() * 255).toInt()
        (46 shl 24) or (v shl 16) or (v shl 8) or v
    }
    val grain = Bitmap.createBitmap(noise, 96, 96, Bitmap.Config.ARGB_8888).asImageBitmap()
    val q = max(3, (4 * dp).roundToInt())
    val lineInk = (if (bw) Color(0xFF141414) else Color(0xFF3B2A12)).toArgb()
    val lines = Bitmap.createBitmap(IntArray(q * q) { if (it < q || it % q == 0) lineInk else 0 }, q, q, Bitmap.Config.ARGB_8888).asImageBitmap()
    return Plates(
        flat, flatFrom, proof, devFrom, glow.asImageBitmap(),
        cols, rows, sample(flatBitmap, flatFrom, cols, rows), order,
        gc, gr, sample(dev, devFrom, gc, gr),
        ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated)),
        ShaderBrush(ImageShader(lines, TileMode.Repeated, TileMode.Repeated)),
    )
}

private fun tint(r: Float, g: Float, b: Float) = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

// The emulsion's own layers: yellow keeps back blue, magenta green, cyan red. Added together at the weights given
// they make the finished look again, which is what lets 显影 hand over to 印相 unseen.
// Black-and-white: a cool silver pass and a warm selenium one.
private class Dye(val filter: ColorFilter, val alpha: Float, val dx: Float, val dy: Float)

private val COLOUR_DYES = listOf(Dye(tint(1f, 1f, 0f), .5f, -1f, -.45f), Dye(tint(1f, 0f, 1f), .5f, .62f, 1f), Dye(tint(0f, 1f, 1f), .5f, -.34f, -.9f))
private val SILVER_DYES = listOf(Dye(tint(.84f, .9f, 1f), .55f, -1f, -.45f), Dye(tint(1f, .9f, .78f), .55f, .62f, 1f))

/** The negative of the frame: turned inside out, under its orange mask if it is a colour negative, without colour if it is black-and-white. */
private fun negative(mask: Color?, mono: Boolean): ColorFilter {
    val r = mask?.red ?: 1f
    val g = mask?.green ?: 1f
    val b = mask?.blue ?: 1f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            if (mono) floatArrayOf(
                -.3f, -.59f, -.11f, 0f, 255f,
                -.3f, -.59f, -.11f, 0f, 255f,
                -.3f, -.59f, -.11f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ) else floatArrayOf(
                -r, 0f, 0f, 0f, 255f * r,
                0f, -g, 0f, 0f, 255f * g,
                0f, 0f, -b, 0f, 255f * b,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/**
 * The run, laid over the picture inside the gate. [gate] is where the picture is inside [proof] (the film around
 * it stays as it is). [kind]: "neg", "pos", "cine" or "bw". [grain] and [halo] 0..1, from the look.
 */
@Composable
internal fun PrintRun(
    original: ImageBitmap?, proof: ImageBitmap, gate: Rect, kind: String, grain: Float, halo: Float,
    run: PrintRunState, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (LocalReducedMotion.current || animationsOff(context)) return      // the picture is left alone; only the bar moves
    val face = remember { mono(context) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textAlign = Paint.Align.CENTER } }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var plates by remember { mutableStateOf<Plates?>(null) }
    val bw = kind == "bw"
    LaunchedEffect(original, proof, gate, area, bw) {
        if (area.width <= 0 || area.height <= 0) return@LaunchedEffect
        val dp = context.resources.displayMetrics.density
        plates = withContext(Dispatchers.Default) { runCatching { makePlates(original, proof, gate, area.width, area.height, dp, bw) }.getOrNull() }
    }
    // a colour negative carries its orange mask; black-and-white film has no colour at all; reversal film has neither
    val mask = if (bw || kind == "pos") null else Color(0xFFF4B27A)
    val negFilter = remember(mask, bw) { negative(mask, bw) }
    val dyes = if (bw) SILVER_DYES else COLOUR_DYES
    val warm = if (kind == "cine") Color(0xFFFF6A44) else Color(0xFFFFD9A0)
    val haloLevel = .25f + .5f * halo.coerceIn(0f, 1f)
    val grainLevel = grain.coerceIn(0f, 1f) * .4f
    val blends = Build.VERSION.SDK_INT >= 29      // difference, saturation, overlay and soft light are not to be had before

    Canvas(
        modifier.onSizeChanged { area = it }.graphicsLayer {
            alpha = if (plates == null) 0f else Score.veil.at(run.position) * run.fade
            compositingStrategy = CompositingStrategy.Offscreen
            clip = true
        },
    ) {
        val p = plates ?: return@Canvas
        val s = run.position
        val t = run.time
        val w = this.size.width
        val h = this.size.height
        val whole = IntSize(w.toInt(), h.toInt())
        val breathe = .5f + .5f * sin(t * 1.6f)
        val d = .042f * min(w, h)

        fun plate(image: ImageBitmap, from: Rect, alpha: Float, filter: ColorFilter? = null, blend: BlendMode = BlendMode.SrcOver, dx: Float = 0f, dy: Float = 0f, to: Rect? = null) {
            drawImage(
                image,
                srcOffset = IntOffset(from.left.roundToInt(), from.top.roundToInt()),
                srcSize = IntSize(from.width.roundToInt().coerceAtLeast(1), from.height.roundToInt().coerceAtLeast(1)),
                dstOffset = IntOffset(((to?.left ?: 0f) + dx).roundToInt(), ((to?.top ?: 0f) + dy).roundToInt()),
                dstSize = if (to == null) whole else IntSize(to.width.roundToInt(), to.height.roundToInt()),
                alpha = alpha.coerceIn(0f, 1f), colorFilter = filter, blendMode = blend, filterQuality = FilterQuality.Low,
            )
        }

        drawRect(Color.Black)

        // ---- 读取原片
        val ghost = Score.ghost.at(s)
        if (ghost > E) plate(p.flat, p.flatFrom, ghost)
        val blocks = Score.blocks.at(s)
        val land = Score.land.at(s)
        if (blocks > E && land > 0f) {
            // the frame at block size; each block fades up as its turn comes
            val cw = w / p.cols
            val ch = h / p.rows
            for (j in 0 until p.rows) for (c in 0 until p.cols) {
                val a = ease(0f, .1f, land * 1.1f - p.order[j * p.cols + c])
                if (a <= 0f) continue
                val x0 = (c * cw).roundToInt().toFloat()
                val y0 = (j * ch).roundToInt().toFloat()
                drawRect(
                    Color(p.block[j * p.cols + c]).copy(alpha = a * blocks), Offset(x0, y0),
                    Size(((c + 1) * cw).roundToInt() - x0, ((j + 1) * ch).roundToInt() - y0),
                )
            }
        }
        val flat = Score.flat.at(s)
        if (flat > E) plate(p.flat, p.flatFrom, flat)

        // ---- 曝光: |frame - c| with c rising from black to white is the Sabattier effect, and at white it is the
        // negative. The picture never fades out on its way over: it turns inside out.
        val invert = Score.invert.at(s)
        if (flat > E && invert > E) {
            if (blends) {
                drawRect(Color(invert, invert, invert), blendMode = BlendMode.Difference)
                if (mask != null) drawRect(mask, alpha = invert, blendMode = BlendMode.Multiply)
                if (bw) drawRect(Color.Gray, alpha = invert, blendMode = BlendMode.Saturation)
            } else plate(p.flat, p.flatFrom, invert, negFilter)
        }
        val neg = Score.neg.at(s)
        if (neg > E) plate(p.flat, p.flatFrom, neg, negFilter)
        val glow = Score.glow.at(s)
        if (glow > E) plate(p.glow, Rect(0f, 0f, p.glow.width.toFloat(), p.glow.height.toFloat()), glow * haloLevel * (.3f + .3f * breathe), blend = BlendMode.Plus)
        val wash = Score.wash.at(s)
        if (wash > E) drawRect(warm, alpha = wash * .1f, blendMode = BlendMode.Plus)

        // ---- 显影: the dye layers, swaying while they are apart
        val dev = Score.dev.at(s)
        val dye = Score.dye.at(s)
        if (dye > E && dev < 1f) {
            val spread = Score.spread.at(s)
            val off = spread * d + sin(t * 1.35f) * .2f * d * spread
            for (layer in dyes) plate(p.dev, p.devFrom, dye * layer.alpha, layer.filter, BlendMode.Plus, off * layer.dx, off * layer.dy)
        }
        // …under the characters, with everything behind them hushed while they hold the picture
        val glyph = Score.glyph.at(s)
        if (glyph > E) {
            drawRect(Color.Black, alpha = glyph * .5f)
            val cell = max(7.dp.toPx(), (max(w, h) / 72f).roundToInt().toFloat())
            val flick = floor(t * 8f)
            val top = RAMP.length - 1
            paint.textSize = cell * .95f
            val centre = -(paint.ascent() + paint.descent()) / 2f
            val chars = RAMP.toCharArray()
            drawIntoCanvas { canvas ->
                for (j in 0 until p.gr) for (c in 0 until p.gc) {
                    val px = p.cells[j * p.gc + c]
                    val r = px shr 16 and 0xff
                    val g = px shr 8 and 0xff
                    val b = px and 0xff
                    val lum = (.2126f * r + .7152f * g + .0722f * b) / 255f
                    val k = ((.2f + lum * (.72f + .28f * (hash(c.toFloat(), j + flick) - .5f))) * top).roundToInt()
                    if (k < 1) continue
                    // bright cells carry the picture, dark ones stand back
                    paint.setARGB(
                        ((.4f + .6f * lum) * glyph * 255).roundToInt().coerceIn(0, 255),
                        min(255, (r * 1.12f + 20).roundToInt()), min(255, (g * 1.12f + 20).roundToInt()), min(255, (b * 1.12f + 20).roundToInt()),
                    )
                    canvas.nativeCanvas.drawText(chars, min(top, k), 1, c * cell + cell / 2, j * cell + cell / 2 + centre, paint)
                }
            }
        }

        // ---- the finished picture, in its mount once there is paper
        val border = Score.mount.at(s) * .045f * min(w, h)
        val inner = Rect(border, border, w - border, h - border)
        if (dev > E) {
            if (border > .01f) drawRect(PAPER)
            plate(p.dev, p.devFrom, dev, to = inner)
        }
        if (s < 3f) return@Canvas

        clipRect(inner.left, inner.top, inner.right, inner.bottom) {
            // ---- 印相
            val flash = Score.flash.at(s)
            if (flash > E && kind != "pos") plate(p.flat, p.flatFrom, flash * .22f, negFilter, to = inner)      // faint: the print must not wash out
            val grains = Score.grain.at(s)
            if (grains > E && grainLevel > .01f && blends) {
                val ox = floor(t * 97f) % 96f
                val oy = floor(t * 61f) % 96f
                translate(-ox, -oy) { drawRect(p.grain, Offset(ox, oy), Size(w, h), alpha = grains * grainLevel, blendMode = BlendMode.Overlay) }
            }
            val lamp = Score.lamp.at(s)
            if (lamp > E && blends) {
                val at = Offset(inner.left + inner.width * .2f, inner.top)
                drawRect(
                    Brush.radialGradient(0f to Color.White.copy(alpha = .7f), 1f to Color.Transparent, center = at, radius = inner.width * .9f),
                    inner.topLeft, inner.size, alpha = lamp * .4f, blendMode = BlendMode.Softlight,
                )
            }
            // ---- 扫描
            val grid = Score.grid.at(s)
            if (grid > E) {
                drawRect(p.lines, inner.topLeft, inner.size, alpha = grid * .22f)
                // a colour fringe, as a sensor reads it
                plate(p.dev, p.devFrom, grid * .14f, dyes.last().filter, BlendMode.Plus, dx = 1f + 2f * grid, to = inner)
            }
            // ---- 保存: each block lights amber and settles, in the order it was read
            val commit = Score.commit.at(s)
            if (commit > 0f && commit < 1f) {
                val cw = inner.width / p.cols
                val ch = inner.height / p.rows
                for (j in 0 until p.rows) for (n in 0 until p.cols) {
                    val u = commit * 1.3f - p.order[j * p.cols + n]
                    if (u <= 0f || u >= .3f) continue
                    val x0 = (inner.left + n * cw).roundToInt().toFloat()
                    val y0 = (inner.top + j * ch).roundToInt().toFloat()
                    drawRect(
                        Ink.Amber, Offset(x0, y0),
                        Size((inner.left + (n + 1) * cw).roundToInt() - x0, (inner.top + (j + 1) * ch).roundToInt() - y0),
                        alpha = .26f * ease(0f, .1f, u) * (1f - ease(.1f, .3f, u)), blendMode = BlendMode.Plus,
                    )
                }
            }
        }
    }
}
