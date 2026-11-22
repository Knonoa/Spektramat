package com.spektra.latent.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.LinearImage
import com.spektra.latent.model.CreativeLook
import com.spektra.latent.model.ExposureLayer
import com.spektra.latent.model.Look
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** App-owned, unmodified exposure originals. Display thumbnails never feed processing. */
class CreativeSource(context: Context) {
    private val app = context.applicationContext
    private val originals = File(app.filesDir, "creative/originals")
    private val thumbs = File(app.filesDir, "creative/thumbs")

    data class Report(val layers: List<ExposureLayer>, val failures: List<String>, val overflow: Int)

    suspend fun import(uris: List<Uri>, capacity: Int): Report = withContext(Dispatchers.IO) {
        val limit = capacity.coerceIn(0, CreativeLook.MAX_LAYERS)
        val accepted = mutableListOf<ExposureLayer>()
        val failures = mutableListOf<String>()
        val attempted = uris.take(limit)
        for (uri in attempted) {
            currentCoroutineContext().ensureActive()
            val name = displayName(uri).take(240)
            val id = UUID.randomUUID().toString()
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }?.let { ".$it" } ?: ""
            val original = File(originals, id + extension)
            val thumb = File(thumbs, "$id.jpg")
            var retained = false
            try {
                if ((!originals.isDirectory && !originals.mkdirs()) || (!thumbs.isDirectory && !thumbs.mkdirs()))
                    throw IOException("无法创建叠加照片目录")
                (app.contentResolver.openInputStream(uri) ?: throw IOException("打不开这个文件")).use { input ->
                    original.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                // Validate the real decoder, not merely the file extension or an embedded display Bitmap.
                Decoder.decode(original, 160).use { source ->
                    if (source.previewOnly) throw IOException("无法完整解码此 RAW，不能作为叠加原片")
                    val bitmap = FilmEngine.display(source)
                    try { thumb.outputStream().use {
                        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it))
                            throw IOException("无法生成叠加缩略图")
                    } } finally { bitmap.recycle() }
                }
                currentCoroutineContext().ensureActive()
                accepted += ExposureLayer(id, name)
                retained = true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { failures += "$name：${failure.message ?: "无法读取"}"
            } finally {
                if (!retained) { original.delete(); thumb.delete() }
            }
        }
        Report(accepted, failures, uris.size - attempted.size)
    }

    fun file(layer: ExposureLayer): File {
        require(ID.matches(layer.id)) { "无效的叠加照片标识" }
        return originals.listFiles()?.firstOrNull {
            it.isFile && (it.name == layer.id || it.name.startsWith("${layer.id}."))
        } ?: throw IOException("叠加原片已不存在：${layer.name}")
    }

    fun thumbnail(layer: ExposureLayer): File? {
        if (!ID.matches(layer.id)) return null
        return File(thumbs, "${layer.id}.jpg").takeIf { it.isFile }
    }

    /** Caller keeps [base]; the returned float image belongs to the caller. Zero means native originals. */
    suspend fun apply(base: LinearImage, look: Look, maxEdge: Int): LinearImage {
        var pending: LinearImage? = null
        try {
            return withContext(Dispatchers.IO) {
                require(maxEdge in 0..1280) { "无效的创意代理尺寸" }
                val creative = look.creative
                require(creative.layers.size <= CreativeLook.MAX_LAYERS) { "包含底图最多合成 5 张照片" }
                require(creative.baseWeight.isFinite() && creative.baseWeight in 1f..100f)
                require(creative.layers.all { it.weight.isFinite() && it.weight in 0f..100f })
                if (maxEdge == 0 && base.previewOnly) throw IOException("无法完整解码此 RAW，不能使用内嵌预览图导出")
                // Resolve even muted layers: missing originals must never silently alter a saved composition.
                val files = creative.layers.map { file(it) }
                currentCoroutineContext().ensureActive()
                val result = if (maxEdge in 1..128) {
                    // All film candidates share the same tiny creative source. Film/scene controls aren't in this key.
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(base.pixels().duplicate().apply { rewind() })
                    val key = "${base.width}:${base.height}:${base.previewOnly}:$maxEdge:${look.lensShading}:$creative:" +
                        files.joinToString { "${it.path}:${it.length()}:${it.lastModified()}" } +
                        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    smallGate.withLock {
                        if (smallKey == key) smallImage!!.scaled(maxEdge)
                        else {
                            val result = compose(base, look, maxEdge, files)
                            try {
                                currentCoroutineContext().ensureActive()
                                val copy = result.scaled(maxEdge)
                                smallImage?.close()
                                smallImage = result
                                smallKey = key
                                copy
                            } catch (failure: Throwable) { result.close(); throw failure }
                        }
                    }
                } else compose(base, look, maxEdge, files)
                pending = result
                result
            }
        } catch (failure: Throwable) { pending?.close(); throw failure }
    }

    private suspend fun compose(base: LinearImage, look: Look, edge: Int, files: List<File>): LinearImage {
        val creative = look.creative
        var composed: LinearImage? = null
        try {
            if (creative.layers.any { it.weight > 0f }) {
                FilmEngine.exposureComposite(base, creative.baseWeight).use { blend ->
                    for ((index, layer) in creative.layers.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        if (layer.weight == 0f) continue
                        val source = if (edge == 0) Decoder.decode(files[index], 0, look.lensShading)
                        else PhotoProxyStore.get(app).acquire(proxyFrame(layer, files[index]), "", look.lensShading, edge)
                        source.use {
                            if (edge == 0 && it.previewOnly) throw IOException("无法完整解码叠加 RAW：${layer.name}")
                            val p = layer.placement
                            blend.add(it, layer.weight, p.x, p.y, p.scale, p.rotation, p.gradient, p.angle, p.midpoint, p.feather)
                        }
                    }
                    composed = blend.detach()
                }
            } else composed = base.scaled(0)
            currentCoroutineContext().ensureActive()
            if (creative.softFocus > 0f || creative.flare > 0f || creative.glow > 0f) {
                val treated = FilmEngine.creativeOptics(composed!!, creative.softFocus, creative.flare, creative.glow)
                composed!!.close()
                composed = treated
            }
            currentCoroutineContext().ensureActive()
            return composed!!.also { composed = null }
        } finally { composed?.close() }
    }

    private fun proxyFrame(layer: ExposureLayer, original: File) = Frame(
        layer.id.hashCode().toLong(), -1L, 1, original, layer.name, false, 1, 1,
        0L, "", "", 0, 0.0, 0.0, 0.0, 0f, Look(), thumbnail(layer) ?: original, 1,
    )

    private fun displayName(uri: Uri): String = runCatching {
        if (uri.scheme == "file") File(uri.path!!).name else
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
    }.getOrNull()?.ifBlank { null } ?: uri.lastPathSegment ?: "叠加照片"

    companion object {
        private val ID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        private val smallGate = Mutex()
        private var smallKey: String? = null
        private var smallImage: LinearImage? = null
    }
}
