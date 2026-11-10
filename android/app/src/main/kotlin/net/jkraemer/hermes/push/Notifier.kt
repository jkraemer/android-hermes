package net.jkraemer.hermes.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import net.jkraemer.hermes.MainActivity
import net.jkraemer.hermes.core.push.Channel
import net.jkraemer.hermes.core.push.PushNotification

/** Where [PushHandler] puts the notifications; the system's notification manager cannot run in plain unit tests. */
interface Notifications {
    fun show(notification: PushNotification)

    fun cancel(key: String)
}

/** Shows a push as a system notification that opens its session in [MainActivity] when tapped. */
class Notifier(private val context: Context) : Notifications {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Creates the channels; doing so again changes nothing, and the user's settings for them stay. */
    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(Channel.CRON.id, "Scheduled jobs", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(Channel.APPROVALS.id, "Approvals and questions", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(Channel.REPLIES.id, "Replies", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    override fun show(notification: PushNotification) {
        if (!manager.areNotificationsEnabled()) return
        val built = Notification.Builder(context, notification.channel.id)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notification.title)
            .setContentText(notification.text)
            .setStyle(Notification.BigTextStyle().bigText(notification.text))
            .setContentIntent(opening(notification))
            .setAutoCancel(true)
            .build()
        manager.notify(idOf(notification.key), built)
    }

    override fun cancel(key: String) = manager.cancel(idOf(key))

    // Single top and clear top deliver the tap to the running activity (onNewIntent) instead of stacking a second one.
    private fun opening(notification: PushNotification): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { notification.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) } }
        return PendingIntent.getActivity(context, idOf(notification.key), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // A string's hash is the same in every process, so a notification can be found again to replace or cancel it.
    private fun idOf(key: String) = key.hashCode()

    companion object {
        /** The stored id of the session a tapped notification opens. */
        const val EXTRA_SESSION_ID = "session_id"
    }
}

private val Channel.id: String
    get() = when (this) {
        Channel.CRON -> "cron"
        Channel.APPROVALS -> "approvals"
        Channel.REPLIES -> "replies"
    }
