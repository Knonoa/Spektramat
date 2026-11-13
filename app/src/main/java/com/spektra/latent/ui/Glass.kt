package com.spektra.latent.ui

import android.graphics.BlurMaskFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The glass material.
 *
 * Rule of the house: glass only sits on something worth seeing through -- a
 * photograph, the viewfinder, the character matrix. What is behind it is
 * recorded once into a layer ([backdropSource]); each piece of glass draws its
 * part of that layer again, blurred and a little more saturated, and puts its
 * tint, its sheen and its lit rim on top. Before Android 12 there is no blur
 * to be had and the glass is the tint alone.
 */

val LocalReducedMotion = staticCompositionLocalOf { false }

/** Opens the sidebar. */
val LocalNavigation = staticCompositionLocalOf<() -> Unit> { {} }

/** What lies behind the glass of one page. */
@Stable
class Backdrop {
    internal var layer by mutableStateOf<GraphicsLayer?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
    internal var extent by mutableStateOf(IntSize.Zero)
    /** Counts the times the source has been drawn again; glass that reads it follows. */
    internal var tick by mutableIntStateOf(0)
    /** The source changes without Compose drawing it again (a camera stream): the glass refreshes every frame. */
    var live by mutableStateOf(false)
}

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

@Composable
fun rememberBackdrop(live: Boolean = false): Backdrop = remember { Backdrop() }.also { it.live = live }

private val canBlur = Build.VERSION.SDK_INT >= 31

/**
 * A page made of two things: what its glass looks through ([behind]: the
 * photograph, the viewfinder, a list of pictures) and what floats over that
 * ([chrome]: the capsules, the trays).
 */
@Composable
fun GlassPage(
    modifier: Modifier = Modifier,
    live: Boolean = false,
    behind: @Composable BoxScope.() -> Unit,
    chrome: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberBackdrop(live)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop), content = behind)
        CompositionLocalProvider(LocalBackdrop provides backdrop) { chrome() }
    }
}

/** Marks the thing glass looks through. Glass over it must be drawn outside it (a sibling, never a child). */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = if (!canBlur) this else composed {
    val layer = rememberGraphicsLayer()
    DisposableEffect(backdrop, layer) {
        backdrop.layer = layer
        onDispose { if (backdrop.layer === layer) backdrop.layer = null }
    }
    onGloballyPositioned {
        backdrop.origin = it.positionInRoot()
        backdrop.extent = it.size
    }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            // written without being read here: only the glass over this source is drawn again, not the source
            Snapshot.withoutReadObservation { backdrop.tick++ }
        }
}

enum class GlassTone { Regular, Strong, Thin, Plate }

/** CSS gives a blur as the Gaussian's deviation; Android asks for a radius. */
private fun blurRadius(sigma: Float) = (sigma - .5f) / .57735f

private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

/**
 * A plate of glass with corners of [radius] (anything large makes a capsule).
 * [shadow]: large plates throw one; [clip] cuts what is inside to the plate.
 */
fun Modifier.glass(
    radius: Dp = 28.dp,
    tone: GlassTone = GlassTone.Regular,
    shadow: Boolean = true,
    clip: Boolean = true,
): Modifier = composed {
    val backdrop = LocalBackdrop.current
    // The blurred copy is drawn into a layer made for that one drawing and given back straight after. A layer
    // that is kept and recorded again goes on showing what it showed the first time: whatever came behind the
    // glass later (a picture that has just arrived, the camera's next frame) never reaches it.
    val layers = if (canBlur && backdrop != null) LocalGraphicsContext.current else null
    var position by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    if (layers != null && backdrop?.live == true) {
        LaunchedEffect(Unit) { while (true) withFrameNanos { frame++ } }
    }
    val light = Ink.light
    val fill = when (tone) {
        GlassTone.Regular -> Ink.Glass
        GlassTone.Strong -> Ink.GlassStrong
        GlassTone.Thin -> Ink.GlassThin
        GlassTone.Plate -> Ink.GlassPlate
    }
    val sigma = when (tone) {
        GlassTone.Strong -> 34f
        GlassTone.Thin -> 14f
        else -> if (light) 24f else 26f
    }
    val saturate = when (tone) {
        GlassTone.Strong -> 1.7f
        GlassTone.Thin -> 1.4f
        else -> Ink.GlassSaturation
    }
    // without the blur the tint has to do all the work
    val tint = if (layers != null) fill else fill.copy(alpha = min(1f, fill.alpha + .34f))
    val hi = Ink.GlassHi
    val lo = Ink.GlassLo
    val warm = Ink.GlassEdgeWarm
    val cool = Ink.GlassEdgeCool
    val drop = Ink.GlassShadow

    this
        .onGloballyPositioned { position = it.positionInRoot() }
        .graphicsLayer()      // its own layer: the glass redraws without the page behind it redrawing
        .drawWithCache {
            val w = size.width
            val h = size.height
            val r = min(radius.toPx(), min(w, h) / 2f)
            val line = 1.dp.toPx()
            val plate = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r))) }
            val rim = Path().apply {
                addRoundRect(RoundRect(line / 2, line / 2, w - line / 2, h - line / 2, CornerRadius(max(0f, r - line / 2))))
            }
            val rimBrush = cssLinear(
                150f, size,
                if (light) arrayOf(0f to Color.White, .34f to Color.White.copy(alpha = .45f), .62f to Color(23, 21, 15, 18), 1f to Color.White.copy(alpha = .85f))
                else arrayOf(0f to Color.White.copy(alpha = .55f), .32f to Color.White.copy(alpha = .09f), .58f to Color.White.copy(alpha = .02f), 1f to Color.White.copy(alpha = .30f)),
            )
            val vy = (r / h).coerceIn(.02f, .5f)
            val vertical = Brush.verticalGradient(0f to hi, vy to Color.Transparent, 1f - vy to Color.Transparent, 1f to lo)
            val vx = (r / w).coerceIn(.02f, .5f)
            val horizontal = Brush.horizontalGradient(0f to warm, vx to Color.Transparent, 1f - vx to Color.Transparent, 1f to cool)
            val pad = ceil(blurRadius(sigma.dp.toPx()) * 1.5f).toInt()
            val effect = if (layers != null && Build.VERSION.SDK_INT >= 31) {
                val px = blurRadius(sigma.dp.toPx())
                RenderEffect.createColorFilterEffect(
                    ColorMatrixColorFilter(saturation(saturate)),
                    RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP),
                ).asComposeRenderEffect()
            } else null
            val shade = if (shadow) glassShadow(w, h, r, light, drop) else null

            onDrawBehind {
                shade?.invoke(this)
                clipPath(plate) {
                    val source = backdrop?.layer
                    if (layers != null && effect != null && source != null && !source.isReleased && w >= 1f && h >= 1f) {
                        follow(backdrop.tick)      // drawn again whenever what is behind has been
                        follow(frame)
                        val dx = position.x - backdrop.origin.x
                        val dy = position.y - backdrop.origin.y
                        // The copy takes in a margin around the plate, for the blur to draw on -- but never more
                        // than there is: past the edge of what is behind there is nothing, and blurring nothing
                        // in would leave the glass clear along the edges of the screen. Up to its own edge the
                        // blur repeats the last pixels instead.
                        val left = max(0, floor(dx).toInt() - pad)
                        val top = max(0, floor(dy).toInt() - pad)
                        val right = min(backdrop.extent.width, ceil(dx + w).toInt() + pad)
                        val bottom = min(backdrop.extent.height, ceil(dy + h).toInt() + pad)
                        if (right > left && bottom > top) {
                            val blurred = layers.createGraphicsLayer()
                            blurred.record(size = IntSize(right - left, bottom - top)) {
                                translate(-left.toFloat(), -top.toFloat()) { drawLayer(source) }
                            }
                            blurred.renderEffect = effect
                            translate(left - dx, top - dy) { drawLayer(blurred) }
                            layers.releaseGraphicsLayer(blurred)
                        }
                    }
                    drawRect(tint)
                    // the sheen: light from the upper left, a little of the safelight from the lower right
                    glow(Offset(w * .12f, -h * .12f), w * 1.2f, h * .8f, Color.White.copy(alpha = if (light) .6f else .15f), .56f)
                    glow(Offset(w * .9f, h * 1.16f), w * .9f, h * .6f, Ink.Amber.copy(alpha = if (light) .10f else .08f), .62f)
                }
                // the rim: a line that catches light at the upper left and the lower right -- the thickness of the glass
                drawPath(rim, vertical, style = Stroke(line))
                drawPath(rim, horizontal, style = Stroke(line))
                drawPath(rim, rimBrush, style = Stroke(line))
            }
        }
        .then(if (clip) Modifier.clip(RoundedCornerShape(radius)) else Modifier)
}

/** A CSS `linear-gradient(<angle>deg, …)` across [size]. */
internal fun cssLinear(angle: Float, size: Size, stops: Array<Pair<Float, Color>>): Brush {
    val a = Math.toRadians(angle.toDouble())
    val dx = sin(a).toFloat()
    val dy = -cos(a).toFloat()
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(*stops, start = Offset(c.x - dx * half, c.y - dy * half), end = Offset(c.x + dx * half, c.y + dy * half))
}

/** A CSS elliptical `radial-gradient(rx ry at centre, colour, transparent stop)`. */
internal fun DrawScope.glow(centre: Offset, rx: Float, ry: Float, colour: Color, stop: Float) {
    if (rx <= 0f || ry <= 0f) return
    withTransform({
        translate(centre.x, centre.y)
        scale(1f, ry / rx, pivot = Offset.Zero)
    }) {
        drawCircle(
            Brush.radialGradient(0f to colour, stop to Color.Transparent, center = Offset.Zero, radius = rx),
            radius = rx, center = Offset.Zero,
        )
    }
}

/** Reading a state inside a draw block is what makes the block run again when it changes. */
@Suppress("UNUSED_PARAMETER")
private fun follow(value: Int) = Unit

/** `0 34px 70px -36px`: only plates larger than the spread throw a shadow at all. */
private fun Density.glassShadow(w: Float, h: Float, r: Float, light: Boolean, colour: Color): ((DrawScope) -> Unit)? {
    val spread = (if (light) 32 else 36).dp.toPx()
    val dy = (if (light) 28 else 34).dp.toPx()
    val sigma = (if (light) 25 else 35).dp.toPx()
    val rect = android.graphics.RectF(spread, spread + dy, w - spread, h - spread + dy)
    if (rect.width() <= 0f || rect.height() <= 0f) return null
    val corner = max(0f, r - spread)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colour.toArgb()
        maskFilter = BlurMaskFilter(blurRadius(sigma), BlurMaskFilter.Blur.NORMAL)
    }
    return { scope -> scope.drawIntoCanvas { it.nativeCanvas.drawRoundRect(rect, corner, corner, paint) } }
}

private val shadowPaints = HashMap<Int, android.graphics.Paint>()

/**
 * A CSS `box-shadow: 0 <dy> <blur> <spread> <colour>` under [rect]: a soft dark shape, drawn before the thing
 * itself. All lengths in pixels; [blur] is the CSS blur radius (twice the Gaussian's deviation).
 */
fun DrawScope.softShadow(rect: Rect, radius: Float, colour: Color, dy: Float, blur: Float, spread: Float) {
    val r = Rect(rect.left - spread, rect.top - spread + dy, rect.right + spread, rect.bottom + spread + dy)
    if (r.width <= 0f || r.height <= 0f) return
    val key = (blurRadius(blur / 2f) * 4f).toInt()
    val paint = shadowPaints.getOrPut(key) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(max(1f, key / 4f), BlurMaskFilter.Blur.NORMAL)
        }
    }
    paint.color = colour.toArgb()
    val corner = max(0f, radius + spread)
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(r.left, r.top, r.right, r.bottom, corner, corner, paint) }
}

/**
 * A picture's own light: the picture made very small and put out of focus, to be drawn large behind things. It is
 * made here, pixel by pixel, and not with a blur effect, because what it shows keeps changing (a camera's frames,
 * the proof of a look being worked on) and an effect layer does not follow that.
 */
fun ambientOf(picture: android.graphics.Bitmap, edge: Int = 28): androidx.compose.ui.graphics.ImageBitmap {
    val k = edge.toFloat() / max(picture.width, picture.height)
    val w = max(2, (picture.width * k).toInt())
    val h = max(2, (picture.height * k).toInt())
    val small = android.graphics.Bitmap.createScaledBitmap(picture, w, h, true)
    val a = IntArray(w * h)
    small.getPixels(a, 0, w, 0, 0, w, h)
    if (small !== picture) small.recycle()
    val b = IntArray(w * h)
    // two passes of a three-by-three average
    repeat(2) {
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0
            var g = 0
            var bl = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val p = a[yy * w + xx]
                r += p shr 16 and 0xff; g += p shr 8 and 0xff; bl += p and 0xff; n++
            }
            b[y * w + x] = (0xff shl 24) or (r / n shl 16) or (g / n shl 8) or (bl / n)
        }
        System.arraycopy(b, 0, a, 0, a.size)
    }
    return android.graphics.Bitmap.createBitmap(a, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

// ------------------------------------------------------------------ the droplet

/** Where the droplet is at this instant, and how far it is pressed flat while it travels. */
class DropletFrame(val rect: Rect, val squash: Float)

/**
 * One moving lens marks the current choice. Going from one choice to another
 * it first stretches to cover both, flattening a little, and then gathers
 * itself on the new one.
 */
@Stable
class Droplet(private val vertical: Boolean) {
    private var from: Rect? = null
    private var to by mutableStateOf<Rect?>(null)
    private var key: Any? = null
    private val t = Animatable(1f)

    internal suspend fun moveTo(nextKey: Any?, rect: Rect?, animate: Boolean) {
        if (rect == null) {
            to = null
            from = null
            key = nextKey
            return
        }
        val before = frame()?.rect
        val same = nextKey == key
        key = nextKey
        if (before == null || same || !animate) {
            from = null
            to = rect
            t.snapTo(1f)
            return
        }
        from = before
        to = rect
        t.snapTo(0f)
        t.animateTo(1f, tween(480, easing = Motion.Droplet))
        from = null
    }

    fun frame(): DropletFrame? {
        val b = to ?: return null
        val a = from ?: return DropletFrame(b, 1f)
        val p = t.value
        fun lerp(x: Float, y: Float, k: Float) = x + (y - x) * k
        val aPos = if (vertical) a.top else a.left
        val aSize = if (vertical) a.height else a.width
        val bPos = if (vertical) b.top else b.left
        val bSize = if (vertical) b.height else b.width
        val lo = min(aPos, bPos)
        val span = max(aPos + aSize, bPos + bSize) - lo
        val flat = if (vertical) .94f else .84f
        val pos: Float
        val size: Float
        val squash: Float
        if (p < .4f) {
            val k = p / .4f
            pos = lerp(aPos, lo, k); size = lerp(aSize, span, k); squash = lerp(1f, flat, k)
        } else {
            val k = (p - .4f) / .6f
            pos = lerp(lo, bPos, k); size = lerp(span, bSize, k); squash = lerp(flat, 1f, k)
        }
        return DropletFrame(
            if (vertical) Rect(lerp(a.left, b.left, p), pos, lerp(a.right, b.right, p), pos + size)
            else Rect(pos, lerp(a.top, b.top, p), pos + size, lerp(a.bottom, b.bottom, p)),
            squash,
        )
    }
}

/** A droplet that follows [rect]; it travels when [key] changes and simply stays put when only the layout does. */
@Composable
fun rememberDroplet(key: Any?, rect: Rect?, vertical: Boolean = false): Droplet {
    val droplet = remember(vertical) { Droplet(vertical) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(key, rect) { droplet.moveTo(key, rect, animate = !reduced) }
    return droplet
}

/** Draws the droplet where it is now. [radius] of its corners; a capsule when large. */
fun DrawScope.drawDroplet(droplet: Droplet, radius: Float = Float.MAX_VALUE, vertical: Boolean = false) {
    val f = droplet.frame() ?: return
    val rect = f.rect
    if (rect.width <= 0f || rect.height <= 0f) return
    // pressed flat across its direction of travel
    val body = if (vertical) {
        val w = rect.width * f.squash
        Rect(rect.center.x - w / 2, rect.top, rect.center.x + w / 2, rect.bottom)
    } else {
        val h = rect.height * f.squash
        Rect(rect.left, rect.center.y - h / 2, rect.right, rect.center.y + h / 2)
    }
    drawLens(body, min(radius, min(body.width, body.height) / 2f))
}

private val lensShadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private var lensShadowBlur = 0f

/** The droplet's body: a brighter piece of glass, lit from above, with a short shadow under it. */
fun DrawScope.drawLens(rect: Rect, radius: Float) {
    val line = 1.dp.toPx()
    val corner = CornerRadius(radius)
    // 0 8px 18px -8px
    val spread = 8.dp.toPx()
    if (rect.width > 2 * spread && rect.height > spread) {
        drawIntoCanvas {
            lensShadowPaint.color = Ink.DropShadow.toArgb()
            val blur = blurRadius(9.dp.toPx())
            if (blur != lensShadowBlur) {
                lensShadowBlur = blur
                lensShadowPaint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            it.nativeCanvas.drawRoundRect(
                rect.left + spread, rect.top + spread + 8.dp.toPx(), rect.right - spread, rect.bottom - spread / 2 + 8.dp.toPx(),
                max(0f, radius - spread), max(0f, radius - spread), lensShadowPaint,
            )
        }
    }
    val path = Path().apply { addRoundRect(RoundRect(rect, corner)) }
    drawPath(path, Ink.Drop)
    clipPath(path) {
        glow(
            Offset(rect.left + rect.width * .2f, rect.top - rect.height * .1f), rect.width * .9f, rect.height * 1.2f,
            Color.White.copy(alpha = if (Ink.light) .5f else .28f), .6f,
        )
    }
    val edge = Path().apply {
        addRoundRect(RoundRect(rect.left + line / 2, rect.top + line / 2, rect.right - line / 2, rect.bottom - line / 2, CornerRadius(max(0f, radius - line / 2))))
    }
    val k = (radius / rect.height).coerceIn(.05f, .5f)
    drawPath(
        edge,
        Brush.verticalGradient(0f to Ink.DropHi, k to Color.Transparent, 1f - k to Color.Transparent, 1f to Ink.DropLo, startY = rect.top, endY = rect.bottom),
        style = Stroke(line),
    )
}
