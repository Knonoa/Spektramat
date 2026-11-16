package com.spektra.latent.data

import android.content.Context
import com.spektra.latent.Journal
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoModel
import com.spektra.latent.engine.PhotoPreviewLut
import com.spektra.latent.engine.PrintOrder
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Optional cold-start acceleration. Any mismatch falls back to the current engine baker. */
internal object BundledPhotoLuts {
    private const val DIRECTORY = "photo-previews"
    private data class Entry(val film: String, val paper: String, val scan: Boolean,
                             val expanded: Boolean, val file: String, val sha256: String)
    private data class Index(val engine: String, val size: Int, val entries: List<Entry>)
    @Volatile private var index: Index? = null
    @Volatile private var loaded = false

    fun read(context: Context, order: PrintOrder, size: Int): ByteBuffer? {
        val catalogue = catalogue(context) ?: return null
        if (catalogue.engine != FilmEngine.renderCacheKey || catalogue.size != size) return null
        val normalized = PhotoPreviewLut.canonicalOrder(order)
        val expanded = normalized.model.scannerFullWhiteExtension
        // Compare every canonical pointwise field. A bundled material must not
        // hide changes to DIR, filters, spectral input, scan calibration, etc.
        val defaults = PhotoPreviewLut.canonicalOrder(PrintOrder(
            normalized.film, normalized.paper, normalized.scanFilm,
            model = if (expanded) PhotoModel(scannerWhiteCorrection = true,
                scannerWhiteLevel = 1f, scannerFullWhiteExtension = true) else PhotoModel(),
        ))
        if (normalized != defaults) return null
        val entry = catalogue.entries.firstOrNull {
            it.film == normalized.film && it.paper == normalized.paper &&
                it.scan == normalized.scanFilm && it.expanded == expanded
        } ?: return null
        return try {
            val bytes = context.assets.open("$DIRECTORY/${entry.file}").use { it.readBytes() }
            check(bytes.size == size * size * size * 12)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest == entry.sha256)
            val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for (i in 0 until source.remaining()) check(source[i].isFinite())
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                asFloatBuffer().put(source)
                rewind()
            }
        } catch (failure: Exception) {
            Journal.w("latent-photo-lut", "Bundled cube unavailable; using current engine", failure)
            null
        }
    }

    private fun catalogue(context: Context): Index? {
        if (loaded) return index
        return synchronized(this) {
            if (!loaded) {
                index = runCatching {
                    val json = JSONObject(context.assets.open("$DIRECTORY/manifest.json")
                        .bufferedReader().use { it.readText().removePrefix("\uFEFF") })
                    check(json.getInt("schema") == 1 && json.getString("type") == "float32-le" &&
                        json.getString("shaper") == "photo-signed-log-v1")
                    val entries = json.getJSONArray("entries")
                    Index(json.getString("engineCacheKey"), json.getInt("size"), List(entries.length()) { i ->
                        val entry = entries.getJSONObject(i)
                        val file = entry.getString("file")
                        check(file.matches(Regex("[a-z0-9_\\-]+\\.rgb")))
                        Entry(entry.getString("film"), entry.getString("paper"), entry.getBoolean("scan"),
                            entry.getString("white") == "expanded", file, entry.getString("sha256"))
                    })
                }.getOrNull()
                loaded = true
            }
            index
        }
    }
}
