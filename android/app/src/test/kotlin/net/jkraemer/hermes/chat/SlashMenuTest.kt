package net.jkraemer.hermes.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.jkraemer.hermes.core.commands.commandCatalogFrom
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SlashMenuTest {
    @get:Rule val compose = createComposeRule()

    private val sent = mutableListOf<String>()
    private var menusOpened = 0

    private fun show() {
        val catalog = commandCatalogFrom(Json.parseToJsonElement(COMMANDS_CATALOG).jsonObject)
        compose.setContent {
            MaterialTheme {
                Composer(
                    running = false,
                    canSend = true,
                    onSend = { sent += it },
                    onStop = {},
                    keyboardVisible = false,
                    prefill = null,
                    onPrefillShown = {},
                    commands = catalog,
                    onMenuOpened = { menusOpened++ },
                )
            }
        }
    }

    private val title = "Set a title (usage: /title [name])"

    @Test
    fun slashAtStartOpensMenuFiltered() {
        show()

        compose.onNodeWithTag("composer").performTextInput("/ti")
        compose.onNodeWithText("/title").assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("/compress").assertDoesNotExist()

        compose.onNodeWithTag("composer").performTextReplacement("/title ")
        compose.onNodeWithText(title).assertDoesNotExist()

        compose.onNodeWithTag("composer").performTextReplacement("x /")
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText("/compress").assertDoesNotExist()
    }

    @Test
    fun tapInsertsCommand() {
        show()
        compose.onNodeWithTag("composer").performTextInput("/")

        compose.onNodeWithText("/compress").performClick()

        compose.onNodeWithTag("composer").assertEditableText("/compress ")
        assertEquals(TextRange("/compress ".length), compose.onNodeWithTag("composer").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithText("Compress the context").assertDoesNotExist()
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun slashButtonOpensMenu() {
        show()
        compose.onNodeWithTag("composer").requestFocus()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Commands").performClick()

        compose.onNodeWithTag("composer").assertEditableText("/")
        compose.onNodeWithText("/compress").assertIsDisplayed()
    }

    @Test
    fun groupsHaveHeadings() {
        show()
        compose.onNodeWithTag("composer").performTextInput("/")

        compose.onNodeWithText("Session").assertIsDisplayed()
        // The menu scrolls; the skills' heading comes after the session commands.
        compose.onNodeWithTag("slash-menu").performScrollToNode(hasText("Skills"))
        compose.onNodeWithText("Skills").assertIsDisplayed()
        compose.onNodeWithText("/arxiv").assertIsDisplayed()
    }

    @Test
    fun openingTheMenuAsksForTheCatalogAgain() {
        show()
        compose.onNodeWithTag("composer").performTextInput("/")
        compose.waitForIdle()

        assertEquals(1, menusOpened)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertEditableText(expected: String) {
        assertEquals(expected, fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}
