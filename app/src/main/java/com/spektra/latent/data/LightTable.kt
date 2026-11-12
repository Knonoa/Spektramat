package com.spektra.latent.data

import android.content.Context
import com.spektra.latent.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One photograph lying on the table. The same frame may lie there several times, each its own way. */
data class Placement(
    val id: Long,
    val frameId: Long,
    /** Centre, in table units. */
    val cx: Float,
    val cy: Float,
    /** Width in table units; the height follows the picture's shape. */
    val w: Float,
    /** Degrees, clockwise. */
    val rot: Float = 0f,
    val z: Int = 0,
    val locked: Boolean = false,
)

/**
 * A loupe standing on the table. It magnifies whatever lies under it now: it
 * keeps a place and a power, never a picture of what was once there.
 */
data class Loupe(val id: Long, val cx: Float, val cy: Float, val size: Float = 240f, val zoom: Float = 2.5f)

/** Everything on the light table. */
data class TableDoc(val placements: List<Placement> = emptyList(), val loupes: List<Loupe> = emptyList()) {
    val nextId: Long get() = (placements.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(loupes.maxOfOrNull { it.id } ?: 0L) + 1L

    fun toJson(): JSONObject = JSONObject()
        .put("placements", JSONArray().also { a ->
            for (p in placements) {
                a.put(
                    JSONObject().put("id", p.id).put("frame", p.frameId).put("cx", p.cx.toDouble()).put("cy", p.cy.toDouble())
                        .put("w", p.w.toDouble()).put("rot", p.rot.toDouble()).put("z", p.z).put("locked", p.locked),
                )
            }
        })
        .put("loupes", JSONArray().also { a ->
            for (l in loupes) {
                a.put(
                    JSONObject().put("id", l.id).put("cx", l.cx.toDouble()).put("cy", l.cy.toDouble())
                        .put("size", l.size.toDouble()).put("zoom", l.zoom.toDouble()),
                )
            }
        })

    companion object {
        fun fromJson(o: JSONObject): TableDoc {
            fun f(j: JSONObject, key: String, default: Float): Float {
                val v = j.optDouble(key, default.toDouble()).toFloat()
                return if (v.isFinite()) v else default
            }
            val ps = o.optJSONArray("placements")
            val ls = o.optJSONArray("loupes")
            return TableDoc(
                placements = List(ps?.length() ?: 0) { i ->
                    val p = ps!!.getJSONObject(i)
                    Placement(
                        p.getLong("id"), p.getLong("frame"), f(p, "cx", 0f), f(p, "cy", 0f),
                        f(p, "w", 600f).coerceIn(60f, 20000f), f(p, "rot", 0f), p.optInt("z"), p.optBoolean("locked"),
                    )
                },
                loupes = List(ls?.length() ?: 0) { i ->
                    val l = ls!!.getJSONObject(i)
                    Loupe(l.getLong("id"), f(l, "cx", 0f), f(l, "cy", 0f), f(l, "size", 240f).coerceIn(80f, 8000f), f(l, "zoom", 2.5f).coerceIn(1.2f, 12f))
                },
            )
        }
    }
}

/** The light table as it was left, in one small file of the app's own. */
class LightTableStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "light-table.json")

    suspend fun load(): TableDoc = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext TableDoc()
        try {
            TableDoc.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            // a table that cannot be read is kept beside a fresh one, not overwritten
            Journal.w(TAG, "the light table could not be read", e)
            file.copyTo(File(file.parentFile, "light-table-unreadable-${System.currentTimeMillis()}.json"), overwrite = true)
            TableDoc()
        }
    }

    suspend fun save(doc: TableDoc) = withContext(Dispatchers.IO) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(doc.toJson().toString())
            if (!tmp.renameTo(file)) Journal.w(TAG, "the light table was not saved")
        } catch (e: Exception) {
            Journal.w(TAG, "the light table was not saved", e)
        }
    }

    private companion object {
        const val TAG = "latent-table"
    }
}
