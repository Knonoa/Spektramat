package com.spektra.latent.data

import android.content.Context
import com.spektra.latent.Journal
import com.spektra.latent.model.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A look with a name: a film, a print, an edge, a date and a finish, kept to be used again. */
class Recipe(val id: Long, val name: String, val look: Look)

/**
 * Named recipes, kept in one small file of the app's own. A recipe holds how
 * a photograph is developed -- never what was corrected about one particular
 * photograph (its exposure, white balance, shape).
 */
class Recipes(context: Context) {
    private val file = File(context.applicationContext.filesDir, "recipes.json")
    private val _all = MutableStateFlow<List<Recipe>>(emptyList())
    val all: StateFlow<List<Recipe>> = _all

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        try {
            val array = JSONArray(file.readText())
            _all.value = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Recipe(o.getLong("id"), o.getString("name"), Look.fromJson(o.optJSONObject("look")?.toString()))
            }
            if ((0 until array.length()).any {
                (array.getJSONObject(it).optJSONObject("look")?.optInt("algorithmSchema") ?: 0) <
                    com.spektra.latent.engine.PhotoModel.SCHEMA
            }) {
                write(_all.value)
                Journal.i(TAG, "migrated saved recipes to reference algorithm schema ${com.spektra.latent.engine.PhotoModel.SCHEMA}")
            }
        } catch (e: Exception) {
            // a file that cannot be read is kept beside a fresh start, not overwritten
            Journal.w(TAG, "recipes could not be read", e)
            file.copyTo(File(file.parentFile, "recipes-unreadable-${System.currentTimeMillis()}.json"), overwrite = true)
        }
    }

    fun find(id: Long): Recipe? = _all.value.firstOrNull { it.id == id }

    /** Keeps [look] as a recipe called [name]; a recipe of that name is replaced. */
    suspend fun save(name: String, look: Look): Recipe = withContext(Dispatchers.IO) {
        val clean = name.trim().ifEmpty { "未命名配方" }
        val existing = _all.value.firstOrNull { it.name == clean }
        val recipe = Recipe(existing?.id ?: ((_all.value.maxOfOrNull { it.id } ?: 0L) + 1L), clean, look.asRecipe())
        write(listOf(recipe) + _all.value.filter { it.id != recipe.id })
        recipe
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) { write(_all.value.filter { it.id != id }) }

    private fun write(list: List<Recipe>) {
        val array = JSONArray()
        for (r in list) array.put(JSONObject().put("id", r.id).put("name", r.name).put("look", r.look.toJson()))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) throw IOException("配方没有存上")
        _all.value = list
    }

    private companion object {
        const val TAG = "latent-recipes"
    }
}
