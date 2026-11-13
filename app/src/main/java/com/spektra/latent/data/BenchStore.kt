package com.spektra.latent.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Membership of the workbench, independent of the library catalogue.
 * Removing a photograph here keeps its original, project, edits and prints.
 */
class BenchStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("bench", Context.MODE_PRIVATE))

    private val _removed = MutableStateFlow(
        prefs.getStringSet(REMOVED, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }.toSet(),
    )
    val removed: StateFlow<Set<Long>> = _removed

    /** Returns the next neighbour, or the preceding one when the last photograph is removed. */
    fun remove(frameId: Long, orderedFrameIds: List<Long>): Long? {
        if (frameId <= 0) return orderedFrameIds.firstOrNull { it !in _removed.value }
        save(_removed.value + frameId)
        val at = orderedFrameIds.indexOf(frameId)
        return if (at < 0) orderedFrameIds.firstOrNull { it !in _removed.value }
        else orderedFrameIds.drop(at + 1).firstOrNull { it !in _removed.value }
            ?: orderedFrameIds.take(at).lastOrNull { it !in _removed.value }
    }

    /** Explicitly opening an existing project photograph puts it back on the workbench. */
    fun restore(frameId: Long) {
        if (frameId > 0 && frameId in _removed.value) save(_removed.value - frameId)
    }

    private fun save(next: Set<Long>) {
        prefs.edit().putStringSet(REMOVED, next.map { it.toString() }.toSet()).apply()
        _removed.value = next
    }

    private companion object {
        const val REMOVED = "removedFrames"
    }
}
