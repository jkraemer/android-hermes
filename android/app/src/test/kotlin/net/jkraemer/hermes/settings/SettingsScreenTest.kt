package net.jkraemer.hermes.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.jkraemer.hermes.push.PushStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var retries = 0
    private var testsSent = 0
    private var sendTest: suspend () -> Unit = { testsSent++ }

    private fun show(status: PushStatus) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    favoriteCount = 0, push = status, onBack = {}, onOpenFavoriteModels = {}, onRetryPush = { retries++ },
                    onNotificationPermissionResult = {}, onSendTest = { sendTest() },
                )
            }
        }
    }

    private fun supporting(status: PushStatus, text: String) {
        show(status)
        compose.onNodeWithText("Notifications").assertExists()
        compose.onNodeWithText(text).assertExists()
    }

    @Test fun onStatus() = supporting(PushStatus.On, "On")

    @Test fun registeringStatus() = supporting(PushStatus.Registering, "Setting up…")

    @Test
    fun tappingTheRegisteringRowDoesNothing() {
        show(PushStatus.Registering)

        compose.onNodeWithText("Notifications").performClick()

        assertEquals(0, retries)
    }

    @Test fun needsPermissionStatus() = supporting(PushStatus.NeedsPermission, "Allow notifications")

    @Test fun noDistributorStatus() = supporting(PushStatus.NoDistributor, "Install ntfy to get notifications")

    @Test fun pluginMissingStatus() = supporting(PushStatus.PluginMissing, "Install the hermes-app plugin on the server")

    @Test fun failedStatus() = supporting(PushStatus.Failed("NETWORK"), "Couldn't register: NETWORK")

    @Test
    fun tappingAFailedRowRetries() {
        show(PushStatus.Failed("NETWORK"))

        compose.onNodeWithText("Notifications").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun tappingTheNoDistributorRowRetries() {
        show(PushStatus.NoDistributor)

        compose.onNodeWithText("Notifications").performClick()

        assertEquals(1, retries)
    }

    private fun noTestRow(status: PushStatus) {
        show(status)
        compose.onNodeWithText("Send test notification").assertDoesNotExist()
    }

    @Test fun noTestRowWhileRegistering() = noTestRow(PushStatus.Registering)

    @Test fun noTestRowWhenNeedingPermission() = noTestRow(PushStatus.NeedsPermission)

    @Test fun noTestRowWithoutDistributor() = noTestRow(PushStatus.NoDistributor)

    @Test fun noTestRowWithoutPlugin() = noTestRow(PushStatus.PluginMissing)

    @Test fun noTestRowAfterAFailure() = noTestRow(PushStatus.Failed("x"))

    @Test
    fun theTestRowIsShownWhenOn() {
        show(PushStatus.On)

        compose.onNodeWithText("Send test notification").assertExists()
    }

    @Test
    fun theTestRowSendsATest() {
        show(PushStatus.On)

        compose.onNodeWithText("Send test notification").performClick()

        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); testsSent == 1 }
    }

    @Test
    fun aFailedTestShowsTheReason() {
        sendTest = { throw IllegalStateException("no subscription") }
        show(PushStatus.On)

        compose.onNodeWithText("Send test notification").performClick()

        compose.waitUntil(10_000) {
            ShadowLooper.idleMainLooper()
            compose.onAllNodes(hasText("Couldn't send: no subscription")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
