package com.spektra.latent.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.spektra.latent.R

private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255f + 0.5f).toInt())

/**
 * Design tokens. Everything a theme can change lives here, in two columns:
 * 暗 (the darkroom) and 亮 (paper). The values are the ones in the H5 design
 * (`ui-design/app-h5/css/tokens.css`); a token keeps the name it has there.
 */
object Ink {
    var light by mutableStateOf(false)
        internal set

    private fun pick(dark: Color, lightColour: Color) = if (light) lightColour else dark

    // ---- brand constants: identical in both themes
    val Amber = Color(0xFFFFA51F)
    val Amber2 = Color(0xFFFFBE63)
    val AmberDeep = Color(0xFFB06A17)
    /** Text on an amber fill. */
    val AmberInk = Color(0xFF1A1206)
    /** Recording, destructive. */
    val Rec = Color(0xFFE5484D)

    // ---- the page
    val Bg get() = pick(Color(0xFF08080A), Color(0xFFEDE7DA))
    val Bg2 get() = pick(Color(0xFF0D0D10), Color(0xFFF5F0E5))
    val Bg3 get() = pick(Color(0xFF141418), Color(0xFFE2DBCB))
    val Card get() = pick(Color(0xFF111114), Color(0xFFFBF8F1))
    val CardHi get() = pick(rgba(255, 255, 255, .07f), Color.White)

    val Text get() = pick(Color(0xFFF2EFE6), Color(0xFF17150F))
    val Dim get() = pick(Color(0xFFA7A196), Color(0xFF625C50))
    val Faint get() = pick(Color(0xFF77726A), Color(0xFF8F8879))
    val Line get() = pick(rgba(255, 255, 255, .10f), rgba(23, 21, 15, .12f))
    val Line2 get() = pick(rgba(255, 255, 255, .17f), rgba(23, 21, 15, .22f))

    /** Readouts, the one lit thing: amber light in the dark, the same light printed on paper. */
    val Accent get() = pick(Amber, AmberDeep)
    val AccentSoft get() = pick(rgba(255, 165, 31, .14f), rgba(176, 106, 23, .12f))

    // ---- glass
    val Glass get() = pick(rgba(20, 20, 24, .44f), rgba(250, 247, 240, .60f))
    val GlassStrong get() = pick(rgba(15, 15, 18, .66f), rgba(250, 247, 240, .80f))
    /** Large plates: the sidebar. */
    val GlassPlate get() = pick(rgba(16, 16, 20, .52f), rgba(250, 247, 240, .68f))
    val GlassThin get() = pick(rgba(20, 20, 24, .30f), rgba(250, 247, 240, .42f))
    val GlassHi get() = pick(rgba(255, 255, 255, .30f), rgba(255, 255, 255, .95f))
    val GlassLo get() = pick(rgba(0, 0, 0, .45f), rgba(23, 21, 15, .10f))
    val GlassEdgeWarm get() = pick(rgba(255, 120, 60, .06f), rgba(255, 140, 60, .10f))
    val GlassEdgeCool get() = pick(rgba(80, 160, 255, .07f), rgba(80, 150, 255, .10f))
    val GlassShadow get() = pick(rgba(0, 0, 0, .9f), rgba(45, 34, 16, .55f))
    val GlassText get() = pick(Color(0xFFF2EFE6), Color(0xFF17150F))
    val GlassDim get() = pick(Color(0xFFB4AEA3), Color(0xFF5C564A))
    val GlassSaturation get() = if (light) 1.5f else 1.65f

    // ---- the droplet: a brighter lens that marks "this one"
    val Drop get() = pick(rgba(255, 255, 255, .15f), rgba(255, 255, 255, .78f))
    val DropHi get() = pick(rgba(255, 255, 255, .42f), Color.White)
    val DropLo get() = pick(rgba(0, 0, 0, .35f), rgba(23, 21, 15, .10f))
    val DropShadow get() = pick(rgba(0, 0, 0, .7f), rgba(45, 34, 16, .45f))

    /** A recessed track inside glass. */
    val Well get() = pick(rgba(0, 0, 0, .30f), rgba(23, 21, 15, .08f))
    val WellLine get() = pick(rgba(255, 255, 255, .08f), rgba(23, 21, 15, .08f))
    val Scrim get() = pick(rgba(3, 3, 5, .58f), rgba(38, 32, 22, .34f))

    val Tick get() = pick(rgba(255, 255, 255, .20f), rgba(23, 21, 15, .22f))
    val TickMajor get() = pick(rgba(255, 255, 255, .42f), rgba(23, 21, 15, .5f))

    val Glyph get() = pick(Color(0xFFB6B0A4), Color(0xFF6B6559))
    val GlyphHot get() = pick(Amber, AmberDeep)

    val PhotoShadow get() = pick(rgba(0, 0, 0, .9f), rgba(45, 34, 16, .6f))
    val Paper get() = pick(Color(0xFFF4F1E8), Color(0xFFFDFBF6))

    /** What lies on a photograph is dark in both themes: the photograph is not the page. */
    val OnPhoto = Color(0xFFF2EFE6)
}

/** The curves everything moves on. */
object Motion {
    val Ease = CubicBezierEasing(.16f, 1f, .3f, 1f)
    val EaseIn = CubicBezierEasing(.4f, 0f, 1f, 1f)
    val Spring = CubicBezierEasing(.34f, 1.4f, .64f, 1f)
    val Droplet = CubicBezierEasing(.3f, .75f, .2f, 1f)
}

/**
 * Two voices. Chinese prose is the phone's own typeface, light. Numbers, frame
 * numbers and film names are set in a condensed grotesque -- the lettering
 * printed along the edge of a roll of film. Eleven is the smallest size.
 */
object Type {
    private val numFace = FontFamily(Font(R.font.heros_cn))

    val display get() = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Light, color = Ink.Text, letterSpacing = .03.em, lineHeight = 35.sp)
    val h1 get() = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, color = Ink.Text, letterSpacing = .04.em, lineHeight = 30.sp)
    val title get() = TextStyle(fontSize = 17.sp, color = Ink.Text, lineHeight = 24.sp)
    val body get() = TextStyle(fontSize = 14.sp, color = Ink.Text, lineHeight = 21.sp)
    val label get() = TextStyle(fontSize = 12.5.sp, color = Ink.Text, lineHeight = 18.sp)
    val micro get() = TextStyle(fontSize = 11.sp, color = Ink.Dim, lineHeight = 16.sp)

    /** Numbers and readouts. */
    val num get() = TextStyle(fontFamily = numFace, fontSize = 14.sp, color = Ink.Text, letterSpacing = .04.em)
    val numSmall get() = num.copy(fontSize = 12.sp, color = Ink.Dim)
    val numMicro get() = num.copy(fontSize = 11.sp, color = Ink.Dim, letterSpacing = .06.em)
    /** A film's name, as its edge print spells it. */
    val film get() = TextStyle(fontFamily = numFace, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.Text, letterSpacing = .08.em)
}

/** Text without Material: one line unless [maxLines] says otherwise. */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.let { s -> if (color != Color.Unspecified) s.copy(color = color) else s }
            .let { s -> if (align != null) s.copy(textAlign = align) else s },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
