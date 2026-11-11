package net.jkraemer.hermes.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val sent = mutableListOf<String>()

    private fun show() {
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
                    commands = null,
                    slashMenu = false,
                )
            }
        }
    }

    private fun fieldText() =
        compose.onNodeWithTag("composer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun theClearButtonShowsOnlyWithTextAndEmptiesTheFieldWithoutSending() {
        show()
        compose.onAllNodesWithContentDescription("Clear message").assertCountEquals(0)

        compose.onNodeWithTag("composer").performTextInput("half a thought")
        compose.onAllNodesWithContentDescription("Clear message").assertCountEquals(1)
        compose.onNodeWithContentDescription("Clear message").performClick()

        assertEquals("", fieldText())
        compose.onAllNodesWithContentDescription("Clear message").assertCountEquals(0)
        assertEquals(emptyList<String>(), sent)
    }
}
