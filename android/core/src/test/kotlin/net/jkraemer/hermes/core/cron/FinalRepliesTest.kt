package net.jkraemer.hermes.core.cron

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FinalRepliesTest {
    private val fetched = mutableListOf<String>()
    private var answer: String? = "Two new mails."
    private val replies = FinalReplies { id ->
        fetched += id
        answer
    }

    private fun run(id: String, running: Boolean = false) = CronRun(id, Instant.EPOCH, running, archived = false)

    @Test
    fun aFinishedRunsReplyIsFetchedOnce() = runBlocking {
        assertEquals("Two new mails.", replies.of(run("r1")))
        answer = "changed"
        assertEquals("Two new mails.", replies.of(run("r1")))
        assertEquals(listOf("r1"), fetched)
    }

    @Test
    fun aFinishedRunWithoutAReplyIsFetchedOnceToo() = runBlocking {
        answer = null
        assertNull(replies.of(run("r1")))
        assertNull(replies.of(run("r1")))
        assertEquals(listOf("r1"), fetched)
    }

    @Test
    fun aRunningRunIsAskedEveryTime() = runBlocking {
        replies.of(run("r1", running = true))
        replies.of(run("r1", running = true))
        replies.of(run("r1"))
        replies.of(run("r1"))
        assertEquals(listOf("r1", "r1", "r1"), fetched)
    }

    @Test
    fun aFailedFetchIsNotRemembered() = runBlocking {
        var fail = true
        val flaky = FinalReplies { if (fail) throw java.io.IOException("down") else "ok" }

        runCatching { flaky.of(run("r1")) }
        fail = false

        assertEquals("ok", flaky.of(run("r1")))
    }
}
