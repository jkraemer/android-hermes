package net.jkraemer.hermes.core.cron

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CronJobsTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val job = CronJob("j1", "Attention Check", lastRunAt = null)

    private fun run(id: String, hoursAgo: Long, running: Boolean = false, archived: Boolean = false) =
        CronRun(id, now.minus(Duration.ofHours(hoursAgo)), running, archived)

    /** Replies by session id; a run missing from it has none. Records which runs were asked for. */
    private class Replies(private val replies: Map<String, String>) {
        val asked = mutableListOf<String>()
        suspend fun of(run: CronRun): String? {
            asked += run.sessionId
            return replies[run.sessionId]
        }
    }

    @Test
    fun theJobsTimeIsItsNewestRunThatReportedSomething() = runBlocking {
        val runs = listOf(run("r4", 1, running = true), run("r3", 2), run("r2", 4), run("r1", 6))
        val replies = Replies(mapOf("r3" to "[SILENT]", "r2" to "Two new mails.", "r1" to "Older news"))

        val summary = summarizeJob(job, runs, seenUntil = now.minus(Duration.ofDays(1)), replies::of)

        assertEquals(ShownRun("r2", runs[2].startedAt, "Two new mails."), summary.latestShownRun)
        assertEquals(runs[2].startedAt, summary.time)
        // A running run has no reply yet, and nothing older than the first shown run is needed.
        assertEquals(listOf("r3", "r2"), replies.asked)
    }

    @Test
    fun archivedRunsAndRunsWithoutAReplyAreNotShown() = runBlocking {
        val runs = listOf(run("r3", 1, archived = true), run("r2", 2), run("r1", 3))
        val replies = Replies(mapOf("r3" to "Archived news", "r1" to "Shown"))

        val summary = summarizeJob(job, runs, seenUntil = now, replies::of)

        assertEquals("r1", summary.latestShownRun?.sessionId)
        assertEquals(listOf(runs[1], runs[2]), summary.runs)
    }

    @Test
    fun aJobWhoseRunsWereAllSilentHasNoTimeAndIsNotUnread() = runBlocking {
        val summary = summarizeJob(job, listOf(run("r2", 1), run("r1", 2)), seenUntil = now.minus(Duration.ofDays(1)), Replies(mapOf("r2" to "[SILENT]", "r1" to "NO_REPLY"))::of)

        assertNull(summary.latestShownRun)
        assertNull(summary.time)
        assertFalse(summary.unread)
    }

    @Test
    fun aJobIsUnreadWhenItsNewestShownRunIsAfterItWasSeen() = runBlocking {
        val runs = listOf(run("r2", 1), run("r1", 5))
        val replies = Replies(mapOf("r2" to "New", "r1" to "Old"))

        assertTrue(summarizeJob(job, runs, seenUntil = now.minus(Duration.ofHours(2)), replies::of).unread)
        assertFalse(summarizeJob(job, runs, seenUntil = runs[0].startedAt, replies::of).unread)
    }

    @Test
    fun seeingTheJobLaterUpdatesWhetherItIsUnread() = runBlocking {
        val runs = listOf(run("r1", 1))
        val summary = summarizeJob(job, runs, seenUntil = now.minus(Duration.ofHours(2)), Replies(mapOf("r1" to "New"))::of)

        assertFalse(summary.seenUpTo(runs[0].startedAt).unread)
        assertTrue(summary.seenUpTo(runs[0].startedAt).seenUpTo(now.minus(Duration.ofHours(3))).unread)
    }

    @Test
    fun aSilentRunAfterTheSeenTimeDoesNotMakeTheJobUnread() = runBlocking {
        val runs = listOf(run("r2", 1), run("r1", 5))

        val summary = summarizeJob(job, runs, seenUntil = now.minus(Duration.ofHours(2)), Replies(mapOf("r2" to "[SILENT]", "r1" to "Old"))::of)

        assertFalse(summary.unread)
    }

    @Test
    fun theFeedIsTheShownRunsOldestFirstOfThoseWhoseReplyArrived() {
        val runs = listOf(run("r5", 1, running = true), run("r4", 2), run("r3", 3, archived = true), run("r2", 4), run("r1", 5), run("r0", 6))
        val replies = mapOf("r4" to "Newest", "r3" to "Archived", "r2" to "[SILENT]", "r1" to "Oldest", "r0" to null)

        assertEquals(
            listOf(ShownRun("r1", runs[4].startedAt, "Oldest"), ShownRun("r4", runs[1].startedAt, "Newest")),
            feedEntries(runs, replies),
        )
    }

    @Test
    fun readRunsAndSilentRunsOlderThanAWeekAreArchived() = runBlocking {
        val seenUntil = now.minus(Duration.ofDays(9))
        val runs = listOf(
            run("recent-silent", 24),
            run("old-unread-shown", 24 * 8),
            run("old-unread-silent", 24 * 8 + 1),
            run("old-read", 24 * 10),
            run("old-archived", 24 * 11, archived = true),
            run("old-running", 24 * 12, running = true),
        )
        val replies = Replies(mapOf("recent-silent" to "[SILENT]", "old-unread-shown" to "News", "old-unread-silent" to "[SILENT]", "old-read" to "Read news"))

        val archive = runsToArchive(runs, seenUntil, now, replies::of)

        assertEquals(listOf("old-unread-silent", "old-read"), archive.map { it.sessionId })
        // A read run is archived whatever it said.
        assertFalse("old-read" in replies.asked)
    }

    @Test
    fun aRunExactlyAWeekOldStays() = runBlocking {
        val runs = listOf(CronRun("r1", now.minus(Duration.ofDays(7)), running = false, archived = false))

        assertEquals(emptyList<CronRun>(), runsToArchive(runs, seenUntil = now, now, Replies(emptyMap())::of))
    }
}
