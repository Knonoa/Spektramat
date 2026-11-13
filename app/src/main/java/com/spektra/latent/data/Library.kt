package com.spektra.latent.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.spektra.latent.Journal
import androidx.exifinterface.media.ExifInterface
import com.spektra.latent.engine.FilmEngine
import com.spektra.latent.engine.LinearImage
import com.spektra.latent.engine.Rebate
import com.spektra.latent.model.Look
import com.spektra.latent.model.Shooting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/** One photograph in the library: the original file, what is known about it, and how it is developed. */
data class Frame(
    val id: Long,
    val rollId: Long,
    /** Its place on the roll, from 1: the frame number printed under it. */
    val number: Int,
    val file: File,
    val name: String,
    val raw: Boolean,
    val width: Int,
    val height: Int,
    /** When it was taken (EXIF), else when it was imported. Milliseconds. */
    val taken: Long,
    val camera: String,
    val lens: String,
    val iso: Int,
    val shutter: Double,
    val aperture: Double,
    val focal: Double,
    /** Import-time metering metadata. Full photo renders meter their current input independently. */
    val meteredEv: Float,
    val look: Look,
    /** The thumbnail on disk shows the look as of this revision. */
    val thumb: File,
    val revision: Int,
) {
    val longEdge: Int get() = max(width, height)
}

data class Roll(val id: Long, val name: String, val created: Long, val frames: List<Frame>)

/** A finished print of a frame, saved to the phone's gallery. */
data class Print(val id: Long, val frameId: Long, val uri: String, val width: Int, val height: Int, val created: Long, val film: String)

class ImportReport(val added: List<Long>, val failed: List<String>)

/** The result of hiding a project, or removing its registered app-owned originals. */
data class ProjectRemoval(
    val projectId: Long,
    /** Hidden frames for an archive; successfully deleted catalogue entries for a disk removal. */
    val removedFrames: Int,
    val failedFiles: List<String>,
    val projectRemoved: Boolean,
    val archived: Boolean,
)

/**
 * The library: originals copied into the app's own storage, a SQLite catalogue
 * of them, and their thumbnails.
 *
 * Originals are never modified and never depend on where they came from: an
 * import copies the bytes, so a later change in the gallery or a revoked
 * permission cannot take a photograph away. Edits are a [Look] in the
 * catalogue; a thumbnail is that look rendered small.
 */
class Library(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "library")
    private val originals = File(root, "originals").apply { mkdirs() }
    private val thumbs = File(root, "thumbs").apply { mkdirs() }
    private val db = Db(app).writableDatabase
    // File writes and catalogue changes form one operation. In particular, a
    // project cannot disappear between choosing its id and filing an original.
    private val catalogue = Mutex()

    private val _rolls = MutableStateFlow<List<Roll>>(emptyList())
    val rolls: StateFlow<List<Roll>> = _rolls

    /** An import in progress: how many of how many are in. Null when none is running. */
    private val _importing = MutableStateFlow<Pair<Int, Int>?>(null)
    val importing: StateFlow<Pair<Int, Int>?> = _importing

    /** What the last import could not read, for the screen to show once. */
    private val _importFailures = MutableStateFlow<List<String>>(emptyList())
    val importFailures: StateFlow<List<String>> = _importFailures
    fun clearImportFailures() { _importFailures.value = emptyList() }


    suspend fun load() = withContext(Dispatchers.IO) {
        catalogue.withLock {
            migrateLooks()
            publish()
        }
    }

    /** Rebuild old-algorithm previews after the catalogue is available. */
    suspend fun refreshPreviews() = withContext(Dispatchers.IO) {
        catalogue.withLock {
            // Cached previews produced by the old LUT engine cannot stand in for
            // exact photo results after an algorithm/data update.
            for (frame in _rolls.value.flatMap { it.frames }) {
                if (frame.thumb.exists()) continue
                try {
                    Decoder.decode(frame.file, THUMB_EDGE, frame.look.lensShading, frame.look.aspect).use {
                        renderThumb(it, frame.look, frame.thumb, rebateOf(frame, frame.look))
                    }
                } catch (e: Exception) {
                    Journal.w(TAG, "thumbnail migration failed for ${frame.name}", e)
                }
            }
        }
    }

    private fun thumbFile(file: File, revision: Int) =
        File(thumbs, "${file.nameWithoutExtension}_${revision}_${FilmEngine.renderCacheKey}.jpg")

    private fun migrateLooks() {
        val changes = ArrayList<Pair<Long, Look>>()
        db.rawQuery("SELECT id, look FROM frame", null).use { cursor ->
            while (cursor.moveToNext()) {
                val json = try { org.json.JSONObject(cursor.getString(1)) } catch (_: Exception) { continue }
                if (json.optInt("algorithmSchema") >= com.spektra.latent.engine.PhotoModel.SCHEMA) continue
                val look = Look.fromJson(json.toString())
                if (json.optDouble("interLayer", 100.0) > 150.0)
                    Journal.i(TAG, "migrated frame ${cursor.getLong(0)} DIR to reference maximum 1.5")
                changes += cursor.getLong(0) to look
            }
        }
        if (changes.isEmpty()) return
        db.beginTransaction()
        try {
            for ((id, look) in changes) db.update("frame", ContentValues().apply {
                put("look", look.toJson().toString())
            }, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        Journal.i(TAG, "migrated ${changes.size} photo recipes to reference algorithm schema ${com.spektra.latent.engine.PhotoModel.SCHEMA}")
    }

    fun frame(id: Long): Frame? = _rolls.value.firstNotNullOfOrNull { r -> r.frames.firstOrNull { it.id == id } }

    /** Copies pictures into an explicit project, or today's shared import project and date directory. */
    suspend fun import(uris: List<Uri>, projectId: Long? = null): ImportReport =
        withContext(Dispatchers.IO) {
            catalogue.withLock {
                val added = ArrayList<Long>()
                val failed = ArrayList<String>()
                val day = dayName()
                val directory = if (projectId == null) File(originals, day) else originals
                var rollId = projectId
                try {
                    uris.forEachIndexed { index, uri ->
                        _importing.value = index to uris.size
                        val name = displayName(uri) ?: "照片 ${index + 1}"
                        var copy: File? = null
                        var thumb: File? = null
                        var registered = false
                        try {
                            if (projectId != null) requireVisibleProject(projectId)
                            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建原片目录")
                            val original = File(directory, UUID.randomUUID().toString() + extension(name))
                            copy = original
                            (app.contentResolver.openInputStream(uri) ?: throw IOException("打不开这个文件")).use { input ->
                                original.outputStream().use { input.copyTo(it, 1 shl 16) }
                            }
                            val size = Decoder.size(original) ?: throw IOException("不是可以读取的图片")
                            val look = Look()
                            val preview = thumbFile(original, 1)
                            thumb = preview
                            val metered = Decoder.decode(original, THUMB_EDGE, look.lensShading).use { small ->
                                val ev = small.meterEv()
                                renderThumb(small, look, preview)
                                ev
                            }
                            val destination = rollId ?: dailyRoll(day).also { rollId = it }
                            added += insertFrame(destination, original, name, size, metered, look)
                            registered = true
                        } catch (e: Exception) {
                            Journal.w(TAG, "import failed: $name", e)
                            if (!registered) {
                                copy?.delete()
                                thumb?.delete()
                                thumb?.let { File(it.parentFile, it.name + ".tmp").delete() }
                            }
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            failed += "$name：${e.message ?: "无法读取"}"
                        }
                        if (registered) publish()
                    }
                } finally {
                    _importing.value = null
                }
                if (failed.isNotEmpty()) _importFailures.value = failed
                ImportReport(added, failed)
            }
        }

    /**
     * A picture the app's own camera has just taken. The file is moved in (it
     * is the app's own temporary file) and filed on today's camera roll with
     * the look of the film that was in the camera.
     *
     * [metered] is capture metadata: 0 for an already exposed JPEG, or null to
     * measure the source. Rendering applies the recipe's reference metering
     * to the current input rather than multiplying this stored measurement.
     */
    suspend fun addCapture(
        shot: File, name: String, look: Look, metered: Float?, roll: Long? = null, keepOnFailure: Boolean = false,
    ): Frame? = withContext(Dispatchers.IO) {
        catalogue.withLock {
            val copy = File(originals, UUID.randomUUID().toString() + extension(name))
            var registered = false
            try {
                // A tether destination can have been removed while a camera was
                // downloading. Preserve the new photograph on today's camera roll.
                val destination = roll?.takeIf(::visibleProject)
                if (!shot.renameTo(copy)) {
                    shot.copyTo(copy, overwrite = true)
                    shot.delete()
                }
                val size = Decoder.size(copy) ?: throw IOException("拍下的文件读不出来")
                // Keep source metering as metadata, independently of the recipe's render metering.
                val ev = metered ?: Decoder.decode(copy, THUMB_EDGE, look.lensShading).use { it.meterEv() }
                val id = insertFrame(destination ?: cameraRoll(), copy, name, size, ev, look)
                registered = true
                publish()
                val filed = frame(id) ?: throw IOException("拍下的照片没有登记上")
                // the thumbnail needs the frame itself: its number and date go onto the film's edge.
                // The photograph is filed by now; a thumbnail that fails does not unfile it.
                try {
                    Decoder.decode(copy, THUMB_EDGE, look.lensShading, look.aspect).use {
                        renderThumb(it, look, filed.thumb, rebateOf(filed, look))
                    }
                } catch (e: Exception) {
                    Journal.w(TAG, "no thumbnail for $name", e)
                }
                return@withLock filed
            } catch (e: Exception) {
                Journal.w(TAG, "capture not filed: $name", e)
                // Keep a durable original even if publishing its catalogue failed.
                if (!registered) {
                    if (keepOnFailure) {
                        // the caller has no other copy: the file goes back where it came from
                        if (copy.exists() && !shot.exists() && !copy.renameTo(shot)) copy.copyTo(shot, overwrite = true)
                        copy.delete()
                    } else {
                        copy.delete()
                        shot.delete()
                    }
                }
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }
    }

    /** Saves the frame's look and redraws its thumbnail to match. */
    suspend fun saveLook(frameId: Long, look: Look) = withContext(Dispatchers.IO) {
        catalogue.withLock {
            val f = frame(frameId) ?: return@withLock
            if (f.look == look) return@withLock
            val revision = f.revision + 1
            val thumb = thumbFile(f.file, revision)
            try {
                Decoder.decode(f.file, THUMB_EDGE, look.lensShading, look.aspect).use {
                    renderThumb(it, look, thumb, rebateOf(f, look))
                }
            } catch (e: Exception) {
                Journal.w(TAG, "thumbnail failed for ${f.name}", e)
            }
            // larger copies of the previous revision are of no use any more
            thumbs.listFiles { file -> file.name.startsWith(f.file.nameWithoutExtension + "_${f.revision}_p") }?.forEach { it.delete() }
            db.update("frame", ContentValues().apply {
                put("look", look.toJson().toString())
                put("revision", revision)
            }, "id=?", arrayOf(frameId.toString()))
            if (thumb.exists()) f.thumb.delete()
            publish()
        }
    }

    suspend fun delete(frameIds: Collection<Long>) = withContext(Dispatchers.IO) {
        catalogue.withLock {
            val selected = frameIds.distinct().mapNotNull(::frame)
            val retained = retainedOriginals(selected.mapTo(HashSet()) { it.id })
            for (f in selected) {
                if (deleteOriginal(storedOriginal(f.file), retained)) removeFrameRecord(f.id, f.file)
            }
            publish()
        }
    }

    /**
     * App removal hides the catalogue while retaining original bytes and their
     * index. Disk removal only unlinks this project's registered files inside
     * originals; an unsafe path or failed unlink leaves that frame recoverable.
     * Exported print URIs and import source URIs are never opened or deleted.
     */
    suspend fun removeProject(projectId: Long, deleteFiles: Boolean): ProjectRemoval = withContext(Dispatchers.IO) {
        catalogue.withLock {
            val exists = db.rawQuery("SELECT id FROM roll WHERE id=?", arrayOf(projectId.toString()))
                .use { it.moveToFirst() }
            if (!exists) return@withLock ProjectRemoval(projectId, 0, emptyList(), false, false)
            val originalsInProject = ArrayList<Pair<Long, String>>()
            db.rawQuery("SELECT id, file FROM frame WHERE roll_id=?", arrayOf(projectId.toString())).use { cursor ->
                while (cursor.moveToNext()) originalsInProject += cursor.getLong(0) to cursor.getString(1)
            }
            if (!deleteFiles) {
                db.update("roll", ContentValues().apply { put("archived", 1) }, "id=?", arrayOf(projectId.toString()))
                publish()
                return@withLock ProjectRemoval(projectId, originalsInProject.size, emptyList(), true, true)
            }

            val retained = retainedOriginals(originalsInProject.mapTo(HashSet()) { it.first })
            val failed = ArrayList<String>()
            var removed = 0
            for ((id, path) in originalsInProject) {
                if (deleteOriginal(path, retained)) {
                    removeFrameRecord(id, originalFile(path))
                    removed++
                } else {
                    failed += path
                }
            }
            val projectRemoved = failed.isEmpty()
            if (projectRemoved) db.delete("roll", "id=?", arrayOf(projectId.toString()))
            publish()
            ProjectRemoval(projectId, removed, failed, projectRemoved, false)
        }
    }

    /** Projects share the existing roll catalogue; creating one never copies originals. */
    suspend fun createProject(name: String): Long = withContext(Dispatchers.IO) {
        catalogue.withLock {
            val id = db.insertOrThrow("roll", null, ContentValues().apply {
                put("name", name.trim().ifEmpty { "未命名项目" }); put("created", System.currentTimeMillis())
            })
            publish()
            id
        }
    }

    suspend fun moveToProject(frameIds: Collection<Long>, projectId: Long) = withContext(Dispatchers.IO) {
        catalogue.withLock {
            db.beginTransaction()
            try {
                var n = db.rawQuery("SELECT COALESCE(MAX(number),0) FROM frame WHERE roll_id=?", arrayOf(projectId.toString())).use { it.moveToFirst(); it.getInt(0) }
                requireVisibleProject(projectId)
                for (id in frameIds.distinct()) {
                    val current = db.rawQuery("SELECT frame.roll_id FROM frame JOIN roll ON roll.id=frame.roll_id WHERE frame.id=? AND roll.archived=0", arrayOf(id.toString())).use { if(it.moveToFirst()) it.getLong(0) else null }
                    if(current != null && current != projectId)
                        db.update("frame", ContentValues().apply { put("roll_id",projectId); put("number",++n) }, "id=?", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            publish()
        }
    }

    suspend fun renameRoll(rollId: Long, name: String) = withContext(Dispatchers.IO) {
        catalogue.withLock {
            db.update("roll", ContentValues().apply { put("name", name) }, "id=? AND archived=0", arrayOf(rollId.toString()))
            publish()
        }
    }

    suspend fun addPrint(frameId: Long, uri: String, width: Int, height: Int, film: String) = withContext(Dispatchers.IO) {
        catalogue.withLock {
            if (!visibleFrame(frameId)) return@withLock -1L
            db.insert("print", null, ContentValues().apply {
                put("frame_id", frameId); put("uri", uri); put("width", width); put("height", height)
                put("created", System.currentTimeMillis()); put("film", film)
            })
        }
    }

    suspend fun prints(frameId: Long): List<Print> = withContext(Dispatchers.IO) {
        catalogue.withLock {
            if (!visibleFrame(frameId)) return@withLock emptyList()
            val out = ArrayList<Print>()
            db.rawQuery(
                "SELECT id, frame_id, uri, width, height, created, film FROM print WHERE frame_id=? ORDER BY created DESC",
                arrayOf(frameId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out += Print(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4), c.getLong(5), c.getString(6))
                }
            }
            out
        }
    }

    // ---- thumbnails

    private fun renderThumb(small: LinearImage, look: Look, out: File, rebate: Rebate = Rebate()) {
        val rendered = picture(small, look, rebate)
        val bitmap = if (rendered.config == Bitmap.Config.ARGB_8888 &&
            rendered.colorSpace == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)) rendered else
            Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888).also {
                android.graphics.Canvas(it).drawBitmap(rendered, 0f, 0f, null)
                rendered.recycle()
            }
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!tmp.renameTo(out)) throw IOException("thumbnail could not be saved")
        } finally {
            tmp.delete()
            bitmap.recycle()
        }
    }

    /** Every photo surface, including thumbnails, uses the full spectral engine. */
    private fun picture(small: LinearImage, look: Look, rebate: Rebate): Bitmap {
        val base = look.order(seed = 1L)
        val order = if (rebate.idle) base else base.copy(rebate = rebate)
        return FilmEngine.develop(small, look.photoGrade(), order, look.finish(), look.finishTable(), lane = FilmEngine.THUMB)
    }

    /** What [frame] puts on its negative besides the picture, with [look]. */
    fun rebateOf(frame: Frame, look: Look): Rebate =
        if (!look.edge.on && !look.date.on) Rebate() else look.rebate(Shooting.of(frame), frameSeed(frame), frame.number)

    /**
     * A larger copy of the frame as it prints, for places that show it bigger
     * than a thumbnail (the light table). Made once per revision and kept on
     * disk; null when the original cannot be read.
     */
    suspend fun proxy(frame: Frame, longEdge: Int = PROXY_EDGE): File? = withContext(Dispatchers.IO) {
        catalogue.withLock {
            val current = this@Library.frame(frame.id) ?: return@withLock null
            val file = File(thumbs, "${current.file.nameWithoutExtension}_${current.revision}_p${longEdge}_${FilmEngine.renderCacheKey}.jpg")
            if (file.exists()) return@withLock file
            try {
                Decoder.decode(current.file, longEdge, current.look.lensShading, current.look.aspect).use {
                    renderThumb(it, current.look, file, rebateOf(current, current.look))
                }
                file
            } catch (e: Exception) {
                Journal.w(TAG, "no proxy for ${frame.name}", e)
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }

    // ---- catalogue

    private fun dayName(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    private fun dailyRoll(day: String): Long {
        db.rawQuery("SELECT id FROM roll WHERE name=? AND archived=0 ORDER BY id DESC LIMIT 1", arrayOf(day)).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return db.insertOrThrow("roll", null, ContentValues().apply {
            put("name", day); put("created", System.currentTimeMillis())
        })
    }

    /** The roll a tethered camera's pictures go onto today. */
    suspend fun tetherRoll(): Long = withContext(Dispatchers.IO) {
        catalogue.withLock {
            val id = cameraRoll("联机")
            publish()
            id
        }
    }

    /** One roll per day for what the camera shoots; a roll that was renamed is left alone and a new one begun. */
    private fun cameraRoll(kind: String = "相机"): Long {
        val now = System.currentTimeMillis()
        val name = "$kind " + dayName(now)
        db.rawQuery("SELECT id FROM roll WHERE name=? AND archived=0 ORDER BY id DESC LIMIT 1", arrayOf(name)).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return db.insertOrThrow("roll", null, ContentValues().apply { put("name", name); put("created", now) })
    }

    private fun insertFrame(rollId: Long, file: File, name: String, size: IntArray, metered: Float, look: Look): Long {
        requireVisibleProject(rollId)
        val exif = try { ExifInterface(file) } catch (e: Exception) { null }
        val number = db.rawQuery("SELECT COALESCE(MAX(number), 0) + 1 FROM frame WHERE roll_id=?", arrayOf(rollId.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 1 }
        return db.insertOrThrow("frame", null, ContentValues().apply {
            put("roll_id", rollId); put("number", number)
            put("file", storedOriginal(file)); put("name", name)
            put("raw", if (FilmEngine.isRaw(file.path)) 1 else 0)
            put("width", size[0]); put("height", size[1])
            put("taken", exif?.let(::takenAt) ?: System.currentTimeMillis())
            put("camera", listOfNotNull(exif?.getAttribute(ExifInterface.TAG_MAKE), exif?.getAttribute(ExifInterface.TAG_MODEL))
                .joinToString(" ") { it.trim() }.trim())
            put("lens", exif?.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim() ?: "")
            put("iso", exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0) ?: 0)
            put("shutter", exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0) ?: 0.0)
            put("aperture", exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0) ?: 0.0)
            put("focal", exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0) ?: 0.0)
            put("metered", metered.toDouble())
            put("look", look.toJson().toString())
            put("revision", 1)
        })
    }

    private fun visibleProject(projectId: Long): Boolean =
        db.rawQuery("SELECT id FROM roll WHERE id=? AND archived=0", arrayOf(projectId.toString()))
            .use { it.moveToFirst() }

    private fun requireVisibleProject(projectId: Long) {
        require(visibleProject(projectId)) { "项目已被移除" }
    }

    private fun visibleFrame(frameId: Long): Boolean = db.rawQuery(
        "SELECT frame.id FROM frame JOIN roll ON roll.id=frame.roll_id WHERE frame.id=? AND roll.archived=0",
        arrayOf(frameId.toString()),
    ).use { it.moveToFirst() }

    /** New rows use relative paths; old absolute paths and flat basenames remain readable. */
    private fun storedOriginal(file: File): String = file.relativeTo(originals).invariantSeparatorsPath

    private fun originalFile(path: String): File = File(path).let { if (it.isAbsolute) it else File(originals, path) }

    /** Files referenced by another frame are retained even if an old catalogue shares a path. */
    private fun retainedOriginals(removing: Set<Long>): Set<String> {
        val retained = HashSet<String>()
        db.rawQuery("SELECT id, file FROM frame", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) !in removing) {
                    runCatching { originalFile(cursor.getString(1)).canonicalPath }.getOrNull()?.let(retained::add)
                }
            }
        }
        return retained
    }

    private fun deleteOriginal(path: String, retained: Set<String>): Boolean = try {
        val directory = originals.canonicalFile
        val file = originalFile(path).canonicalFile
        when {
            !file.path.startsWith(directory.path + File.separator) -> false
            file.path in retained -> false
            !file.exists() -> true
            !file.isFile -> false
            else -> file.delete()
        }
    } catch (e: Exception) {
        Journal.w(TAG, "could not delete registered original: $path", e)
        false
    }

    private fun removeFrameRecord(id: Long, original: File) {
        db.beginTransaction()
        try {
            db.delete("print", "frame_id=?", arrayOf(id.toString()))
            db.delete("frame", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        thumbs.listFiles { file -> file.name.startsWith(original.nameWithoutExtension + "_") }?.forEach { it.delete() }
    }

    private fun publish() {
        val frames = HashMap<Long, ArrayList<Frame>>()
        db.rawQuery(
            "SELECT frame.id, roll_id, number, file, frame.name, raw, width, height, taken, camera, lens, iso, shutter, aperture, focal, metered, look, revision FROM frame JOIN roll ON roll.id=frame.roll_id WHERE roll.archived=0 ORDER BY roll_id, number",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val file = originalFile(c.getString(3))
                val revision = c.getInt(17)
                frames.getOrPut(c.getLong(1)) { ArrayList() } += Frame(
                    id = c.getLong(0), rollId = c.getLong(1), number = c.getInt(2), file = file, name = c.getString(4),
                    raw = c.getInt(5) != 0, width = c.getInt(6), height = c.getInt(7), taken = c.getLong(8),
                    camera = c.getString(9), lens = c.getString(10), iso = c.getInt(11), shutter = c.getDouble(12),
                    aperture = c.getDouble(13), focal = c.getDouble(14), meteredEv = c.getFloat(15),
                    look = Look.fromJson(c.getString(16)),
                    thumb = thumbFile(file, revision), revision = revision,
                )
            }
        }
        val rolls = ArrayList<Roll>()
        db.rawQuery("SELECT id, name, created FROM roll WHERE archived=0 ORDER BY created DESC, id DESC", null).use { c ->
            while (c.moveToNext()) {
                val list = frames[c.getLong(0)] ?: arrayListOf()
                rolls += Roll(c.getLong(0), c.getString(1), c.getLong(2), list)
            }
        }
        _rolls.value = rolls
    }

    /** EXIF's "2026:11:11 15:04:05" as local time; null when absent or malformed. */
    private fun takenAt(exif: ExifInterface): Long? {
        val text = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return try {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text.trim())?.time?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    private fun extension(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }

    private class Db(context: Context) : SQLiteOpenHelper(context, "library.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE roll (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)")
            db.execSQL(
                """CREATE TABLE frame (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, roll_id INTEGER NOT NULL, number INTEGER NOT NULL,
                    file TEXT NOT NULL, name TEXT NOT NULL, raw INTEGER NOT NULL,
                    width INTEGER NOT NULL, height INTEGER NOT NULL, taken INTEGER NOT NULL,
                    camera TEXT NOT NULL DEFAULT '', lens TEXT NOT NULL DEFAULT '', iso INTEGER NOT NULL DEFAULT 0,
                    shutter REAL NOT NULL DEFAULT 0, aperture REAL NOT NULL DEFAULT 0, focal REAL NOT NULL DEFAULT 0,
                    metered REAL NOT NULL DEFAULT 0, look TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1)""",
            )
            db.execSQL("CREATE INDEX frame_roll ON frame (roll_id, number)")
            db.execSQL(
                """CREATE TABLE print (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, frame_id INTEGER NOT NULL, uri TEXT NOT NULL,
                    width INTEGER NOT NULL, height INTEGER NOT NULL, created INTEGER NOT NULL, film TEXT NOT NULL)""",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE roll ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        }
    }

    companion object {
        private const val TAG = "latent-library"
        /** Long edge of thumbnails and of the picture metering is taken from. */
        const val THUMB_EDGE = 640
        /** Long edge of the larger copies the light table shows. */
        const val PROXY_EDGE = 1600

        /** The advance and scan of one frame: the same every time it is printed. */
        fun frameSeed(frame: Frame): Int = (frame.id % 9973L).toInt() + 1
    }
}
