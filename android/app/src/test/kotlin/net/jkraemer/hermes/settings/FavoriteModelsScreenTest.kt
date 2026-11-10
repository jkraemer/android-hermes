package net.jkraemer.hermes.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import net.jkraemer.hermes.chat.FakePeer
import net.jkraemer.hermes.core.options.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

private const val MODEL_OPTIONS = """{"providers":[
  {"slug":"anthropic","name":"Anthropic","authenticated":true,"models":["claude-sonnet-5","claude-opus-5"]},
  {"slug":"openrouter","name":"OpenRouter","authenticated":true,"models":["deepseek/deepseek-v4-flash","anthropic/claude-sonnet-5"]}
]}"""

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class FavoriteModelsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val peer = FakePeer().apply { results["model.options"] = MODEL_OPTIONS }
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("favorite-models-test", Context.MODE_PRIVATE)
    private val store = FavoritesStore(prefs)

    private val isFavoriteRow = SemanticsMatcher("is a favorite row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("fav:") == true
    }

    @Before fun clean() = prefs.edit().clear().apply()

    private fun show() {
        compose.setContent {
            MaterialTheme { FavoriteModelsScreen(FavoriteModelsViewModel(peer, store), onBack = {}) }
        }
    }

    // The catalogue loads on a coroutine that resumes on Main, so the looper has to be pumped while waiting.
    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        ShadowLooper.idleMainLooper()
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun listsModelsByProvider() {
        show()
        waitForText("Anthropic")

        compose.onNodeWithText("Anthropic").assertExists()
        compose.onNodeWithText("OpenRouter").assertExists()
        compose.onNodeWithText("claude-sonnet-5").assertExists()
        compose.onNodeWithText("deepseek/deepseek-v4-flash").assertExists()
        compose.onAllNodesWithContentDescription("Add to favorites").assertCountEquals(4)
    }

    @Test
    fun starringAModelFavoritesIt() {
        show()
        waitForText("Anthropic")

        compose.onNode(hasContentDescription("Add to favorites") and hasAnyAncestor(hasTestTag("model:anthropic/claude-opus-5"))).performClick()

        assertEquals(listOf(ModelRef("anthropic", "claude-opus-5")), store.favorites.value)
        compose.onAllNodesWithContentDescription("Remove from favorites").assertCountEquals(2)
    }

    @Test
    fun searchFilters() {
        show()
        waitForText("Anthropic")

        compose.onNodeWithText("Search models").performTextInput("SONNET")

        compose.onAllNodesWithText("claude-sonnet-5").assertCountEquals(1)
        compose.onNodeWithText("anthropic/claude-sonnet-5").assertExists()
        compose.onAllNodesWithText("claude-opus-5").assertCountEquals(0)
        compose.onAllNodesWithText("deepseek/deepseek-v4-flash").assertCountEquals(0)
    }

    @Test
    fun favoritesSectionAtTopInOrder() {
        store.toggle(ModelRef("openrouter", "deepseek/deepseek-v4-flash"))
        store.toggle(ModelRef("anthropic", "claude-sonnet-5"))
        show()
        waitForText("Favorites")

        compose.onNodeWithText("Favorites").assertExists()
        compose.onAllNodesWithContentDescription("Reorder").assertCountEquals(2)
        assertEquals(
            listOf("fav:openrouter/deepseek/deepseek-v4-flash", "fav:anthropic/claude-sonnet-5"),
            compose.onAllNodes(isFavoriteRow).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] },
        )
    }

    @Test
    fun retiredFavoriteIsGreyedAndRemovable() {
        store.toggle(ModelRef("anthropic", "claude-gone"))
        show()
        waitForText("No longer offered")

        compose.onNode(hasContentDescription("Remove from favorites") and hasAnyAncestor(hasTestTag("fav:anthropic/claude-gone"))).performClick()

        assertEquals(emptyList<ModelRef>(), store.favorites.value)
        compose.onAllNodesWithText("No longer offered").assertCountEquals(0)
    }

    @Test
    fun loadFailureShowsRetry() {
        peer.failing = setOf("model.options")
        show()
        waitForText("Couldn't load models")

        peer.failing = emptySet()
        compose.onNodeWithText("Retry").performClick()
        waitForText("Anthropic")
    }
}
