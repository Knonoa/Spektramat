package com.spektra.latent.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.spektra.latent.R

/**
 * The palette. Everything around a photograph is a neutral grey (equal red,
 * green and blue), because a tinted surround changes how the photograph's own
 * colours are judged. One accent: the amber of a darkroom safelight, kept for
 * "this is the current value / the current step / progress". Red is only for
 * recording and deleting. Paper white appears only as the border of a print.
 */
object Ink {
    val Curtain = Color(0xFF0C0C0C)   // behind pictures
    val Body = Color(0xFF161616)      // panels
    val Raised = Color(0xFF202020)    // things on panels
    val Line = Color(0xFF2B2B2B)      // hairlines, tick marks at rest
    val Faint = Color(0xFF5A5A5A)
    val Dim = Color(0xFF8A8A8A)       // secondary text
    val Text = Color(0xFFEDEDED)
    val Amber = Color(0xFFF2A93B)
    val Red = Color(0xFFE5484D)
    val Paper = Color(0xFFF1EEE6)
}

/**
 * Two voices. Chinese text is the phone's own typeface. Numbers, frame numbers
 * and film names are set in a condensed grotesque -- the lettering printed
 * along the edge of a roll of film.
 */
object Type {
    private val edgeFace = FontFamily(Font(R.font.heros_cn))

    val title = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = Ink.Text, letterSpacing = 0.5.sp)
    val heading = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink.Text)
    val body = TextStyle(fontSize = 15.sp, color = Ink.Text, lineHeight = 22.sp)
    val label = TextStyle(fontSize = 13.sp, color = Ink.Text)
    val caption = TextStyle(fontSize = 11.sp, color = Ink.Dim)

    val edge = TextStyle(fontFamily = edgeFace, fontSize = 15.sp, color = Ink.Text, letterSpacing = 0.6.sp)
    val edgeSmall = edge.copy(fontSize = 12.sp, color = Ink.Dim)
    val edgeLarge = edge.copy(fontSize = 22.sp)
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
