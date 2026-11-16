package com.spektra.latent.data

import android.app.ActivityManager
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.Journal
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.LinearImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Unprocessed scene-linear float32 sources shared by the workbench and stock swatches.
 * The canonical proxy is decoded once; every caller owns a separate native image.
 * Display JPEGs and film recipes never enter this cache or the export path.
 */
class PhotoProxyStore internal constructor(context: Context, private val directory: File = File(context.cacheDir, "photo-sources")) {
    private val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    private val sourceEdge = if (lowMemory) 960 else 1280
    private val memoryBudget = (if (lowMemory) 24L else 48L) * 1024 * 1024
    private val gate = Mutex()
    private val memory = LinkedHashMap<String, LinearImage>(4, .75f, true)
    private var memoryBytes = 0L
    internal var decodeCount = 0
        private set
    internal var diskHitCount = 0
        private set

    /** The returned native image belongs to the caller and must be closed. */
    suspend fun acquire(frame: Frame, aspect: String, lensShading: Boolean, edge: Int): LinearImage {
        var pending: LinearImage? = null
        try {
            return withContext(Dispatchers.IO) {
                require(edge in 1..1280) { "A workbench proxy must remain at most 1280 pixels" }
                gate.withLock {
                    currentCoroutineContext().ensureActive()
                    val identity = identity(frame.file, aspect, lensShading, sourceEdge)
                    memory[identity]?.let { return@withLock it.scaled(edge) }
                    val stored = read(identity)
                    val source = stored ?: run {
                        currentCoroutineContext().ensureActive()
                        decodeCount++
                        // Decoder preserves negative/HDR values; no Bitmap or colour cube is involved.
                        Decoder.decode(frame.file, sourceEdge, lensShading, aspect)
                    }
                    var cached = false
                    try {
                        currentCoroutineContext().ensureActive()
                        if (stored == null) write(identity, source)
                        val result = source.scaled(edge)
                        try {
                            currentCoroutineContext().ensureActive()
                            remember(identity, source)
                            cached = true
                            result
                        } catch (failure: Throwable) { result.close(); throw failure }
                    } finally { if (!cached) source.close() }
                }.also { pending = it }
            }
        } catch (failure: Throwable) {
            // withContext may cancel after a native result is made but before delivery.
            pending?.close()
            throw failure
        }
    }

    /** Memory pressure and tests can discard working data without touching originals. */
    suspend fun clearMemory() = gate.withLock {
        memory.values.forEach { it.close() }
        memory.clear()
        memoryBytes = 0L
    }

    /** Nonblocking application memory callback; the next access can reload its float disk copy. */
    internal fun trimMemory() {
        if (!gate.tryLock()) return
        try {
            memory.values.forEach { it.close() }
            memory.clear()
            memoryBytes = 0L
        } finally { gate.unlock() }
    }

    internal suspend fun cacheFile(frame: Frame, aspect: String, lensShading: Boolean): File = withContext(Dispatchers.IO) {
        File(directory, identity(frame.file, aspect, lensShading, sourceEdge) + ".f32")
    }

    private fun remember(key: String, image: LinearImage) {
        val bytes = image.width.toLong() * image.height * 12
        while (memory.isNotEmpty() && memoryBytes + bytes > memoryBudget) {
            val oldest = memory.entries.iterator().next()
            memory.remove(oldest.key)
            memoryBytes -= oldest.value.width.toLong() * oldest.value.height * 12
            oldest.value.close()
        }
        memory[key] = image
        memoryBytes += bytes
    }

    private suspend fun read(key: String): LinearImage? {
        val file = File(directory, "$key.f32")
        if (!file.isFile) return null
        try {
            val restored = file.inputStream().use { stream ->
                val input = DataInputStream(stream)
                if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readUTF() != key ||
                    input.readUTF() != "linear-sRGB" || input.readUTF() != "float32-le") throw IOException("obsolete proxy")
                val width = input.readInt()
                val height = input.readInt()
                val previewOnly = input.readBoolean()
                val lensOpcodes = input.readInt()
                val count = width.toLong() * height * 12
                if (width !in 1..sourceEdge || height !in 1..sourceEdge || count > sourceEdge.toLong() * sourceEdge * 12 ||
                    input.readLong() != count || lensOpcodes < 0) throw IOException("invalid proxy dimensions")
                val expectedDigest = ByteArray(32).also { input.readFully(it) }
                val payload = ByteArray(count.toInt()).also { input.readFully(it) }
                if (input.read() != -1 || !MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(expectedDigest))
                    throw IOException("damaged proxy")
                val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val samples = data.asFloatBuffer()
                for (i in 0 until samples.remaining()) {
                    if (!samples[i].isFinite()) throw IOException("non-finite proxy")
                    if (i % 65536 == 0) currentCoroutineContext().ensureActive()
                }
                if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
                    val native = ByteBuffer.allocate(payload.size).order(ByteOrder.nativeOrder())
                    native.asFloatBuffer().put(samples)
                    FilmEngine.restoreProxy(width, height, native, previewOnly, lensOpcodes)
                } else FilmEngine.restoreProxy(width, height, data, previewOnly, lensOpcodes)
            }
            file.setLastModified(System.currentTimeMillis())
            diskHitCount++
            return restored
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            file.delete()
            Journal.w(TAG, "discarded an unreadable source proxy", failure)
            return null
        }
    }

    private suspend fun write(key: String, source: LinearImage) {
        val temporary = File(directory, "$key.${UUID.randomUUID()}.pending")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val bytes = ByteArray(source.width * source.height * 12)
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) source.pixels().get(bytes)
            else ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(source.pixels().asFloatBuffer())
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for (i in 0 until samples.remaining()) {
                if (!samples[i].isFinite()) throw IOException("non-finite decoded source")
                if (i % 65536 == 0) currentCoroutineContext().ensureActive()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            temporary.outputStream().buffered().use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC); output.writeInt(VERSION); output.writeUTF(key)
                output.writeUTF("linear-sRGB"); output.writeUTF("float32-le")
                output.writeInt(source.width); output.writeInt(source.height)
                output.writeBoolean(source.previewOnly); output.writeInt(source.lensOpcodes)
                output.writeLong(bytes.size.toLong()); output.write(digest)
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val length = minOf(65536, bytes.size - offset)
                    output.write(bytes, offset, length)
                    offset += length
                }
            }
            currentCoroutineContext().ensureActive()
            val target = File(directory, "$key.f32")
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            var total = 0L
            directory.listFiles()?.filter { it.extension == "f32" }?.sortedByDescending { it.lastModified() }?.forEach {
                total += it.length()
                if (total > DISK_BUDGET && it != target) it.delete()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IOException) { Journal.w(TAG, "source proxy remains in memory; disk cache unavailable", failure) }
        catch (failure: SecurityException) { Journal.w(TAG, "source proxy remains in memory; disk cache denied", failure) }
        finally { temporary.delete() }
    }

    companion object {
        private const val TAG = "latent-photo-source"
        private const val MAGIC = 0x53504b50
        private const val VERSION = 1
        private const val DISK_BUDGET = 192L * 1024 * 1024
        private const val DECODER = "linear-source-v1"
        @Volatile private var shared: PhotoProxyStore? = null

        fun get(context: Context): PhotoProxyStore = shared ?: synchronized(this) {
            shared ?: PhotoProxyStore(context.applicationContext).also { shared = it }
        }

        internal fun trimSharedMemory() { shared?.trimMemory() }

        private fun identity(file: File, aspect: String, lensShading: Boolean, edge: Int): String {
            if (!file.isFile) throw IOException("原始照片已不存在")
            val digest = MessageDigest.getInstance("SHA-256")
            val orientation = runCatching {
                ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val header = "$DECODER|$VERSION|${file.canonicalPath}|${file.length()}|${file.lastModified()}|$orientation|$aspect|$lensShading|$edge|linear-sRGB|float32"
            digest.update(header.toByteArray(Charsets.UTF_8))
            // A bounded content fingerprint catches replacements even when name and size are reused.
            RandomAccessFile(file, "r").use { input ->
                val buffer = ByteArray(32768)
                val offsets = longArrayOf(0L, maxOf(0L, input.length() / 2 - buffer.size / 2), maxOf(0L, input.length() - buffer.size))
                for (offset in offsets.distinct()) {
                    input.seek(offset)
                    val count = input.read(buffer)
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
