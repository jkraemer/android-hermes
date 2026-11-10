package net.jkraemer.hermes.settings

import android.content.Context
import net.jkraemer.hermes.core.options.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FavoritesStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("favorites-test", Context.MODE_PRIVATE)
    private val sonnet = ModelRef("anthropic", "claude-sonnet-5")
    private val opus = ModelRef("anthropic", "claude-opus-5")
    private val flash = ModelRef("openrouter", "deepseek/deepseek-v4-flash")

    @Test
    fun toggleAddsAndRemoves() {
        val store = FavoritesStore(prefs)
        assertEquals(emptyList<ModelRef>(), store.favorites.value)

        store.toggle(sonnet)
        store.toggle(opus)
        assertEquals(listOf(sonnet, opus), store.favorites.value)

        store.toggle(sonnet)
        assertEquals(listOf(opus), store.favorites.value)
    }

    @Test
    fun moveReorders() {
        val store = FavoritesStore(prefs)
        listOf(sonnet, opus, flash).forEach(store::toggle)

        store.move(0, 2)
        assertEquals(listOf(opus, flash, sonnet), store.favorites.value)

        store.move(2, 1)
        assertEquals(listOf(opus, sonnet, flash), store.favorites.value)
    }

    @Test
    fun moveOutsideTheListChangesNothing() {
        val store = FavoritesStore(prefs)
        listOf(sonnet, opus).forEach(store::toggle)

        store.move(0, 5)
        store.move(-1, 0)

        assertEquals(listOf(sonnet, opus), store.favorites.value)
    }

    @Test
    fun survivesNewInstance() {
        val store = FavoritesStore(prefs)
        listOf(sonnet, flash, opus).forEach(store::toggle)
        store.move(2, 0)

        assertEquals(listOf(opus, sonnet, flash), FavoritesStore(prefs).favorites.value)
    }

    @Test
    fun corruptValueMeansNone() {
        prefs.edit().putString("favorite_models", "{not json").apply()
        assertEquals(emptyList<ModelRef>(), FavoritesStore(prefs).favorites.value)

        prefs.edit().putString("favorite_models", """[{"provider":"a"}]""").apply()
        assertEquals(emptyList<ModelRef>(), FavoritesStore(prefs).favorites.value)
    }
}
