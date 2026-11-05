package net.jkraemer.hermes.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.jkraemer.hermes.core.chat.ApprovalChoice
import net.jkraemer.hermes.core.chat.CardStatus
import net.jkraemer.hermes.core.chat.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

private val NOW = Instant.parse("2026-11-05T12:00:00Z")

private fun approval(
    choices: List<ApprovalChoice> = ApprovalChoice.entries,
    status: CardStatus = CardStatus.Open,
    deadline: Instant = NOW.plusSeconds(252),
) = ChatItem.Approval("srq-1", "r1", "rm -rf build", "Removes the build directory", choices, deadline, status)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApprovalCardTest {
    @get:Rule val compose = createComposeRule()

    private val chosen = mutableListOf<ApprovalChoice>()

    private fun show(item: ChatItem.Approval) {
        compose.setContent {
            MaterialTheme { ApprovalCard(item, clock = { NOW }, onChoose = { chosen += it }) }
        }
    }

    @Test
    fun showsOnlyOfferedChoices() {
        show(approval(choices = listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY)))

        compose.onNodeWithText("Approval needed").assertIsDisplayed()
        compose.onNodeWithText("rm -rf build").assertIsDisplayed()
        compose.onNodeWithText("Removes the build directory").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.onNodeWithText("Deny").assertIsDisplayed()
        compose.onNodeWithText("This session").assertDoesNotExist()
        compose.onNodeWithText("Always").assertDoesNotExist()

        compose.onNodeWithText("Deny").performClick()
        assertEquals(listOf(ApprovalChoice.DENY), chosen)
    }

    @Test
    fun countdownShowsRemainingTime() {
        show(approval())
        compose.onNodeWithText("auto-deny in 4:12").assertIsDisplayed()
    }

    @Test
    fun answeredStateHidesButtons() {
        show(approval(status = CardStatus.Answered("Allowed for this session")))

        compose.onNodeWithText("Allowed for this session").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertDoesNotExist()
        compose.onNodeWithText("Deny").assertDoesNotExist()
        compose.onNodeWithText("auto-deny in 4:12").assertDoesNotExist()
    }

    @Test
    fun expiredStateShowsExpired() {
        show(approval(status = CardStatus.Expired))

        compose.onNodeWithText("Expired").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertDoesNotExist()
    }

    @Test
    fun cancelledStateShowsReason() {
        show(approval(status = CardStatus.Cancelled("interrupted")))
        compose.onNodeWithText("Cancelled: interrupted").assertIsDisplayed()
    }
}
