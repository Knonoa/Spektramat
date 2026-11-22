package com.spektra.latent.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Original vector illustrations rather than downloaded packaging photographs.
 * A labelled cartridge is made for every catalogue stock, including push
 * variants. It stays sharp at any density and needs neither a bitmap nor a
 * photo-processing job while the stock browser is scrolled.
 */
@Composable
internal fun FilmCartridge(stock: Stock, modifier: Modifier = Modifier) {
    val label = remember(stock.id, stock.name) { cartridgeLabel(stock) }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    }
    Canvas(modifier.semantics { contentDescription = "${stock.name} 胶卷示意图" }) {
        // The artwork uses a fixed coordinate system, not density-dependent text.
        scale(size.width / 200f, size.height / 200f, pivot = Offset.Zero) {
            val body = Color(0xFF222327)
            val silver = Color(0xFF75767A)
            drawOval(Color.Black.copy(alpha = .18f), Offset(36f, 173f), Size(133f, 13f))

            // A short amber film leader comes out from behind the canister.
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color(0xFF4D2B15), Color(0xFFAD7442), Color(0xFF5E391F)), 118f, 181f),
                Offset(118f, 71f), Size(63f, 93f), CornerRadius(5f),
            )
            drawRect(Color(0xFF281911).copy(alpha = .42f), Offset(124f, 90f), Size(56f, 55f))
            for (y in listOf(77f, 153f)) for (x in listOf(133f, 148f, 163f)) {
                drawRoundRect(Color(0xFF181719), Offset(x, y), Size(8f, 6f), CornerRadius(1.5f))
            }

            // Top and bottom metal lips frame the stock's own label.
            drawRoundRect(body, Offset(45f, 38f), Size(103f, 131f), CornerRadius(12f))
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color(0xFF101114), silver, Color(0xFF2D2E32), Color(0xFF111215)), 45f, 148f),
                Offset(45f, 36f), Size(103f, 136f), CornerRadius(11f),
            )
            drawRoundRect(label.base, Offset(48f, 55f), Size(97f, 102f), CornerRadius(3f))
            drawRect(label.band, Offset(48f, 118f), Size(97f, 39f))
            drawRect(Color.White.copy(alpha = .09f), Offset(53f, 55f), Size(4f, 102f))
            drawRect(Color.Black.copy(alpha = .13f), Offset(135f, 55f), Size(10f, 102f))
            drawRoundRect(Color(0xFF151619), Offset(41f, 34f), Size(111f, 19f), CornerRadius(7f))
            drawRoundRect(Color(0xFF151619), Offset(41f, 160f), Size(111f, 16f), CornerRadius(6f))
            drawRoundRect(Color.White.copy(alpha = .12f), Offset(44f, 36f), Size(105f, 14f), CornerRadius(5f), style = Stroke(1f))
            drawRoundRect(Color(0xFF292A2D), Offset(77f, 25f), Size(38f, 15f), CornerRadius(4f))

            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                fun word(value: String, x: Float, baseline: Float, maxWidth: Float, point: Float, color: Color) {
                    textPaint.shader = null
                    textPaint.color = android.graphics.Color.argb(
                        (color.alpha * 255).toInt(), (color.red * 255).toInt(),
                        (color.green * 255).toInt(), (color.blue * 255).toInt(),
                    )
                    textPaint.textSize = point
                    val measured = textPaint.measureText(value)
                    if (measured > maxWidth) textPaint.textSize *= maxWidth / measured
                    native.drawText(value, x, baseline, textPaint)
                }
                word(label.brand, 58f, 72f, 77f, 11f, label.ink)
                word(label.model, 57f, 95f, 78f, 20f, label.ink)
                if (label.detail.isNotEmpty()) word(label.detail, 58f, 111f, 76f, 10f, label.ink)
                word(label.speed, 57f, 146f, 76f, 28f, label.bandInk)
                word("FILM", 59f, 48f, 73f, 8f, Color(0xFFB3B3B6))
            }
        }
    }
}

private data class CartridgeLabel(
    val brand: String,
    val model: String,
    val speed: String,
    val detail: String,
    val base: Color,
    val band: Color,
    val ink: Color = Color(0xFF17181B),
    val bandInk: Color = Color.White,
)

/** Use the engine's stock identifiers so no item can show another film's label. */
private fun cartridgeLabel(stock: Stock): CartridgeLabel {
    val id = stock.id
    val brand = when {
        id.startsWith("kodak_") -> "KODAK"
        id.startsWith("fujifilm_") -> "FUJIFILM"
        id.startsWith("ilford_") -> "ILFORD"
        id.startsWith("cinestill_") -> "CINESTILL"
        else -> stock.name.substringBefore(' ').uppercase()
    }
    val speed = (Regex("(?:^|_)(\\d+[a-z]?)(?:_|$)").find(id)?.groupValues?.get(1)
        ?: Regex("\\d+[a-z]?").find(id)?.value)?.uppercase() ?: "FILM"
    val detail = when {
        id.endsWith("push1") -> "PUSH +1"
        id.endsWith("push2") -> "PUSH +2"
        stock.kind == "反转片" -> "COLOR REVERSAL"
        stock.kind == "黑白负片" -> "BLACK & WHITE"
        stock.kind == "电影负片" -> "MOTION PICTURE"
        else -> "COLOR NEGATIVE"
    }
    val model = when {
        "portra" in id -> "PORTRA"
        "ektar" in id -> "EKTAR"
        "ultramax" in id -> "ULTRAMAX"
        "gold" in id -> "GOLD"
        "ektachrome" in id -> "EKTACHROME"
        "kodachrome" in id -> "KODACHROME"
        "vision3" in id -> "VISION3"
        "verita" in id -> "VERITA"
        "tmax" in id -> "T-MAX"
        "tri_x" in id -> "TRI-X"
        "velvia" in id -> "VELVIA"
        "provia" in id -> "PROVIA"
        "acros" in id -> "ACROS II"
        "pro_400h" in id -> "PRO 400H"
        "xtra" in id -> "SUPERIA X-TRA"
        "c200" in id -> "C200"
        "hp5" in id -> "HP5 PLUS"
        "cinestill" in id -> "CINESTILL"
        else -> shortName(stock.name).substringBefore(speed).trim().ifBlank { shortName(stock.name) }
    }
    val cream = Color(0xFFF1E3C6)
    val yellow = Color(0xFFF0BD28)
    val black = Color(0xFF202226)
    val (base, band) = when {
        "portra" in id -> cream to Color(0xFF947749)
        "ektar" in id -> Color(0xFFC53B37) to Color(0xFF7D242B)
        "gold" in id -> yellow to Color(0xFFB33D2C)
        "ultramax" in id -> yellow to Color(0xFF2D5890)
        "ektachrome" in id -> yellow to Color(0xFF326992)
        "kodachrome" in id -> Color(0xFFD84635) to black
        "vision3" in id -> yellow to black
        "verita" in id -> cream to Color(0xFF4C737D)
        "tmax" in id -> Color(0xFFCACDD0) to Color(0xFF6E4369)
        "tri_x" in id -> Color(0xFFC8CACB) to black
        "velvia" in id -> Color(0xFF4A9366) to Color(0xFF993A61)
        "provia" in id -> Color(0xFF4A9366) to Color(0xFF2D5890)
        "acros" in id -> Color(0xFFD9DCDA) to Color(0xFF327659)
        "pro_400h" in id -> Color(0xFFDDE8D6) to Color(0xFF47996A)
        "fujifilm" in id -> Color(0xFF67A66A) to Color(0xFF2D6750)
        "hp5" in id -> Color(0xFFE3E3DA) to Color(0xFF548B4D)
        "cinestill" in id -> Color(0xFFE8E0D2) to Color(0xFF933A37)
        else -> cream to black
    }
    return CartridgeLabel(brand, model, speed, detail, base, band)
}
