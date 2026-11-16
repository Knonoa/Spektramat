package com.spektra.latent.data

import android.content.Context
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.PhotoPreviewLut
import com.spektra.latent.engine.PrintOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Shared immutable cubes. Only preview uses them; a saved photograph never reads this cache. */
object PhotoLutCache {
    const val SAMPLE_SIZE = 33
    const val WORK_SIZE = 65
    private const val VERSION = "photo-preview-v1"
    private const val MEMORY_LIMIT = 24 * 1024 * 1024
    private const val DISK_LIMIT = 128L * 1024 * 1024
    private val gate = Mutex()
    private val interactiveRequests = java.util.concurrent.atomic.AtomicInteger()
    private val memory = LinkedHashMap<String, Cube>(16, .75f, true)
    private var memoryBytes = 0

    fun ready(order: PrintOrder, size: Int): Cube? = synchronized(memory) { memory[key(order, size)] }

    class Cube(val key: String, val size: Int, val rgb: ByteBuffer) {
        fun buffer(): ByteBuffer = rgb.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply { rewind() }
    }

    fun key(order: PrintOrder, size: Int): String {
        val normalized = PhotoPreviewLut.cacheIdentity(order)
        // Rebate is an identity wrapper, so PrintOrder.toString() is not a
        // stable key. Canonical pointwise fields are serialized explicitly.
        val text = "$VERSION|${FilmEngine.renderCacheKey}|$size|$normalized"
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    suspend fun get(context: Context, order: PrintOrder, size: Int = SAMPLE_SIZE, interactive: Boolean = true): Cube = withContext(Dispatchers.Default) {
        val key = key(order, size)
        synchronized(memory) { memory[key] }?.let { return@withContext it }
        if (interactive) interactiveRequests.incrementAndGet()
        try {
            if (!interactive) while (interactiveRequests.get() > 0) delay(16)
            gate.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(memory) { memory[key] }?.let { return@withLock it }
            val dir = File(context.applicationContext.cacheDir, "photo-luts-$VERSION").apply { mkdirs() }
            val file = File(dir, "$key.rgb")
            val bytes = size * size * size * 12
            val buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            var cached = false
            if (file.length() == bytes.toLong()) {
                cached = runCatching {
                    FileInputStream(file).channel.use { channel ->
                        while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
                    }
                    buffer.rewind()
                    val samples = buffer.asFloatBuffer()
                    while (samples.hasRemaining()) check(samples.get().isFinite())
                    file.setLastModified(System.currentTimeMillis())
                    true
                }.getOrDefault(false)
            }
            if (!cached) BundledPhotoLuts.read(context.applicationContext, order, size)?.let { bundled ->
                buffer.clear()
                buffer.put(bundled)
                buffer.rewind()
                cached = true
            }
            if (!cached) {
                buffer.clear()
                PhotoPreviewLut.bake(order, size).use { lut -> buffer.put(lut.pixels().duplicate().apply { rewind() }) }
                buffer.rewind()
                // A cancelled request may warm the disk, but is never delivered to an obsolete screen.
                runCatching {
                    val temporary = File(dir, "$key.tmp")
                    try {
                        FileOutputStream(temporary).channel.use { channel ->
                            val data = buffer.duplicate().apply { rewind() }
                            while (data.hasRemaining()) channel.write(data)
                        }
                        check(temporary.renameTo(file))
                    } finally { temporary.delete() }
                }
                var retained = 0L
                dir.listFiles { f -> f.extension == "rgb" }?.sortedByDescending { it.lastModified() }?.forEach { f ->
                    retained += f.length()
                    if (retained > DISK_LIMIT) f.delete()
                }
            }
            val cube = Cube(key, size, buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder()))
            synchronized(memory) {
                memory[key] = cube
                memoryBytes += bytes
                val iterator = memory.entries.iterator()
                while (memoryBytes > MEMORY_LIMIT && memory.size > 1 && iterator.hasNext()) {
                    memoryBytes -= iterator.next().value.rgb.capacity()
                    iterator.remove()
                }
            }
            currentCoroutineContext().ensureActive()
            cube
            }
        } finally {
            if (interactive) interactiveRequests.decrementAndGet()
        }
    }
}
