package net.jkraemer.hermes.cron

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import net.jkraemer.hermes.core.push.NotificationRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A dialog with a text field keeps Compose from ever going idle under Robolectric on a tall screen, so these tests
// use the default one.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationRuleDialogTest {
    @get:Rule val compose = createComposeRule()

    private val saved = mutableListOf<NotificationRule>()
    private var dismissed = 0

    private fun show(rule: NotificationRule) = compose.setContent {
        MaterialTheme { NotificationRuleDialog("Attention Check", rule, onDismiss = { dismissed++ }, onSave = { saved += it }) }
    }

    @Test
    fun showsTheJobNameAndTheOptions() {
        show(NotificationRule.Always)

        compose.onNodeWithText("Notifications for Attention Check").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("Every report").assertIsDisplayed()
        compose.onNodeWithText("Only when the report matches").assertIsDisplayed()
    }

    @Test
    fun theCurrentRuleIsSelected() {
        show(NotificationRule.Off)

        compose.onNodeWithText("Off").assertIsSelected()
    }

    @Test
    fun theExpressionFieldIsOnlyForTheMatchOption() {
        show(NotificationRule.Always)
        compose.onNodeWithText("Regular expression").assertDoesNotExist()

        compose.onNodeWithText("Only when the report matches").performClick()
        compose.onNodeWithText("Regular expression").assertIsDisplayed()
    }

    @Test
    fun aMatchRuleStartsWithItsStoredExpression() {
        show(NotificationRule.Match("ALERT"))

        compose.onNodeWithText("Only when the report matches").assertIsSelected()
        compose.onNodeWithTag("rule-field").assertTextEquals("Regular expression", "ALERT")
    }

    @Test
    fun saveReportsTheChosenRule() {
        show(NotificationRule.Always)
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf<NotificationRule>(NotificationRule.Off), saved)
    }

    @Test
    fun saveReportsAnEnteredExpression() {
        show(NotificationRule.Always)
        compose.onNodeWithText("Only when the report matches").performClick()
        compose.onNodeWithTag("rule-field").performTextInput("ALERT|FAIL")
        compose.onNodeWithText("Save").performClick()

        assertEquals(listOf<NotificationRule>(NotificationRule.Match("ALERT|FAIL")), saved)
    }

    @Test
    fun anInvalidExpressionShowsAnErrorAndBlocksSaveUntilItIsFixedOrAnotherOptionChosen() {
        show(NotificationRule.Match("ok"))
        compose.onNodeWithTag("rule-field").performTextClearance()
        compose.onNodeWithTag("rule-field").performTextInput("(")

        compose.onNodeWithText("Not a valid regular expression").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        compose.onNodeWithTag("rule-field").performTextClearance()
        compose.onNodeWithTag("rule-field").performTextInput("fine")
        compose.onNodeWithText("Not a valid regular expression").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsEnabled()

        compose.onNodeWithTag("rule-field").performTextInput(")")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun cancelSavesNothing() {
        show(NotificationRule.Always)
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<NotificationRule>(), saved)
        assertEquals(1, dismissed)
    }
}
