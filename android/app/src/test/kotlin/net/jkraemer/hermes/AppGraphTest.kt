package net.jkraemer.hermes

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
}
