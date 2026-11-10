package net.jkraemer.hermes.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsDisplayed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import net.jkraemer.hermes.core.options.ModelRef
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.settings.SeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Instant

private val NOW = Instant.parse("2026-11-05T12:00:00Z")

private val FLASH = ModelRef("openrouter", "deepseek/deepseek-v4-flash")
private val SONNET = ModelRef("openrouter", "anthropic/claude-sonnet-5")
private val GONE = ModelRef("openrouter", "retired/model")

private fun resumeWith(info: String?) =
    """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":false,"open_requests":[]${info?.let { ""","info":$it""" }.orEmpty()}}"""

private fun info(reasoning: String = "high", fast: Boolean = false, yolo: Boolean = false, model: String = FLASH.model) =
    """{"model":"$model","provider":"openrouter","reasoning_effort":"$reasoning","fast":$fast,"yolo":$yolo}"""

/** Flash can run fast and cannot switch reasoning off; Sonnet can switch it off; Mini does no reasoning. */
private const val MODEL_OPTIONS = """
    {"providers":[{"slug":"openrouter","name":"OpenRouter","authenticated":true,
      "models":["deepseek/deepseek-v4-flash","anthropic/claude-sonnet-5","mini/mini"],
      "capabilities":{
        "deepseek/deepseek-v4-flash":{"reasoning":true,"can_disable_reasoning":false,"fast":true},
        "anthropic/claude-sonnet-5":{"reasoning":true,"can_disable_reasoning":true,"fast":false},
        "mini/mini":{"reasoning":false,"fast":false}}}]}
"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionOptionsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val peer = FakePeer()
    private val favorites = MutableStateFlow(listOf(FLASH, SONNET, GONE))
    private var favoriteShortcuts = 0
    private lateinit var viewModel: ChatViewModel

    private fun show(info: String? = info()) {
        peer.resume = resumeWith(info)
        peer.results["model.options"] = MODEL_OPTIONS
        peer.results["config.set"] = """{"value":"high"}"""
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-sheet-test", Context.MODE_PRIVATE)
        viewModel = ChatViewModel(peer, "stored1", "Chat", MutableStateFlow(emptySet()), SeenStore(prefs), favorites = favorites) { NOW }
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    viewModel,
                    cron = false,
                    onOpenMenu = {},
                    onNewChat = {},
                    onRetryConnect = {},
                    onStoredSessionId = {},
                    onOpenFavorites = { favoriteShortcuts++ },
                    clock = { NOW },
                    keyboardVisible = false,
                )
            }
        }
        waitUntil { viewModel.chat.value.liveSessionId != null }
        compose.onNodeWithTag("composer").requestFocus()
        compose.waitForIdle()
    }

    /** Emitted events and coroutine hand-backs land on the main looper, which waitUntil alone does not run. */
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        ShadowLooper.idleMainLooper()
        condition()
    }

    private fun waitForText(text: String) = waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForSheet() = waitUntil { compose.onAllNodes(hasTestTag("options-sheet")).fetchSemanticsNodes().isNotEmpty() }

    private fun openSheet() {
        compose.onNodeWithTag("model-chip").performClick()
        waitForSheet()
    }

    private fun configSets() = peer.calls.filter { it.first == "config.set" }.map { it.second }

    private fun String.param(params: kotlinx.serialization.json.JsonObject) = (params[this] as? JsonPrimitive)?.content

    @Test
    fun chipShowsModelAndReasoning() {
        show()
        compose.onNodeWithText("deepseek-v4-flash · high").assertIsDisplayed()
    }

    @Test
    fun chipOmitsDefaultReasoning() {
        show(info(reasoning = ""))
        compose.onNodeWithText("deepseek-v4-flash").assertIsDisplayed()
    }

    @Test
    fun chipSaysOffForNoReasoning() {
        show(info(reasoning = "none"))
        compose.onNodeWithText("deepseek-v4-flash · off").assertIsDisplayed()
    }

    @Test
    fun chipDisabledWithoutOptions() {
        show(info = null)
        compose.onNodeWithTag("model-chip").assertIsNotEnabled()
    }

    @Test
    fun chipDisabledWhileReconnecting() {
        show()
        compose.onNodeWithText("deepseek-v4-flash · high").assertIsEnabled()

        peer.state.value = ConnectionState.Connecting
        waitForText("Reconnecting…")

        compose.onNodeWithText("deepseek-v4-flash · high").assertIsNotEnabled()
    }

    @Test
    fun sheetListsOfferedFavoritesWithCurrentTicked() {
        show()
        openSheet()
        waitForText(SONNET.model)

        compose.onNode(hasText(FLASH.model) and isSelected()).assertIsDisplayed()
        compose.onNode(hasText(SONNET.model)).assertIsNotSelected()
        compose.onNodeWithText(GONE.model).assertDoesNotExist()
    }

    @Test
    fun tappingAnotherFavoriteSwitchesOnlyTheSession() {
        show()
        openSheet()
        waitForText(SONNET.model)
        peer.results["config.set"] = """{"value":"${SONNET.model}","scope":"session"}"""

        compose.onNodeWithText(SONNET.model).performClick()
        waitUntil { compose.onAllNodesWithText(SONNET.model).fetchSemanticsNodes().isNotEmpty() && viewModel.chat.value.options?.model == SONNET.model }

        val set = configSets().single()
        assertEquals("model", "key".param(set))
        assertEquals("live1", "session_id".param(set))
        assertEquals("${SONNET.model} --provider openrouter --session", "value".param(set))
        assertTrue("scope" !in set)
        compose.onNode(hasText(SONNET.model) and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("claude-sonnet-5 · high").assertExists()
    }

    @Test
    fun noFavoritesShowsShortcut() {
        favorites.value = emptyList()
        show()
        openSheet()
        waitForText("No favorite models yet")

        compose.onNodeWithText("Choose favorites").performClick()

        assertEquals(1, favoriteShortcuts)
    }

    @Test
    fun confirmationDialog() {
        show()
        openSheet()
        waitForText(SONNET.model)
        peer.results["config.set"] = """{"confirm_required":true,"confirm_message":"costly"}"""

        compose.onNodeWithText(SONNET.model).performClick()
        waitForText("costly")
        assertEquals(1, configSets().size)

        peer.results["config.set"] = """{"value":"${SONNET.model}","scope":"session"}"""
        compose.onNodeWithText("Switch").performClick()
        waitUntil { configSets().size == 2 }

        val resent = configSets().last()
        assertEquals("${SONNET.model} --provider openrouter --session", "value".param(resent))
        assertEquals("true", "confirm_expensive_model".param(resent))
        assertTrue(viewModel.chat.value.items.isEmpty())
    }

    @Test
    fun deferredSwitchShowsNote() {
        show()
        openSheet()
        waitForText(SONNET.model)
        peer.results["config.set"] = """{"value":"${SONNET.model}","deferred":true}"""

        compose.onNodeWithText(SONNET.model).performClick()

        waitForText("Applies from the next message")
    }

    @Test
    fun reasoningChoices() {
        show(info(reasoning = "high", model = SONNET.model))
        openSheet()
        waitForText("XHigh")

        for (level in listOf("Off", "Minimal", "Low", "Medium", "High", "XHigh", "Max", "Ultra")) compose.onNodeWithText(level).assertExists()
        compose.onNodeWithText("High").assertIsSelected()
        compose.onNodeWithText("Low").assertIsNotSelected()
    }

    @Test
    fun noReasoningChoiceSelectedForTheDefault() {
        show(info(reasoning = "", model = SONNET.model))
        openSheet()
        waitForText("XHigh")

        for (level in listOf("Off", "Minimal", "Low", "Medium", "High", "XHigh", "Max", "Ultra")) compose.onNodeWithText(level).assertIsNotSelected()
    }

    @Test
    fun tappingAReasoningLevelSetsIt() {
        show(info(reasoning = "high"))
        openSheet()
        waitForText("XHigh")
        peer.results["config.set"] = """{"value":"low"}"""

        compose.onNodeWithText("Low").performClick()
        waitUntil { viewModel.chat.value.options?.reasoning == "low" }

        val set = configSets().single()
        assertEquals("reasoning", "key".param(set))
        assertEquals("low", "value".param(set))
        assertEquals("live1", "session_id".param(set))
        assertTrue("scope" !in set)
        compose.onNodeWithText("Low").assertIsSelected()
    }

    @Test
    fun offIsHiddenWhenTheModelCannotDisableReasoning() {
        show()
        openSheet()
        waitForText(SONNET.model)

        compose.onNodeWithText("Off").assertDoesNotExist()
        compose.onNodeWithText("High").assertExists()
    }

    @Test
    fun offIsOfferedWhenTheModelCanDisableReasoning() {
        show(info(model = SONNET.model))
        openSheet()
        waitForText(SONNET.model)
        waitForText("Off")
    }

    @Test
    fun reasoningHiddenForAModelWithoutIt() {
        show(info(model = "mini/mini"))
        openSheet()
        waitForText(FLASH.model)

        compose.onNodeWithText("Reasoning").assertDoesNotExist()
        compose.onNodeWithText("XHigh").assertDoesNotExist()
    }

    @Test
    fun fastShownOnlyWhenModelHasFast() {
        show()
        openSheet()
        waitForText("Fast mode")
        compose.onNodeWithText("Fast mode").assertIsOff()

        peer.event("session.info", info(model = SONNET.model))
        waitUntil { compose.onAllNodesWithText("Fast mode").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun fastSwitchSetsFast() {
        show()
        openSheet()
        waitForText("Fast mode")
        peer.results["config.set"] = """{"value":"fast"}"""

        compose.onNodeWithText("Fast mode").performClick()
        waitUntil { viewModel.chat.value.options?.fast == true }

        val set = configSets().single()
        assertEquals("fast", "key".param(set))
        assertEquals("fast", "value".param(set))
        compose.onNodeWithText("Fast mode").assertIsOn()
    }

    @Test
    fun yoloSwitch() {
        show()
        openSheet()
        waitForText("YOLO (skip approvals)")
        compose.onNodeWithText("YOLO (skip approvals)").assertIsOff()
        peer.results["config.set"] = """{"value":"1"}"""

        compose.onNodeWithText("YOLO (skip approvals)").performClick()
        waitUntil { viewModel.chat.value.options?.yolo == true }

        val set = configSets().single()
        assertEquals("yolo", "key".param(set))
        assertEquals("1", "value".param(set))
        assertEquals("live1", "session_id".param(set))
        assertTrue("scope" !in set)
        compose.onNodeWithText("YOLO (skip approvals)").assertIsOn()
        compose.onNodeWithText("YOLO is on for every session in Hermes' config").assertDoesNotExist()
    }

    @Test
    fun globalYoloIsExplained() {
        show(info(yolo = true))
        openSheet()
        waitForText("YOLO (skip approvals)")
        peer.results["config.set"] = """{"value":"0"}"""

        compose.onNodeWithText("YOLO (skip approvals)").performClick()
        waitUntil { configSets().size == 1 && viewModel.chat.value.options?.yolo == false }
        compose.onNodeWithText("YOLO is on for every session in Hermes' config").assertDoesNotExist()

        // Hermes reports YOLO on as long as its global approvals mode is off.
        peer.event("session.info", info(yolo = true))
        waitForText("YOLO is on for every session in Hermes' config")
        compose.onNodeWithText("YOLO (skip approvals)").assertIsOn()
    }

    @Test
    fun optionsEffectOpensSheet() {
        show()
        compose.onNodeWithTag("composer").performTextInput("/model")
        compose.onNodeWithContentDescription("Send").performClick()
        waitForSheet()
    }

    @Test
    fun failedChangeShowsMessage() {
        show()
        openSheet()
        waitForText(SONNET.model)
        peer.refusing = setOf("config.set")

        compose.onNodeWithText(SONNET.model).performClick()
        waitForText("Couldn't change the model: session not found")

        compose.onNodeWithText("Low").performClick()
        waitForText("Couldn't change reasoning: session not found")

        compose.onNodeWithText("Fast mode").performClick()
        waitForText("Couldn't change fast mode: session not found")

        compose.onNodeWithText("YOLO (skip approvals)").performClick()
        waitForText("Couldn't change YOLO: session not found")
    }

    @Test
    fun warningIsShown() {
        show()
        openSheet()
        waitForText(SONNET.model)
        peer.results["config.set"] = """{"value":"${SONNET.model}","warning":"big context","scope":"session"}"""

        compose.onNodeWithText(SONNET.model).performClick()

        waitForText("big context")
    }
}
