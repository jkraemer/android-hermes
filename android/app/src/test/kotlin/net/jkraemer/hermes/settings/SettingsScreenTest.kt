package net.jkraemer.hermes.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.jkraemer.hermes.push.PushStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var retries = 0
    private var testsSent = 0
    private var permissionResults = 0
    private var sendTest: suspend () -> Unit = { testsSent++ }

    private fun show(status: PushStatus) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    favoriteCount = 0, push = status, onBack = {}, onOpenFavoriteModels = {}, onRetryPush = { retries++ },
                    onNotificationPermissionResult = { permissionResults++ }, onSendTest = { sendTest() },
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

    private val app = RuntimeEnvironment.getApplication()

    private fun tapAllowNotifications() {
        show(PushStatus.NeedsPermission)
        compose.onNodeWithText("Notifications").performClick()
    }

    // The deprecated callback is what the framework calls with the user's answer; the registry takes it from there.
    @Suppress("DEPRECATION")
    private fun answerPermissionRequest(grant: Int, rationale: Boolean) {
        val activityShadow = shadowOf(compose.activity)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, rationale)
        val requested = activityShadow.lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), requested.requestedPermissions.toList())
        compose.activity.onRequestPermissionsResult(requested.requestCode, requested.requestedPermissions, intArrayOf(grant))
        ShadowLooper.idleMainLooper()
    }

    // The permission request itself is an activity too, so only the notification settings count.
    private fun startedSettings() = generateSequence { shadowOf(app).nextStartedActivity }
        .filter { it.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS }.toList()

    @Test
    fun tappingAllowNotificationsAsksForThePermission() {
        tapAllowNotifications()

        answerPermissionRequest(PackageManager.PERMISSION_GRANTED, rationale = false)

        assertEquals(1, permissionResults)
        assertEquals(emptyList<Intent>(), startedSettings())
    }

    @Test
    fun aFirstDenialOnlyReportsTheResult() {
        tapAllowNotifications()

        answerPermissionRequest(PackageManager.PERMISSION_DENIED, rationale = true)

        assertEquals(1, permissionResults)
        assertEquals(emptyList<Intent>(), startedSettings())
    }

    @Test
    fun aDenialThatCannotBeAskedAgainOpensTheAppsNotificationSettings() {
        tapAllowNotifications()

        answerPermissionRequest(PackageManager.PERMISSION_DENIED, rationale = false)

        val intent = startedSettings().single()
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(1, permissionResults)
    }

    @Test
    fun notificationsSwitchedOffDespiteTheGrantedPermissionOpenTheSettingsDirectly() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)

        tapAllowNotifications()

        assertEquals(1, startedSettings().size)
        assertNull(shadowOf(compose.activity).lastRequestedPermission)
    }
}
