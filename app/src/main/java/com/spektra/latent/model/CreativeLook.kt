package com.spektra.latent.model

import org.json.JSONArray
import org.json.JSONObject

/** Coordinates are fractions of the base frame; the gradient stays in the frame as the layer moves. */
data class ExposurePlacement(
    val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f,
    val gradient: Boolean = false, val angle: Float = 0f, val midpoint: Float = 0f, val feather: Float = .5f,
) {
    fun toJson(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
        .put("scale", scale.toDouble()).put("rotation", rotation.toDouble()).put("gradient", gradient)
        .put("angle", angle.toDouble()).put("midpoint", midpoint.toDouble()).put("feather", feather.toDouble())

    companion object {
        fun fromJson(o: JSONObject?): ExposurePlacement {
            fun read(key: String, default: Float, min: Float, max: Float): Float =
                o?.optDouble(key, default.toDouble())?.toFloat()?.takeIf(Float::isFinite)?.coerceIn(min, max) ?: default
            return ExposurePlacement(read("x", 0f, -1f, 1f), read("y", 0f, -1f, 1f),
                read("scale", 1f, .25f, 3f), read("rotation", 0f, -180f, 180f), o?.optBoolean("gradient") == true,
                read("angle", 0f, -180f, 180f), read("midpoint", 0f, -1f, 1f), read("feather", .5f, .02f, 1f))
        }
    }
}

/** An app-owned original, never a temporary document URI or an external path. */
data class ExposureLayer(val id: String, val name: String, val weight: Float = 100f,
                         val placement: ExposurePlacement = ExposurePlacement()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("weight", weight.toDouble())
        .put("placement", placement.toJson())

    companion object {
        private val identifier = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        fun fromJson(o: JSONObject): ExposureLayer? {
            val id = o.optString("id")
            if (!identifier.matches(id)) return null
            val value = o.optDouble("weight", 100.0).toFloat()
            return ExposureLayer(id, o.optString("name", "照片").take(240),
                if (value.isFinite()) value.coerceIn(0f, 100f) else 100f, ExposurePlacement.fromJson(o.optJSONObject("placement")))
        }
    }
}

/** Optional scene-linear creative treatments, applied before film/print/edge/date. */
data class CreativeLook(
    val softFocus: Float = 0f,
    val flare: Float = 0f,
    val glow: Float = 0f,
    val baseWeight: Float = 100f,
    val layers: List<ExposureLayer> = emptyList(),
) {
    val active: Boolean get() = softFocus > 0f || flare > 0f || glow > 0f || layers.any { it.weight > 0f }
    fun toJson(): JSONObject = JSONObject()
        .put("softFocus", softFocus.toDouble()).put("flare", flare.toDouble()).put("glow", glow.toDouble())
        .put("baseWeight", baseWeight.toDouble())
        .put("layers", JSONArray().also { a -> layers.take(MAX_LAYERS).forEach { a.put(it.toJson()) } })

    companion object {
        const val MAX_IMAGES = 5
        const val MAX_LAYERS = MAX_IMAGES - 1

        fun fromJson(o: JSONObject?): CreativeLook {
            if (o == null) return CreativeLook()
            fun amount(key: String, default: Float = 0f): Float {
                val value = o.optDouble(key, default.toDouble()).toFloat()
                return if (value.isFinite()) value.coerceIn(0f, 100f) else default
            }
            val array = o.optJSONArray("layers")
            val layers = buildList<ExposureLayer> {
                if (array != null) for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let(ExposureLayer::fromJson)?.let { layer ->
                        if (size < MAX_LAYERS && none { it.id == layer.id }) add(layer)
                    }
                }
            }
            return CreativeLook(amount("softFocus"), amount("flare"), amount("glow"), amount("baseWeight", 100f).coerceAtLeast(1f), layers)
        }
    }
}
