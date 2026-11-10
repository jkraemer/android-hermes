package net.jkraemer.hermes.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import net.jkraemer.hermes.MainActivity
import net.jkraemer.hermes.core.push.Channel
import net.jkraemer.hermes.core.push.PushNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = Notifier(context)

    private fun reply(key: String = "session:s1", sessionId: String? = "s1", text: String = "Done.") =
        PushNotification(key, Channel.REPLIES, "Hermes replied", text, sessionId)

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun createsTheThreeChannels() {
        notifier.createChannels()

        val channels = manager.notificationChannels.associate { it.id to (it.name.toString() to it.importance) }
        assertEquals(
            mapOf(
                "cron" to ("Scheduled jobs" to NotificationManager.IMPORTANCE_DEFAULT),
                "approvals" to ("Approvals and questions" to NotificationManager.IMPORTANCE_HIGH),
                "replies" to ("Replies" to NotificationManager.IMPORTANCE_DEFAULT),
            ),
            channels,
        )
    }

    @Test
    fun showsTheNotificationOnItsChannelWithBigText() {
        notifier.createChannels()

        notifier.show(PushNotification("request:a1", Channel.APPROVALS, "Approval needed", "rm -rf build", "s1"))

        val notification = posted().single()
        assertEquals("approvals", notification.channelId)
        assertEquals("Approval needed", notification.extras.getString("android.title"))
        assertEquals("rm -rf build", notification.extras.getCharSequence("android.bigText").toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun theSameKeyReplacesTheNotification() {
        notifier.createChannels()

        notifier.show(reply(text = "one"))
        notifier.show(reply(text = "two"))

        assertEquals("two", posted().single().extras.getCharSequence("android.bigText").toString())
    }

    @Test
    fun cancellingRemovesTheNotificationWithThatKey() {
        notifier.createChannels()
        notifier.show(reply("request:a1"))
        notifier.show(reply("request:a2"))

        notifier.cancel("request:a1")

        assertEquals(1, posted().size)
    }

    @Test
    fun tappingOpensTheSessionInMainActivityWithAnImmutablePendingIntent() {
        notifier.createChannels()

        notifier.show(reply())

        val tap = posted().single().contentIntent
        val shadow = shadowOf(tap)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(MainActivity::class.java.name, shadow.savedIntent.component!!.className)
        assertEquals("s1", shadow.savedIntent.getStringExtra("session_id"))
    }

    @Test
    fun tappingATestNotificationOpensNoSession() {
        notifier.createChannels()

        notifier.show(PushNotification("test", Channel.REPLIES, "Hermes", "Notifications work.", null))

        val intent: Intent = shadowOf(posted().single().contentIntent).savedIntent
        assertNull(intent.getStringExtra("session_id"))
        assertNotNull(intent.component)
    }

    @Test
    fun eachKeyGetsItsOwnPendingIntent() {
        notifier.createChannels()

        notifier.show(reply("session:s1", "s1"))
        notifier.show(reply("session:s2", "s2"))

        val tapped = posted().map { shadowOf(it.contentIntent).savedIntent.getStringExtra("session_id") }
        assertEquals(setOf("s1", "s2"), tapped.toSet())
        assertNotEquals(shadowOf(posted()[0].contentIntent).requestCode, shadowOf(posted()[1].contentIntent).requestCode)
    }

    @Test
    fun nothingIsPostedWhileNotificationsAreNotAllowed() {
        notifier.createChannels()
        shadowOf(manager).setNotificationsEnabled(false)

        notifier.show(reply())

        assertEquals(0, posted().size)
    }
}
