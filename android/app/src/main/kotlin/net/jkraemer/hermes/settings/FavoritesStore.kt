package net.jkraemer.hermes.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jkraemer.hermes.core.options.ModelRef
import org.json.JSONArray
import org.json.JSONObject

/** The models the user marked as favorites, in the order they chose; kept on the phone, never sent to Hermes. */
class FavoritesStore(private val prefs: SharedPreferences) {
    private val _favorites = MutableStateFlow(read())
    val favorites: StateFlow<List<ModelRef>> = _favorites.asStateFlow()

    /** Adds [ref] at the end, or removes it when it is a favorite already. */
    fun toggle(ref: ModelRef) = write(if (ref in _favorites.value) _favorites.value - ref else _favorites.value + ref)

    /** Moves the favorite at index [from] to index [to]; indexes outside the list change nothing. */
    fun move(from: Int, to: Int) {
        val current = _favorites.value
        if (from !in current.indices || to !in current.indices) return
        write(current.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun write(favorites: List<ModelRef>) {
        _favorites.value = favorites
        val json = JSONArray(favorites.map { JSONObject().put("provider", it.provider).put("model", it.model) })
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    /** An unreadable value counts as no favorites. */
    private fun read(): List<ModelRef> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        List(array.length()) { array.getJSONObject(it).let { entry -> ModelRef(entry.getString("provider"), entry.getString("model")) } }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "favorite_models"
    }
}
