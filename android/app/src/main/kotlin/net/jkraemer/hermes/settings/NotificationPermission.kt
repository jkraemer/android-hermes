package net.jkraemer.hermes.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

/** Ways to get the user to allow notifications; the system's answer to a request is passed on to `onResult`. */
class NotificationPermissionRequest internal constructor(private val activity: ComponentActivity) {
    internal lateinit var launcher: ActivityResultLauncher<String>
    internal var onResult: () -> Unit = {}
    private var openSettingsIfBlocked = false

    /** Shows the system's permission dialog, if the system still shows one, and leaves it at that. */
    fun ask() {
        openSettingsIfBlocked = false
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * For the user's own tap on "Allow notifications": when no dialog can appear any more (the permission was denied
     * for good, or notifications are switched off for the app although it is granted), the app's notification settings
     * open instead, as the tap would otherwise do nothing.
     */
    fun allow() {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            openNotificationSettings()
        } else {
            openSettingsIfBlocked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    internal fun answered(granted: Boolean) {
        onResult()
        // A denial the system gave without a dialog to ask again cannot be undone in the app.
        val blocked = !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        if (blocked && openSettingsIfBlocked) openNotificationSettings()
    }

    private fun openNotificationSettings() {
        activity.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
        )
    }
}

@Composable
fun rememberNotificationPermissionRequest(onResult: () -> Unit): NotificationPermissionRequest {
    val activity = LocalContext.current as ComponentActivity
    val request = remember(activity) { NotificationPermissionRequest(activity) }
    request.onResult = onResult
    request.launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), request::answered)
    return request
}
