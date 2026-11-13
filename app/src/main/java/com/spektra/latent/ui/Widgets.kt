package com.spektra.latent.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * A press that the finger can feel without a ripple: the thing sinks a little
 * while held ([sink] is how small it gets) and springs back.
 */
fun Modifier.pressable(enabled: Boolean = true, role: Role? = Role.Button, sink: Float = .965f, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(if (pressed) 90 else 350, easing = Motion.Ease), label = "press")
    this
        .graphicsLayer {
            val s = 1f - (1f - sink) * k
            scaleX = s
            scaleY = s
            alpha = if (enabled) 1f else .38f
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

enum class BtnKind {
    /** The screen's one main action: amber. */
    Key,
    /** A recessed button on glass or on a card. */
    Glass,
    /** An outline only. */
    Line,
    /** Something that cannot be taken back. */
    Danger,
}

enum class BtnSize(val height: Dp, val pad: Dp, val font: Float) {
    Regular(46.dp, 20.dp, 14f), Bar(40.dp, 17.dp, 13.5f), Small(36.dp, 14.dp, 13f)
}

private val keyGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private var keyGlowBlur = 0f

/** The app's button: a capsule. */
@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Glass,
    size: BtnSize = BtnSize.Regular,
    icon: Glyph? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    val ink = when (kind) {
        BtnKind.Key -> Ink.AmberInk
        BtnKind.Danger -> Ink.Rec
        else -> Ink.GlassText
    }
    val line2 = Ink.Line2
    val well = Ink.Well
    val hi = Ink.CardHi
    val face = when (kind) {
        BtnKind.Key -> Modifier
            .drawBehind {
                // 0 12px 26px -12px: the button's own light on what is under it. A button that cannot be pressed
                // gives none: it is drawn faded, in a layer of its own size, which would cut the light off square.
                val spread = 12.dp.toPx()
                if (enabled && this.size.width > 2 * spread) drawIntoCanvas {
                    val blur = (13.dp.toPx() - .5f) / .57735f
                    if (blur != keyGlowBlur) {
                        keyGlowBlur = blur
                        keyGlow.maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    keyGlow.color = Ink.Amber.copy(alpha = .62f).toArgb()
                    val r = this.size.height / 2
                    it.nativeCanvas.drawRoundRect(spread, spread + 12.dp.toPx(), this.size.width - spread, this.size.height + 2.dp.toPx(), r, r, keyGlow)
                }
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Ink.Amber2, Ink.Amber)))
            .drawBehind {
                val r = this.size.height / 2
                drawRoundRect(
                    Brush.verticalGradient(0f to Color.White.copy(alpha = .55f), .5f to Color.Transparent),
                    Offset(.5.dp.toPx(), .5.dp.toPx()), Size(this.size.width - 1.dp.toPx(), this.size.height - 1.dp.toPx()),
                    CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                )
            }
        BtnKind.Glass -> Modifier
            .clip(shape).background(well).border(1.dp, line2, shape)
            .drawBehind {
                val r = this.size.height / 2
                drawRoundRect(
                    Brush.verticalGradient(0f to hi, .5f to Color.Transparent),
                    Offset(1.5.dp.toPx(), 1.5.dp.toPx()), Size(this.size.width - 3.dp.toPx(), this.size.height - 3.dp.toPx()),
                    CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                )
            }
        BtnKind.Line -> Modifier.clip(shape).border(1.dp, line2, shape)
        BtnKind.Danger -> Modifier.clip(shape).border(1.dp, Ink.Rec.copy(alpha = .6f), shape)
    }
    Row(
        modifier
            .pressable(enabled, onClick = onClick)
            .then(face)
            .defaultMinSize(minHeight = size.height)
            .height(size.height)
            .padding(horizontal = size.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, tint = ink, size = 18.dp)
        Txt(
            text,
            Type.body.copy(fontSize = size.font.sp, letterSpacing = .04.em, fontWeight = if (kind == BtnKind.Key) FontWeight.Medium else FontWeight.Normal),
            color = ink,
        )
    }
}

/** A round touch target around an icon. [on]: the thing it switches is on. */
@Composable
fun IconButton(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    on: Boolean = false,
    size: Dp = 44.dp,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(if (pressed) 80 else 300, easing = Motion.Ease), label = "icon")
    val well = Ink.Well
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val s = 1f - .1f * k
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else .32f
            }
            .clip(CircleShape)
            .drawBehind { if (k > 0f) drawCircle(well.copy(alpha = well.alpha * k)) }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, tint = if (tint != Color.Unspecified) tint else if (on) Ink.Accent else Ink.GlassText)
    }
}

/** A free-standing round glass button: flip the camera, record. */
@Composable
fun Orb(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(48.dp)
            .pressable(enabled, sink = .92f, onClick = onClick)
            .glass(24.dp, GlassTone.Thin, shadow = false)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

enum class LampState { On, Off, Rec }

private val lampGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/** A small pilot light: something is going on. */
@Composable
fun Lamp(state: LampState = LampState.On, modifier: Modifier = Modifier) {
    val colour = when (state) {
        LampState.On -> Ink.Amber
        LampState.Off -> Ink.Faint
        LampState.Rec -> Ink.Rec
    }
    Box(
        modifier.size(6.dp).drawBehind {
            if (state != LampState.Off) drawIntoCanvas {
                lampGlow.color = colour.copy(alpha = .8f).toArgb()
                lampGlow.maskFilter = android.graphics.BlurMaskFilter(4.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                it.nativeCanvas.drawCircle(center.x, center.y, 4.dp.toPx(), lampGlow)
            }
            drawCircle(colour)
        },
    )
}

/** A hairline. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.Line))
}

/** A short upright line between two groups of buttons in a capsule. */
@Composable
fun BarSep() {
    Box(Modifier.padding(horizontal = 3.dp).size(1.dp, 20.dp).background(Ink.Line2))
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
