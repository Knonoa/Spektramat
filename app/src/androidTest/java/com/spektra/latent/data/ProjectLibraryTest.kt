package com.spektra.latent.data

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektra.latent.LatentApp
import com.spektra.latent.Startup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Exercises the real catalogue and engine without opening the user's catalogue or originals. */
@RunWith(AndroidJUnit4::class)
class ProjectLibraryTest {
    private lateinit var sandbox: File
    private lateinit var context: IsolatedLibraryContext
    private lateinit var library: Library

    @Before fun createIsolatedLibrary() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LatentApp>()
        val startup = withTimeout(120_000) { app.startup.first { it !is Startup.Loading } }
        assertTrue("The real film engine must be ready: $startup", startup is Startup.Ready)
        sandbox = File(app.cacheDir, "project-library-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = IsolatedLibraryContext(app, sandbox)
        assertNotEquals(app.filesDir.canonicalPath, context.filesDir.canonicalPath)
        assertNotEquals(app.getDatabasePath("library.db").canonicalPath,
            context.getDatabasePath("library.db").canonicalPath)
        assertSame(context, context.applicationContext)
        library = Library(context)
        library.load()
        assertTrue("Each test starts with its own empty catalogue", library.rolls.value.isEmpty())
    }

    @After fun removeOnlyTheTestSandbox() {
        if (::library.isInitialized) closeLibraryDatabase()
        if (::context.isInitialized) context.closeDatabases()
        if (::sandbox.isInitialized) sandbox.deleteRecursively()
    }

    @Test fun removingAProjectFromTheAppPersistsButPreservesEveryOriginalByte() = runBlocking {
        val project = library.createProject("仅从应用移除")
        val source = source("keep-original.png", 1)
        val sourceBytes = source.readBytes()
        val frame = importOne(source, project)
        val stored = frame.file
        assertArrayEquals(sourceBytes, stored.readBytes())

        val removed = library.removeProject(project, deleteFiles = false)
        assertTrue(removed.projectRemoved)
        assertTrue(removed.archived)
        assertEquals(1, removed.removedFrames)
        assertTrue(removed.failedFiles.isEmpty())
        assertFalse(library.rolls.value.any { it.id == project })
        assertNull(library.frame(frame.id))
        assertArrayEquals("Removing from the app must retain its imported original", sourceBytes, stored.readBytes())
        assertArrayEquals("The external import source must remain untouched", sourceBytes, source.readBytes())

        reopenLibrary()
        assertFalse("Archived projects must stay hidden after reopening the database",
            library.rolls.value.any { it.id == project })
        assertNull(library.frame(frame.id))
        assertArrayEquals(sourceBytes, stored.readBytes())
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    @Test fun deletingProjectFilesPreservesOtherProjectsSourcesAndFinishedPrintTargets() = runBlocking {
        val project = library.createProject("删除此项目")
        val firstSource = source("delete-dated.png", 2)
        val neighbourSource = source("keep-dated.png", 3)
        val flatSource = source("delete-explicit.png", 4)
        val first = importOne(firstSource)
        val neighbour = importOne(neighbourSource)
        // Both originals share a date directory. Deleting one project must
        // delete its exact files, rather than recursively deleting that directory.
        assertEquals(first.file.parentFile, neighbour.file.parentFile)
        library.moveToProject(listOf(first.id), project)
        val flat = importOne(flatSource, project)
        val originalsToDelete = listOf(first.file, flat.file)
        val thumbsToDelete = listOf(first.thumb, flat.thumb)
        val keptBytes = neighbour.file.readBytes()
        val originalSources = listOf(firstSource, neighbourSource, flatSource).associateWith { it.readBytes() }

        // Print URIs deliberately point outside the isolated library, just as
        // exported files and gallery items do. No real user album is modified.
        val exported = source("exports/finished-print.png", 5)
        val album = source("album/finished-print.png", 6)
        val finishedTargets = listOf(exported, album).associateWith { it.readBytes() }
        for (target in finishedTargets.keys) {
            library.addPrint(first.id, Uri.fromFile(target).toString(), 24, 16, first.look.film)
        }
        assertEquals(2, library.prints(first.id).size)

        val removed = library.removeProject(project, deleteFiles = true)
        assertTrue("All selected originals were writable", removed.failedFiles.isEmpty())
        assertTrue(removed.projectRemoved)
        assertFalse(removed.archived)
        assertEquals(2, removed.removedFrames)
        assertFalse(library.rolls.value.any { it.id == project })
        assertNull(library.frame(first.id))
        assertNull(library.frame(flat.id))
        originalsToDelete.forEach { assertFalse("Project original remains: $it", it.exists()) }
        thumbsToDelete.forEach { assertFalse("Project thumbnail remains: $it", it.exists()) }
        assertArrayEquals(keptBytes, neighbour.file.readBytes())
        assertNotNull(library.frame(neighbour.id))
        originalSources.forEach { (file, bytes) -> assertArrayEquals("Import source changed: $file", bytes, file.readBytes()) }
        finishedTargets.forEach { (file, bytes) -> assertArrayEquals("Finished print changed: $file", bytes, file.readBytes()) }

        reopenLibrary()
        assertFalse(library.rolls.value.any { it.id == project })
        assertNull(library.frame(first.id))
        assertNull(library.frame(flat.id))
        assertEquals(neighbour.rollId, library.frame(neighbour.id)!!.rollId)
        assertArrayEquals(keptBytes, library.frame(neighbour.id)!!.file.readBytes())
        finishedTargets.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test fun emptyProjectsCanBeRemovedInBothModesAndStayRemoved() = runBlocking {
        val appOnly = library.createProject("空项目，仅从应用移除")
        val disk = library.createProject("空项目，删除文件")
        for ((project, deleteFiles) in listOf(appOnly to false, disk to true)) {
            val removed = library.removeProject(project, deleteFiles)
            assertTrue(removed.projectRemoved)
            assertEquals(0, removed.removedFrames)
            assertTrue(removed.failedFiles.isEmpty())
            assertEquals(!deleteFiles, removed.archived)
            assertFalse(library.rolls.value.any { it.id == project })
        }
        reopenLibrary()
        assertTrue(library.rolls.value.isEmpty())
    }

    @Test fun failedOriginalDeletionKeepsThatFrameAndProjectWhileSuccessfulFilesAreRemoved() = runBlocking {
        val project = library.createProject("部分删除失败")
        val dated = importOne(source("permission-failure.png", 12))
        library.moveToProject(listOf(dated.id), project)
        val flat = importOne(source("permission-success.png", 13), project)
        val retainedBytes = dated.file.readBytes()
        val retainedThumb = dated.thumb.readBytes()
        val finished = source("album/permission-failure-print.png", 16)
        val finishedBytes = finished.readBytes()
        library.addPrint(dated.id, Uri.fromFile(finished).toString(), 24, 16, dated.look.film)
        val protectedDirectory = dated.file.parentFile!!
        assertNotEquals(flat.file.parentFile, protectedDirectory)
        assertTrue("The isolated directory must support permission changes", protectedDirectory.setWritable(false, false))
        try {
            assertFalse("The directory must actually reject unlink operations", protectedDirectory.canWrite())
            val removed = library.removeProject(project, deleteFiles = true)
            assertFalse(removed.projectRemoved)
            assertFalse(removed.archived)
            assertEquals(1, removed.removedFrames)
            assertEquals("The failed original must be identified by its stored relative path",
                listOf(dated.file.relativeTo(File(context.filesDir, "library/originals")).invariantSeparatorsPath),
                removed.failedFiles)
            assertFalse(flat.file.exists())
            assertNull(library.frame(flat.id))
            assertArrayEquals(retainedBytes, dated.file.readBytes())
            assertArrayEquals(retainedThumb, dated.thumb.readBytes())
            assertEquals(Uri.fromFile(finished).toString(), library.prints(dated.id).single().uri)
            assertEquals(listOf(dated.id), library.rolls.value.single { it.id == project }.frames.map { it.id })

            reopenLibrary()
            assertEquals(listOf(dated.id), library.rolls.value.single { it.id == project }.frames.map { it.id })
            assertArrayEquals(retainedBytes, library.frame(dated.id)!!.file.readBytes())
            assertArrayEquals(retainedThumb, library.frame(dated.id)!!.thumb.readBytes())
            assertEquals(Uri.fromFile(finished).toString(), library.prints(dated.id).single().uri)
            assertArrayEquals(finishedBytes, finished.readBytes())
        } finally {
            assertTrue("Restore test-directory permissions for cleanup", protectedDirectory.setWritable(true, false))
        }
        val retried = library.removeProject(project, deleteFiles = true)
        assertTrue(retried.projectRemoved)
        assertTrue(retried.failedFiles.isEmpty())
        assertEquals(1, retried.removedFrames)
        assertFalse(dated.file.exists())
    }

    @Test fun defaultImportsReuseTodaysProjectAndUseARealDateDirectoryAcrossReopening() = runBlocking {
        val day = today()
        val first = importOne(source("dated-first.png", 7))
        reopenLibrary()
        val second = importOne(source("dated-second.png", 8))
        // Avoid treating an actual midnight boundary as a date-grouping bug.
        assumeTrue("The two imports must occur on the same local date", day == today())

        assertEquals(first.rollId, second.rollId)
        val project = library.rolls.value.single { it.id == first.rollId }
        assertEquals(day, project.name)
        assertEquals(listOf(first.id, second.id), project.frames.map { it.id })
        val directory = File(context.filesDir, "library/originals/$day").canonicalFile
        assertTrue(directory.isDirectory)
        for (frame in project.frames) {
            assertEquals("The date must be an actual directory, not only a project label",
                directory, frame.file.parentFile!!.canonicalFile)
            assertTrue(frame.file.parentFile!!.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        }
        assertEquals(1, library.rolls.value.count { it.name == day })
    }

    @Test fun explicitlySelectedProjectImportsKeepTheirProjectAndName() = runBlocking {
        val default = importOne(source("default-project.png", 9))
        val selected = library.createProject("手动指定项目")
        val imported = importOne(source("selected-project.png", 10), selected)
        assertEquals(selected, imported.rollId)
        assertNotEquals(default.rollId, imported.rollId)
        assertEquals("手动指定项目", library.rolls.value.single { it.id == selected }.name)
        assertEquals(File(context.filesDir, "library/originals").canonicalFile,
            imported.file.parentFile!!.canonicalFile)

        reopenLibrary()
        assertEquals(selected, library.frame(imported.id)!!.rollId)
        assertEquals("手动指定项目", library.rolls.value.single { it.id == selected }.name)
        assertEquals(listOf(imported.id), library.rolls.value.single { it.id == selected }.frames.map { it.id })
    }

    @Test fun legacyFlatOriginalPathsStillLoadAndRenderAfterReopening() = runBlocking {
        val imported = importOne(source("legacy-original.png", 11))
        val bytes = imported.file.readBytes()
        val flat = File(context.filesDir, "library/originals/${imported.file.name}")
        closeLibraryDatabase()
        context.closeDatabases()
        imported.file.copyTo(flat, overwrite = true)
        assertTrue(imported.file.delete())
        // Emulate the pre-date-folder catalogue without changing its recipe or
        // metadata. The original schema stored only a basename in frame.file.
        context.openOrCreateDatabase("library.db", Context.MODE_PRIVATE, null).use { db ->
            assertEquals(1, db.update("frame", ContentValues().apply { put("file", flat.name) },
                "id=?", arrayOf(imported.id.toString())))
        }
        reopenLibrary()

        val loaded = library.frame(imported.id)
        assertNotNull(loaded)
        assertEquals(flat.canonicalFile, loaded!!.file.canonicalFile)
        assertArrayEquals(bytes, loaded.file.readBytes())
        val proxy = library.proxy(loaded, longEdge = 48)
        assertNotNull("A legacy original must remain readable by the full engine", proxy)
        assertReadableBitmap(proxy!!)
    }

    @Test fun versionOneCatalogueMigratesWithoutLosingFramesPrintsOrAppRemovalPersistence() = runBlocking {
        val project = library.createProject("原有版本项目")
        val imported = importOne(source("version-one.png", 14), project)
        val bytes = imported.file.readBytes()
        val target = source("album/version-one-print.png", 15)
        val targetBytes = target.readBytes()
        closeLibraryDatabase()
        context.closeDatabases()
        assertTrue(SQLiteDatabase.deleteDatabase(context.getDatabasePath("library.db")))

        // The exact old schema has no project-archive column. Opening Library
        // must run the real onUpgrade path, rather than merely load a v2 DB.
        context.openOrCreateDatabase("library.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE roll (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("""CREATE TABLE frame (
                id INTEGER PRIMARY KEY AUTOINCREMENT, roll_id INTEGER NOT NULL, number INTEGER NOT NULL,
                file TEXT NOT NULL, name TEXT NOT NULL, raw INTEGER NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, taken INTEGER NOT NULL,
                camera TEXT NOT NULL DEFAULT '', lens TEXT NOT NULL DEFAULT '', iso INTEGER NOT NULL DEFAULT 0,
                shutter REAL NOT NULL DEFAULT 0, aperture REAL NOT NULL DEFAULT 0, focal REAL NOT NULL DEFAULT 0,
                metered REAL NOT NULL DEFAULT 0, look TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 1)""")
            db.execSQL("CREATE INDEX frame_roll ON frame (roll_id, number)")
            db.execSQL("""CREATE TABLE print (
                id INTEGER PRIMARY KEY AUTOINCREMENT, frame_id INTEGER NOT NULL, uri TEXT NOT NULL,
                width INTEGER NOT NULL, height INTEGER NOT NULL, created INTEGER NOT NULL, film TEXT NOT NULL)""")
            db.insertOrThrow("roll", null, ContentValues().apply {
                put("id", project); put("name", "原有版本项目"); put("created", imported.taken)
            })
            db.insertOrThrow("frame", null, ContentValues().apply {
                put("id", imported.id); put("roll_id", project); put("number", imported.number)
                put("file", imported.file.name); put("name", imported.name); put("raw", 0)
                put("width", imported.width); put("height", imported.height); put("taken", imported.taken)
                put("metered", imported.meteredEv.toDouble())
                put("look", imported.look.toJson().toString()); put("revision", imported.revision)
            })
            db.insertOrThrow("print", null, ContentValues().apply {
                put("frame_id", imported.id); put("uri", Uri.fromFile(target).toString())
                put("width", 24); put("height", 16); put("created", imported.taken); put("film", imported.look.film)
            })
            db.version = 1
        }
        reopenLibrary()
        assertEquals("原有版本项目", library.rolls.value.single { it.id == project }.name)
        val migrated = library.frame(imported.id)!!
        assertEquals(imported.number, migrated.number)
        assertEquals(imported.look, migrated.look)
        assertEquals(imported.meteredEv, migrated.meteredEv, 0f)
        assertArrayEquals(bytes, migrated.file.readBytes())
        assertEquals(Uri.fromFile(target).toString(), library.prints(imported.id).single().uri)
        val removed = library.removeProject(project, deleteFiles = false)
        assertTrue(removed.projectRemoved)
        assertTrue(removed.archived)
        reopenLibrary()
        assertFalse(library.rolls.value.any { it.id == project })
        assertNull(library.frame(imported.id))
        assertArrayEquals(bytes, imported.file.readBytes())
        assertArrayEquals(targetBytes, target.readBytes())
    }

    private suspend fun importOne(source: File, projectId: Long? = null): Frame {
        val report = library.import(listOf(Uri.fromFile(source)), projectId)
        assertTrue("Import failed: ${report.failed}", report.failed.isEmpty())
        assertEquals(1, report.added.size)
        return library.frame(report.added.single())!!.also {
            assertTrue(it.file.isFile)
            assertArrayEquals("Import must copy exact source bytes", source.readBytes(), it.file.readBytes())
            assertReadableBitmap(it.thumb)
        }
    }

    private suspend fun reopenLibrary() {
        closeLibraryDatabase()
        context.closeDatabases()
        library = Library(context)
        library.load()
    }

    private fun closeLibraryDatabase() {
        // Library currently has no close API. Newer SQLiteOpenHelper versions
        // can bypass Context.openOrCreateDatabase, so close this connection
        // explicitly as well as any connections tracked by the test context.
        val field = Library::class.java.getDeclaredField("db").apply { isAccessible = true }
        val database = field.get(library) as SQLiteDatabase
        if (database.isOpen) database.close()
    }

    private fun assertReadableBitmap(file: File) {
        assertTrue("The real engine must produce a thumbnail/proxy: $file", file.isFile)
        val bitmap = BitmapFactory.decodeFile(file.path)
        assertNotNull("Rendered thumbnail/proxy must decode: $file", bitmap)
        bitmap!!.recycle()
    }

    private fun source(name: String, variant: Int): File {
        val file = File(sandbox, "outside-library/$name").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, Color.rgb(25 + (x * 7 + variant * 13) % 205,
                    25 + (y * 11 + variant * 19) % 205, 25 + (x * 3 + y * 5 + variant * 23) % 205))
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** SQLiteOpenHelper uses applicationContext and openOrCreateDatabase, not just getDatabasePath. */
    private class IsolatedLibraryContext(base: Context, private val sandbox: File) : ContextWrapper(base) {
        private val databases = ArrayList<SQLiteDatabase>()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(sandbox, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(sandbox, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File =
            File(sandbox, "databases/$name").apply { parentFile!!.mkdirs() }

        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            openOrCreateDatabase(name, mode, factory, null)

        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
                .also { databases += it }

        fun closeDatabases() {
            databases.forEach { if (it.isOpen) it.close() }
            databases.clear()
        }
    }
}
