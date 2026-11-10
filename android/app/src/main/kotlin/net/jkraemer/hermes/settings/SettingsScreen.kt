package net.jkraemer.hermes.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.jkraemer.hermes.push.PushStatus

/**
 * The app's settings; [favoriteCount] is how many favorite models are set and [push] whether notifications work.
 * Tapping the notifications row asks for the permission when that is missing ([onNotificationPermissionResult] follows
 * the answer), else runs [onRetryPush]. [onSendTest] throws when the test notification could not be requested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    favoriteCount: Int,
    push: PushStatus,
    onBack: () -> Unit,
    onOpenFavoriteModels: () -> Unit,
    onRetryPush: () -> Unit,
    onNotificationPermissionResult: () -> Unit,
    onSendTest: suspend () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNotificationPermissionResult() }
    Scaffold(
        modifier = Modifier.testTag("settings-screen"),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text("Favorite models") },
                supportingContent = { Text(if (favoriteCount == 0) "None" else favoriteCount.toString()) },
                modifier = Modifier.clickable(onClick = onOpenFavoriteModels),
            )
            ListItem(
                headlineContent = { Text("Notifications") },
                supportingContent = { Text(push.describe()) },
                modifier = Modifier.clickable {
                    when (push) {
                        PushStatus.NeedsPermission -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        PushStatus.Registering -> Unit
                        else -> onRetryPush()
                    }
                },
            )
            if (push == PushStatus.On) {
                ListItem(
                    headlineContent = { Text("Send test notification") },
                    modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                onSendTest()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Couldn't send: ${e.message ?: e::class.java.simpleName}")
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun PushStatus.describe() = when (this) {
    PushStatus.Registering -> "Setting up…"
    PushStatus.On -> "On"
    PushStatus.NeedsPermission -> "Allow notifications"
    PushStatus.NoDistributor -> "Install ntfy to get notifications"
    PushStatus.PluginMissing -> "Install the hermes-app plugin on the server"
    is PushStatus.Failed -> "Couldn't register: $message"
}
