package com.spektra.latent.model

import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.data.Frame
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.Rebate
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The film outside the picture, and what a camera's back writes on it.
 *
 * Both are exposure on the negative: the engine draws them (overscan.cpp), so
 * they go through development and printing like the picture does. This file
 * only decides what the engine is told -- which film and gate, the stock's own
 * edge lettering, the date and the shooting data read from the photograph.
 */

/**
 * A film format: how long the frame is on the film (grain and halation are
 * sized by it) and which strip of film its edge is.
 */
enum class Format(val id: String, val label: String, val longMm: Float, val wire: String, val roll: Boolean) {
    HALF("half", "半格", 24f, "135_half", false),
    F135("135", "135", 35f, "135", false),
    XPAN("xpan", "XPan", 65f, "135_xpan", false),
    F645("645", "645", 56f, "120_645", true),
    F66("6x6", "6×6", 56f, "120_6x6", true),
    F67("6x7", "6×7", 70f, "120_6x7", true),
    F68("6x8", "6×8", 76f, "120_6x8", true),
    F69("6x9", "6×9", 84f, "120_6x9", true),
    F612("6x12", "6×12", 112f, "120_6x12", true),
    F617("6x17", "6×17", 168f, "120_6x17", true),
    // sheet film has no edge print of its own here: its edge is shown as the widest roll film
    F45("4x5", "4×5", 120f, "120_6x9", true);

    /** The panoramic cameras had no data back. */
    val hasDateBack: Boolean get() = this != XPAN && this != F612 && this != F617

    companion object {
        fun of(id: String?): Format = entries.firstOrNull { it.id == id } ?: F135
    }
}

/** A named choice the engine understands by [wire]. */
class Choice(val wire: String, val label: String)

object EdgeChoices {
    val formats = Format.entries.map { Choice(it.id, it.label) }
    val views = listOf(Choice("strip", "整条"), Choice("filed", "窄边"))
    /** [OPEN] is no gate at all: the picture is exposed across the whole film, under the perforations. */
    const val OPEN = "open"
    val gates = listOf(
        Choice("auto", "自动"), Choice(OPEN, "全开"), Choice("square", "方角"), Choice("rounded", "圆角"),
        Choice("eared", "耳形"), Choice("shouldered", "肩形"), Choice("kicked", "折角"),
    )
    val holes = listOf(Choice("white", "透光"), Choice("black", "黑底"))
    val faces = listOf(Choice("lcd", "液晶"), Choice("dots", "点阵"), Choice("data", "仅数据"))
    val orders = listOf(Choice("ymd", "年 月 日"), Choice("mdy", "月 日 年"))
    val placements = listOf(Choice("frame", "画面内"), Choice("rebate", "帧间"))
    val corners = listOf(Choice("br", "右下"), Choice("bl", "左下"), Choice("tr", "右上"), Choice("tl", "左上"))

    fun label(list: List<Choice>, wire: String) = (list.firstOrNull { it.wire == wire } ?: list[0]).label
    internal fun valid(list: List<Choice>, wire: String?) = list.firstOrNull { it.wire == wire }?.wire ?: list[0].wire
}

/** The film around the frame: perforations, edge lettering, base fog, light leaks. */
data class Edge(
    val on: Boolean = false,
    val view: String = "strip",
    val gate: String = "auto",
    val holes: String = "white",
    /** The camera body: its gate, its frame spacing, where it leaks. One body shoots a whole roll. */
    val body: Int = 1,
    /** The number printed beside the frame, 1..99; 0 lets the frame decide. */
    val frameNumber: Int = 0,
    val fog: Float = 100f,     // percent of the stock's own, 0..400
    val leaks: Float = 0f,     // 0..400
    /** Shutter speed and aperture printed on the film beside the frame, as a data back does. */
    val data: Boolean = false,
) {
    /** No gate: the picture covers the film from edge to edge, so nothing of it is cut and no film shows beside it. */
    val open: Boolean get() = gate == EdgeChoices.OPEN

    fun toJson(): JSONObject = JSONObject()
        .put("on", on).put("view", view).put("gate", gate).put("holes", holes).put("body", body)
        .put("frameNumber", frameNumber).put("fog", fog.toDouble()).put("leaks", leaks.toDouble()).put("data", data)

    companion object {
        fun fromJson(o: JSONObject?): Edge {
            if (o == null) return Edge()
            fun f(key: String, default: Float, lo: Float, hi: Float): Float {
                val v = o.optDouble(key, default.toDouble()).toFloat()
                return if (v.isFinite()) v.coerceIn(lo, hi) else default
            }
            return Edge(
                on = o.optBoolean("on", false),
                view = EdgeChoices.valid(EdgeChoices.views, o.optString("view")),
                gate = EdgeChoices.valid(EdgeChoices.gates, o.optString("gate")),
                holes = EdgeChoices.valid(EdgeChoices.holes, o.optString("holes")),
                body = o.optInt("body", 1).coerceIn(1, 9999),
                frameNumber = o.optInt("frameNumber", 0).coerceIn(0, 99),
                fog = f("fog", 100f, 0f, 400f),
                leaks = f("leaks", 0f, 0f, 400f),
                data = o.optBoolean("data", false),
            )
        }
    }
}

/** A date back: the date (and, if wanted, the shooting data) exposed onto the film from behind. */
data class DateBack(
    val on: Boolean = false,
    val face: String = "lcd",
    val order: String = "ymd",
    val placement: String = "frame",
    val corner: String = "br",
    val size: Float = 100f,          // percent, 40..300
    val brightness: Float = 3.5f,    // EV above mid grey, -2..8
    val withData: Boolean = false,
    /** A date typed in by hand, yyyy-MM-dd; empty to use the photograph's own. */
    val manual: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("on", on).put("face", face).put("order", order).put("placement", placement).put("corner", corner)
        .put("size", size.toDouble()).put("brightness", brightness.toDouble()).put("withData", withData)
        .put("manual", manual)

    companion object {
        fun fromJson(o: JSONObject?): DateBack {
            if (o == null) return DateBack()
            fun f(key: String, default: Float, lo: Float, hi: Float): Float {
                val v = o.optDouble(key, default.toDouble()).toFloat()
                return if (v.isFinite()) v.coerceIn(lo, hi) else default
            }
            return DateBack(
                on = o.optBoolean("on", false),
                face = EdgeChoices.valid(EdgeChoices.faces, o.optString("face")),
                order = EdgeChoices.valid(EdgeChoices.orders, o.optString("order")),
                placement = EdgeChoices.valid(EdgeChoices.placements, o.optString("placement")),
                corner = EdgeChoices.valid(EdgeChoices.corners, o.optString("corner")),
                size = f("size", 100f, 40f, 300f),
                brightness = f("brightness", 3.5f, -2f, 8f),
                withData = o.optBoolean("withData", false),
                manual = o.optString("manual", "").takeIf { parseDate(it) != null } ?: "",
            )
        }

        /** yyyy-MM-dd as year, month, day; null when it is not a date. */
        fun parseDate(text: String): IntArray? {
            val p = text.split('-')
            if (p.size != 3) return null
            val y = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            val d = p[2].toIntOrNull() ?: return null
            if (y !in 1900..2099 || m !in 1..12 || d !in 1..31) return null
            return intArrayOf(y, m, d)
        }
    }
}

/**
 * What the photograph knows about how it was taken: only what its file
 * carries. A field the file does not have is left out, never invented.
 */
class Shooting(
    val year: Int, val month: Int, val day: Int,
    val exposureTime: Double? = null,
    val fNumber: Double? = null,
    /** EXIF ExposureProgram: 1 manual, 2 program, 3 aperture priority, 4 shutter priority. */
    val program: Int? = null,
    /** The camera's own exposure compensation. */
    val bias: Double? = null,
    /** Focal length as on 135 film: what a 135 data back prints. */
    val focal35: Double? = null,
) {
    /** The date as a date back writes it: no leading zeros, a two-digit year behind an apostrophe. */
    fun dateText(order: String): String {
        val yy = "'" + String.format(Locale.US, "%02d", ((year % 100) + 100) % 100)
        return if (order == "mdy") "$month $day $yy" else "$yy $month $day"
    }

    /** The edge's line: shutter speed and aperture, `1/60 F1.8`; empty when the file has neither. */
    val edgeText: String
        get() = listOfNotNull(exposureTime?.let(::shutter), fNumber?.let { "F" + trimmed(it) }).joinToString(" ")

    /** The data line: shutter, aperture, mode, compensation, focal length. Fields drop from the right until it fits. */
    val dataText: String
        get() {
            val fields = ArrayList<String>()
            exposureTime?.let { fields += shutter(it) }
            fNumber?.let { fields += "F" + trimmed(it) }
            program?.let(::mode)?.let { fields += it }
            bias?.let(::compensation)?.let { fields += it }
            focal35?.let { fields += "${it.roundToInt()}mm" }
            while (fields.joinToString(" ").length > DATA_LINE_MAX) fields.removeAt(fields.lastIndex)
            return fields.joinToString(" ")
        }

    fun withDate(y: Int, m: Int, d: Int) = Shooting(y, m, d, exposureTime, fNumber, program, bias, focal35)

    companion object {
        /** The longest line the imprint between frames holds: `1/250 F5.6 A +0.3 24mm`. */
        const val DATA_LINE_MAX = 22

        fun shutter(t: Double): String = if (t >= 1) trimmed(t) + "s" else "1/${(1 / t).roundToInt()}"
        private fun mode(program: Int): String? = when (program) { 1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; else -> null }
        private fun compensation(ev: Double): String? {
            val r = Math.round(ev * 10) / 10.0
            return if (r == 0.0) null else String.format(Locale.US, "%+.1f", r)
        }

        /** One decimal, without a trailing `.0`: `1.8`, `2`, `11`. */
        private fun trimmed(v: Double): String {
            val s = String.format(Locale.US, "%.1f", v)
            return if (s.endsWith(".0")) s.dropLast(2) else s
        }

        private fun positive(v: Double): Double? = v.takeIf { it.isFinite() && it > 0 }

        /** Reads the frame's original; what EXIF does not say falls back to what the library noted at import. */
        fun of(frame: Frame): Shooting {
            val taken = Calendar.getInstance().apply { timeInMillis = frame.taken }
            val exif = try { ExifInterface(frame.file) } catch (e: Exception) { null }
            // some cameras write the APEX values rather than ExposureTime / FNumber
            val time = exif?.let { e ->
                positive(e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0))
                    ?: e.getAttributeDouble(ExifInterface.TAG_SHUTTER_SPEED_VALUE, Double.NaN)
                        .takeIf { it.isFinite() }?.let { positive(2.0.pow(-it)) }
            } ?: positive(frame.shutter)
            val aperture = exif?.let { e ->
                positive(e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0))
                    ?: e.getAttributeDouble(ExifInterface.TAG_APERTURE_VALUE, Double.NaN)
                        .takeIf { it.isFinite() }?.let { positive(2.0.pow(it / 2)) }
            } ?: positive(frame.aperture)
            return Shooting(
                year = taken.get(Calendar.YEAR), month = taken.get(Calendar.MONTH) + 1, day = taken.get(Calendar.DAY_OF_MONTH),
                exposureTime = time,
                fNumber = aperture,
                program = exif?.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1)?.takeIf { it > 0 },
                bias = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)?.takeIf { it.isFinite() },
                focal35 = exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1)?.toDouble()?.let(::positive),
            )
        }

        /** Today, and nothing else: for a camera that has not taken the picture yet. */
        fun today(): Shooting {
            val now = Calendar.getInstance()
            return Shooting(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        }
    }
}

/** The lettering a stock carries along its edge: what was read off real strips where there was one, else its name. */
object EdgeText {
    private val own = mapOf( // (135, 120)
        "kodak_gold_200" to ("KODAK GB 200-7" to "KODAK 200"),
        "kodak_portra_800" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
        "kodak_portra_800_push1" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
        "kodak_portra_800_push2" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
        "kodak_ultramax_400" to ("KODAK GC 400" to "KODAK GC 400"),
        "kodak_ektachrome_100" to ("KODAK E100" to "KODAK E100"),
        "fujifilm_c200" to ("FUJI 200" to "FUJI 200"),
        "fujifilm_xtra_400" to ("S-400" to "FUJI S-400"),
        "fujifilm_pro_400h" to ("PRO400H" to "FUJI PRO400H"),
        "fujifilm_provia_100f" to ("FUJI RDPIII" to "FUJI RDPIII"),
        "fujifilm_velvia_100" to ("RVP100" to "FUJI RVP100"),
        "kodak_vision3_50d" to ("EASTMAN 5203" to "EASTMAN 5203"),
        "kodak_vision3_250d" to ("EASTMAN 5207" to "EASTMAN 5207"),
        "kodak_vision3_200t" to ("EASTMAN 5213" to "EASTMAN 5213"),
        "kodak_vision3_500t" to ("EASTMAN 5219" to "EASTMAN 5219"),
        "cinestill_800t" to ("CINESTILL 800T" to "CINESTILL 800T"),
        "kodak_tri_x_400" to ("KODAK 400TX" to "KODAK 400TX"),
        "kodak_tmax_100" to ("KODAK 100TMX" to "KODAK 100TMX"),
        "fujifilm_neopan_acros_100_ii" to ("FUJI 100 ACROS II" to "FUJI 100 ACROS II"),
        "ilford_hp5_plus_400" to ("ILFORD HP5 PLUS" to "ILFORD HP5 PLUS"),
    )

    fun of(stock: String, format: Format): String {
        own[stock]?.let { return if (format.roll) it.second else it.first }
        var name = FilmEngine.catalog.film(stock)?.name ?: stock.replace('_', ' ')
        val open = name.indexOf('(')
        if (open >= 0) name = name.substring(0, open)
        return name.trim().uppercase(Locale.US)
    }
}

/** The shapes a picture can be cut to, written long side first. */
object Aspects {
    class Aspect(val id: String, val long: Float, val short: Float, val note: String)

    val all = listOf(
        Aspect("", 0f, 0f, "不裁切"),
        Aspect("3:2", 3f, 2f, "135 · 6×9"),
        Aspect("4:3", 4f, 3f, "645 · 半格"),
        Aspect("5:4", 5f, 4f, "6×7 · 4×5"),
        Aspect("1:1", 1f, 1f, "6×6"),
        Aspect("16:9", 16f, 9f, "宽银幕"),
        Aspect("65:24", 65f, 24f, "XPan"),
    )

    fun valid(id: String?): String = all.firstOrNull { it.id == id }?.id ?: ""
    fun label(id: String): String = if (id.isEmpty()) "原始" else id

    /**
     * The centred rectangle (x, y, w, h) of a [width] x [height] picture that has
     * the shape [id]; an upright picture gets the upright shape. The whole
     * picture when [id] is empty or already its shape.
     */
    fun rect(width: Int, height: Int, id: String): IntArray {
        val a = all.firstOrNull { it.id == id }
        if (a == null || a.long <= 0f || width <= 0 || height <= 0) return intArrayOf(0, 0, width, height)
        val want = if (width >= height) a.long / a.short else a.short / a.long   // width over height
        val have = width.toFloat() / height
        return if (have > want) {
            val w = (height * want).roundToInt().coerceIn(1, width)
            intArrayOf((width - w) / 2, 0, w, height)
        } else {
            val h = (width / want).roundToInt().coerceIn(1, height)
            intArrayOf(0, (height - h) / 2, width, h)
        }
    }
}

/** What [look] puts on the negative around and over a frame shot as [shooting] says. */
internal fun rebateOf(look: Look, shooting: Shooting?, frameSeed: Int): Rebate {
    val format = Format.of(look.format)
    val edge = look.edge
    val back = look.date
    val shot = shooting?.let { s ->
        DateBack.parseDate(back.manual)?.let { s.withDate(it[0], it[1], it[2]) } ?: s
    }
    val backOn = back.on && format.hasDateBack && shot != null
    val dateText = when {
        !backOn -> ""
        back.face == "data" -> shot!!.dataText
        else -> shot!!.dateText(back.order)
    }
    val backData = if (backOn && back.withData && back.face != "data") shot!!.dataText else ""
    // with the gate open there is no film beside or between the frames to print on
    val open = edge.on && edge.open
    // the date back's own data line wins; otherwise the edge's shutter-and-aperture line
    val edgeData = if (edge.on && edge.data && format.hasDateBack && !open) shot?.edgeText ?: "" else ""
    val dataText = backData.ifEmpty { edgeData }
    val printsDate = backOn && (dateText.isNotEmpty() || backData.isNotEmpty())
    return Rebate(
        edge = edge.on,
        format = format.wire,
        mode = edge.view, gate = edge.gate, holes = edge.holes,
        edgeText = EdgeText.of(look.film, format),
        cameraSeed = edge.body.coerceAtLeast(1),
        frameSeed = frameSeed.coerceAtLeast(1),
        frameNumber = edge.frameNumber,
        fog = edge.fog / 100f, leaks = edge.leaks / 100f,
        fNumber = (shot?.fNumber ?: 0.0).toFloat(),
        date = printsDate || edgeData.isNotEmpty(),
        // with the back off, "data" would read the (empty) date line; any other face reads the data line
        dateStyle = if (printsDate) back.face else "lcd",
        dateText = dateText, dataText = dataText,
        datePlacement = if (open) "frame" else back.placement, dateCorner = back.corner,
        dateSize = back.size / 100f, dateEv = back.brightness,
        // the edge's line belongs on the film beside the frame, never in the picture
        dataInFrame = printsDate,
    )
}
