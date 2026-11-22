package com.spektra.latent.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class StripMode(val label: String) {
    Horizontal("横向整卷"), Vertical("纵向整卷"), Sleeve("胶片袋"),
}

internal data class StripFrame(val id: Long, val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun crop(width: Int, height: Int, vertical: Boolean): StripRect {
        val ratio = if (vertical) 2.0 / 3 else 3.0 / 2
        val w = (minOf(width.toDouble(), height * ratio) / zoom).roundToInt().coerceIn(1, width)
        val h = (minOf(height.toDouble(), width / ratio) / zoom).roundToInt().coerceIn(1, height)
        return StripRect(((width - w) * ((x + 1f) / 2f)).roundToInt(),
            ((height - h) * ((y + 1f) / 2f)).roundToInt(), w, h)
    }
}

internal data class FilmStrip(
    val name: String,
    val frames: List<StripFrame>,
    val mode: StripMode = StripMode.Horizontal,
    val perRow: Int = 5,
    val recipe: Look? = null,
) {
    init {
        require(frames.size in 2..MAX_FRAMES && frames.map { it.id }.distinct().size == frames.size)
        require(perRow in 1..MAX_FRAMES)
        require(frames.all { it.id > 0 && it.zoom in 1f..3f && it.x in -1f..1f && it.y in -1f..1f })
    }

    val rollCode: String get() = "R" + Math.floorMod(name.hashCode(), 10_000).toString().padStart(4, '0')

    fun toJson(): JSONObject = JSONObject().put("name", name).put("mode", mode.name).put("perRow", perRow)
        .put("recipe", recipe?.toJson()).put("frames", JSONArray(frames.map {
            JSONObject().put("id", it.id).put("zoom", it.zoom.toDouble()).put("x", it.x.toDouble()).put("y", it.y.toDouble())
        }))

    companion object {
        const val MAX_FRAMES = 36
        fun fromJson(json: JSONObject): FilmStrip {
            val frames = json.getJSONArray("frames")
            require(frames.length() in 2..MAX_FRAMES)
            return FilmStrip(json.getString("name"), List(frames.length()) { index ->
                val o = frames.getJSONObject(index)
                StripFrame(o.getLong("id"), o.optDouble("zoom", 1.0).toFloat(),
                    o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat())
            }, StripMode.valueOf(json.getString("mode")), json.getInt("perRow"),
                json.optJSONObject("recipe")?.let { Look.fromJson(it.toString()) })
        }
    }
}

internal data class StripRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    fun contains(px: Float, py: Float) = px >= x && px < right && py >= y && py < bottom
}

/** One 135 frame is 30 × 20 layout units. Whole-number output scale keeps cell geometry exact. */
internal class StripLayout(mode: StripMode, count: Int, perRow: Int, val scale: Int) {
    val vertical = mode == StripMode.Vertical
    val sleeve = mode == StripMode.Sleeve
    val columns = if (sleeve) perRow.coerceIn(1, count) else count
    val rows = if (sleeve) (count + columns - 1) / columns else 1
    private val lead = if (sleeve) 4 else 18
    private val tail = if (sleeve) 4 else 12
    private val along = lead + columns * PITCH - GAP + tail
    private val margin = if (sleeve) 6 else 0
    private val head = if (sleeve) 16 else 0
    private val track = 30
    private val rowPitch = track + 5
    val width = (if (vertical) track else along + 2 * margin) * scale
    val height = (if (vertical) along else head + (rows - 1) * rowPitch + track + margin) * scale
    val cells = List(count) { i ->
        val row = i / columns
        val u = lead + (i % columns) * PITCH
        val v = 5
        if (vertical) StripRect(v * scale, u * scale, 20 * scale, 30 * scale)
        else StripRect((margin + u) * scale, (head + row * rowPitch + v) * scale, 30 * scale, 20 * scale)
    }

    fun rail(row: Int) = if (vertical) StripRect(0, 0, width, height)
        else StripRect(margin * scale, (head + row * rowPitch) * scale, along * scale, track * scale)

    fun occupiedIn(row: Int) = (cells.size - row * columns).coerceIn(0, columns)
    fun occupiedLength(row: Int): Int = (if (sleeve) lead + occupiedIn(row) * PITCH - GAP + tail else along) * scale
    fun leadLength() = lead * scale
    fun tailLength() = tail * scale

    companion object {
        private const val GAP = 1
        const val PITCH = 31
        const val MAX_EDGE = 12_000
        const val MAX_PIXELS = 8_000_000
        fun output(strip: FilmStrip): StripLayout {
            val base = StripLayout(strip.mode, strip.frames.size, strip.perRow, 1)
            val scale = minOf(60, MAX_EDGE / maxOf(base.width, base.height),
                floor(sqrt(MAX_PIXELS.toDouble() / (base.width * base.height))).toInt()).coerceAtLeast(1)
            return StripLayout(strip.mode, strip.frames.size, strip.perRow, scale)
        }
    }
}
