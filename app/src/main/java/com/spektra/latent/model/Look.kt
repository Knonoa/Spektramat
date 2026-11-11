package com.spektra.latent.model

import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PrintOrder
import org.json.JSONObject
import kotlin.math.pow

/**
 * Everything the darkroom can change about one photograph, in the order light
 * meets it: the scene (corrections before the film), the film, the print.
 *
 * It is plain data: saved as JSON with the frame, compared for undo, turned
 * into the engine's numbers by [grade] and [order]. Every number is in the
 * unit its control shows.
 */
data class Look(
    // ---- the scene, before the film
    val exposure: Float = 0f,       // EV
    val temperature: Float = 0f,    // -100..100, + warmer
    val tint: Float = 0f,           // -100..100, + magenta
    val contrast: Float = 0f,       // -100..100
    val highlights: Float = 0f,     // -100..100
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val lensShading: Boolean = true,
    // ---- the film
    val film: String = DEFAULT_FILM,
    val halation: Float = 100f,     // percent of the stock's own
    val grain: Float = 100f,
    val interLayer: Float = 100f,
    // ---- the print
    val paper: String = DEFAULT_PAPER,
    val printExposure: Float = 0f,  // EV
    val filterY: Float = 0f,        // CC units added to the neutral filter pack
    val filterM: Float = 0f,
    val glare: Float = 100f,
) {
    /** Slides are not printed: the film itself is the picture. */
    val scanFilm: Boolean get() = FilmEngine.catalog.film(film)?.positive == true

    /** The `Grade` block of latent.glsl. [meteredEv] is the picture's own metering, taken once at import. */
    fun grade(meteredEv: Float): FloatArray {
        val wb = FilmEngine.whiteBalance(temperature, tint)
        return floatArrayOf(
            wb[0], wb[1], wb[2],
            2f.pow(exposure + meteredEv),
            2f.pow(contrast / 100f * 0.6f),
            highlights / 100f, shadows / 100f, whites / 100f, blacks / 100f,
            vibrance / 100f,
            1f + saturation / 100f,
        )
    }

    val printGain: Float get() = 2f.pow(printExposure)

    /** What the engine needs for the finished print. */
    fun order(seed: Long, filmFormatMm: Float = 35f) = PrintOrder(
        film = film, paper = paper, scanFilm = scanFilm,
        printExposure = printGain, yShift = filterY, mShift = filterM,
        interLayer = interLayer > 0f, interLayerAmount = interLayer / 100f,
        halation = halation > 0f, halationAmount = halation / 100f,
        grain = grain > 0f, grainAmount = grain / 100f,
        glare = glare > 0f, glareAmount = glare / 100f,
        filmFormatMm = filmFormatMm, seed = seed,
    )

    /** The same look on another stock; the paper stays when it suits the new stock, else the stock's own. */
    fun withFilm(id: String): Look {
        val stock = FilmEngine.catalog.film(id) ?: return this
        val current = FilmEngine.catalog.paper(paper)
        val fits = current != null && current.blackAndWhite == stock.blackAndWhite && current.cine == stock.cine
        return copy(film = id, paper = if (stock.positive || fits || stock.paper.isEmpty()) paper else stock.paper)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("exposure", exposure.toDouble()).put("temperature", temperature.toDouble()).put("tint", tint.toDouble())
        .put("contrast", contrast.toDouble()).put("highlights", highlights.toDouble()).put("shadows", shadows.toDouble())
        .put("whites", whites.toDouble()).put("blacks", blacks.toDouble())
        .put("vibrance", vibrance.toDouble()).put("saturation", saturation.toDouble())
        .put("lensShading", lensShading)
        .put("film", film).put("halation", halation.toDouble()).put("grain", grain.toDouble())
        .put("interLayer", interLayer.toDouble())
        .put("paper", paper).put("printExposure", printExposure.toDouble())
        .put("filterY", filterY.toDouble()).put("filterM", filterM.toDouble()).put("glare", glare.toDouble())

    companion object {
        const val DEFAULT_FILM = "kodak_portra_400"
        const val DEFAULT_PAPER = "kodak_portra_endura"

        /** Unknown or out-of-range values fall back to the default, never to something unusable. */
        fun fromJson(text: String?): Look {
            val o = try {
                if (text.isNullOrBlank()) return Look() else JSONObject(text)
            } catch (e: Exception) {
                return Look()
            }
            val d = Look()
            fun f(key: String, default: Float, lo: Float, hi: Float): Float {
                val v = o.optDouble(key, default.toDouble()).toFloat()
                return if (v.isFinite()) v.coerceIn(lo, hi) else default
            }
            val film = o.optString("film", d.film).takeIf { FilmEngine.catalog.film(it) != null } ?: d.film
            val paper = o.optString("paper", d.paper).takeIf { FilmEngine.catalog.paper(it) != null }
                ?: FilmEngine.catalog.film(film)?.paper?.takeIf { it.isNotEmpty() } ?: d.paper
            return Look(
                exposure = f("exposure", 0f, -5f, 5f),
                temperature = f("temperature", 0f, -100f, 100f), tint = f("tint", 0f, -100f, 100f),
                contrast = f("contrast", 0f, -100f, 100f),
                highlights = f("highlights", 0f, -100f, 100f), shadows = f("shadows", 0f, -100f, 100f),
                whites = f("whites", 0f, -100f, 100f), blacks = f("blacks", 0f, -100f, 100f),
                vibrance = f("vibrance", 0f, -100f, 100f), saturation = f("saturation", 0f, -100f, 100f),
                lensShading = o.optBoolean("lensShading", true),
                film = film,
                halation = f("halation", 100f, 0f, 200f), grain = f("grain", 100f, 0f, 200f),
                interLayer = f("interLayer", 100f, 0f, 200f),
                paper = paper,
                printExposure = f("printExposure", 0f, -3f, 3f),
                filterY = f("filterY", 0f, -60f, 60f), filterM = f("filterM", 0f, -60f, 60f),
                glare = f("glare", 100f, 0f, 200f),
            )
        }
    }
}

/** One ruler in the darkroom: what it is called, its range, and how it reads and writes a [Look]. */
class Control(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val default: Float,
    /** Marks at this spacing are drawn long and numbered. */
    val major: Float,
    val format: (Float) -> String,
    val get: (Look) -> Float,
    val set: (Look, Float) -> Look,
)

object Controls {
    private fun signed(v: Float): String {
        val n = Math.round(v)
        return if (n > 0) "+$n" else "$n"
    }

    private fun ev(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        return (if (r > 0f) "+" else "") + String.format(java.util.Locale.US, "%.2f", r)
    }

    private fun percent(v: Float) = "${Math.round(v)}"

    private fun hundred(id: String, label: String, get: (Look) -> Float, set: (Look, Float) -> Look) =
        Control(id, label, -100f, 100f, 1f, 0f, 50f, ::signed, get, set)

    val scene = listOf(
        Control("exposure", "曝光", -4f, 4f, 0.05f, 0f, 1f, ::ev, { it.exposure }) { l, v -> l.copy(exposure = v) },
        hundred("temperature", "色温", { it.temperature }) { l, v -> l.copy(temperature = v) },
        hundred("tint", "色调", { it.tint }) { l, v -> l.copy(tint = v) },
        hundred("contrast", "对比度", { it.contrast }) { l, v -> l.copy(contrast = v) },
        hundred("highlights", "高光", { it.highlights }) { l, v -> l.copy(highlights = v) },
        hundred("shadows", "阴影", { it.shadows }) { l, v -> l.copy(shadows = v) },
        hundred("whites", "白色", { it.whites }) { l, v -> l.copy(whites = v) },
        hundred("blacks", "黑色", { it.blacks }) { l, v -> l.copy(blacks = v) },
        hundred("vibrance", "自然饱和度", { it.vibrance }) { l, v -> l.copy(vibrance = v) },
        hundred("saturation", "饱和度", { it.saturation }) { l, v -> l.copy(saturation = v) },
    )

    val film = listOf(
        Control("halation", "光晕", 0f, 200f, 1f, 100f, 50f, ::percent, { it.halation }) { l, v -> l.copy(halation = v) },
        Control("grain", "颗粒", 0f, 200f, 1f, 100f, 50f, ::percent, { it.grain }) { l, v -> l.copy(grain = v) },
        Control("interLayer", "层间效应", 0f, 200f, 1f, 100f, 50f, ::percent, { it.interLayer }) { l, v -> l.copy(interLayer = v) },
    )

    val print = listOf(
        Control("printExposure", "印相曝光", -2f, 2f, 0.05f, 0f, 1f, ::ev, { it.printExposure }) { l, v -> l.copy(printExposure = v) },
        Control("filterY", "黄滤色", -40f, 40f, 1f, 0f, 20f, ::signed, { it.filterY }) { l, v -> l.copy(filterY = v) },
        Control("filterM", "品红滤色", -40f, 40f, 1f, 0f, 20f, ::signed, { it.filterM }) { l, v -> l.copy(filterM = v) },
        Control("glare", "眩光", 0f, 200f, 1f, 100f, 50f, ::percent, { it.glare }) { l, v -> l.copy(glare = v) },
    )
}
