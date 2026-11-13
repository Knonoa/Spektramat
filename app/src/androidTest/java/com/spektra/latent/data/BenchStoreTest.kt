package com.spektra.latent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BenchStoreTest {
    @Test fun removingAndReopeningPreserveOriginalFilesAndPersistMembership() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bench-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        val bytes = ByteArray(8192) { ((it * 37 + 11) % 256).toByte() }
        val source = File(directory, "source.dng").apply { writeBytes(bytes) }
        val imported = File(directory, "imported-copy.dng").also { source.copyTo(it) }
        try {
            prefs.edit().putInt("tab", 1).putLong("frame", 20L).commit()
            val store = BenchStore(prefs)

            assertEquals(30L, store.remove(20L, listOf(10L, 20L, 30L)))
            assertEquals(setOf(20L), BenchStore(prefs).removed.value)
            assertEquals(listOf(10L, 30L), listOf(10L, 20L, 30L).filterNot { it in store.removed.value })
            assertArrayEquals(bytes, source.readBytes())
            assertArrayEquals(bytes, imported.readBytes())
            assertEquals(1, prefs.getInt("tab", -1))
            assertEquals(20L, prefs.getLong("frame", -1L))

            // Explicitly opening the project photograph restores its membership.
            store.restore(20L)
            assertTrue(BenchStore(prefs).removed.value.isEmpty())
            assertArrayEquals(bytes, source.readBytes())
            assertArrayEquals(bytes, imported.readBytes())
        } finally {
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }

    @Test fun removingTheLastPhotographSelectsItsPredecessorAndEventuallyLeavesAnEmptyBench() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "bench-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val store = BenchStore(prefs)
            assertEquals(20L, store.remove(30L, listOf(10L, 20L, 30L)))
            assertEquals(10L, store.remove(20L, listOf(10L, 20L)))
            assertNull(store.remove(10L, listOf(10L)))
            assertEquals(setOf(10L, 20L, 30L), BenchStore(prefs).removed.value)

            store.restore(20L)
            assertFalse(20L in store.removed.value)
            assertEquals(setOf(10L, 30L), store.removed.value)
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
