package net.jkraemer.hermes

import android.app.NotificationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jkraemer.hermes.core.push.NotificationRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AppGraphTest {
    private val graph get() = (RuntimeEnvironment.getApplication() as HermesApp).graph

    @After fun close() = graph.close()

    private fun awaitNeedsAnswer(expected: Set<String>) = runBlocking { withTimeout(10_000) { graph.needsAnswer.first { it == expected } } }

    @Test
    fun needsAnswerIsTheSessionsOfTheChatsAndOfThePushes() {
        graph.chatsNeedingAnswer.value = setOf("chat")
        graph.pushedNeedsAnswer.add("pushed", "r1", java.time.Instant.now().plusSeconds(300))

        awaitNeedsAnswer(setOf("chat", "pushed"))
    }

    @Test
    fun needsAnswerFollowsPushes() {
        val approval = """{"v":1,"type":"approval","session_id":"s1","request_id":"a1","text":"rm x"}"""
        val resolved = """{"v":1,"type":"resolved","session_id":"s1","request_id":"a1"}"""

        graph.pushHandler.onMessage(approval.toByteArray(), decrypted = true)
        awaitNeedsAnswer(setOf("s1"))
        graph.pushHandler.onMessage(resolved.toByteArray(), decrypted = true)
        awaitNeedsAnswer(emptySet())
    }

    @Test
    fun aCronPushFollowsTheRuleSetForItsJob() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        val cron = """{"v":1,"type":"cron","session_id":"s9","job_id":"j1","job_name":"Nightly","text":"all fine"}""".toByteArray()

        // Pushes arriving on the main thread are handled in the graph's scope, so the Off push is followed by an Always
        // one for another job, and the Off one is given time to show if the rule were ignored.
        val other = String(cron).replace("j1", "j2").replace("s9", "s10").toByteArray()
        graph.notificationRules.set("j1", NotificationRule.Off)
        graph.pushHandler.onMessage(cron, decrypted = true)
        graph.pushHandler.onMessage(other, decrypted = true)
        runBlocking { withTimeout(10_000) { while (shadowOf(manager).allNotifications.isEmpty()) delay(10) } }
        runBlocking { delay(300) }
        assertEquals(listOf("s10"), shadowOf(manager).allNotifications.map { shadowOf(it.contentIntent).savedIntent.getStringExtra("session_id") })
    }
}
