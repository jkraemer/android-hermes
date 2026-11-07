package net.jkraemer.hermes.sessions

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.settings.SeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class SessionListViewModelTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-vm-test", Context.MODE_PRIVATE)
    private val source = FakeSource()
    private val logged = CopyOnWriteArrayList<String>()
    private lateinit var seen: SeenStore

    private fun daysAgo(days: Long, at: Instant = NOW) = at.minus(Duration.ofDays(days))

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        seen = SeenStore(prefs) { daysAgo(30) }
    }

    private fun viewModel(now: Instant = NOW) =
        SessionListViewModel(source, MutableStateFlow(emptySet()), seen, Clock.fixed(now, ZoneOffset.UTC), log = { logged += it })

    /** Lets the view model's coroutines, which run on the main looper, catch up with the IO they wait for. */
    private fun settleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private val archived get() = source.updates.filter { it[3] == true }.map { it[0] }

    /** Job j1 seen up to nine days ago, with runs of every kind. */
    private fun jobWithOldRuns() {
        source.jobs = listOf(cronJob("j1"))
        source.runs = mapOf(
            "j1" to listOf(
                cronRun("recent-silent", daysAgo(1)),
                cronRun("old-unread-shown", daysAgo(8)),
                cronRun("old-unread-silent", daysAgo(8).minusSeconds(60)),
                cronRun("old-read", daysAgo(10)),
                cronRun("old-already-archived", daysAgo(11), archived = true),
            ),
        )
        source.replies = mapOf(
            "recent-silent" to "[SILENT]", "old-unread-shown" to "News", "old-unread-silent" to "[SILENT]",
            "old-read" to "Read news", "old-already-archived" to "[SILENT]",
        )
        seen.markJobSeen("j1", daysAgo(9))
    }

    @Test
    fun loadingTheListArchivesTheReadAndTheSilentRunsOlderThanAWeek() {
        jobWithOldRuns()
        source.sessions = listOf(session("old-chat", lastActive = daysAgo(20)))

        viewModel().refresh()

        settleUntil { archived.size == 2 }
        settle()
        assertEquals(listOf("old-unread-silent", "old-read"), archived)
        assertEquals(source.updates.size, archived.size)
    }

    @Test
    fun runsAreArchivedAtMostOnceADay() {
        jobWithOldRuns()
        viewModel().refresh()
        settleUntil { archived.size == 2 }

        source.updates.clear()
        viewModel(NOW.plus(Duration.ofHours(23))).refresh()
        settleUntil { source.runsCalls.size >= 2 }
        settle()
        assertEquals(emptyList<Any?>(), archived)

        viewModel(NOW.plus(Duration.ofHours(25))).refresh()
        settleUntil { archived.size == 2 }
    }

    @Test
    fun aRunThatCannotBeArchivedIsLoggedAndTheOthersStillAre() {
        jobWithOldRuns()
        source.unarchivable = setOf("old-unread-silent")

        viewModel().refresh()

        settleUntil { archived.isNotEmpty() && logged.isNotEmpty() }
        settle()
        assertEquals(listOf("old-read"), archived)
        assertTrue(logged.toString(), logged.single().startsWith("archiving cron run old-unread-silent failed"))
    }

    @Test
    fun aJobWhoseLastRunIsUnchangedIsNotLoadedAgain() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1", lastRunAt = daysAgo(1)))
        source.runs = mapOf("j1" to listOf(cronRun("r1", daysAgo(1))))
        source.replies = mapOf("r1" to "News")
        val model = viewModel()

        model.refresh()
        settleUntil { model.state.value.drawer.scheduled.isNotEmpty() }
        model.refreshQuietly()
        settle()
        assertEquals(listOf("j1"), source.runsCalls)

        source.jobs = listOf(cronJob("j1", lastRunAt = NOW))
        source.runs = mapOf("j1" to listOf(cronRun("r2", NOW.minusSeconds(60)), cronRun("r1", daysAgo(1))))
        source.replies = mapOf("r2" to "Newer news", "r1" to "News")
        model.refreshQuietly()
        settleUntil { model.state.value.drawer.scheduled.single().latestShownRun?.sessionId == "r2" }
        assertEquals(listOf("j1", "j1"), source.runsCalls)
    }

    @Test
    fun aJobsUnreadDotFollowsWhatWasSeenEvenWithoutANewRun() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1", lastRunAt = daysAgo(1)))
        source.runs = mapOf("j1" to listOf(cronRun("r1", daysAgo(1))))
        source.replies = mapOf("r1" to "News")
        val model = viewModel()
        model.refresh()
        settleUntil { model.state.value.drawer.scheduled.singleOrNull()?.unread == true }

        seen.markJobSeen("j1", daysAgo(1))
        model.refreshQuietly()

        settleUntil { model.state.value.drawer.scheduled.single().unread == false }
    }

    @Test
    fun cronJobsThatFailToLoadAreLoggedWithoutAnErrorBanner() {
        source.sessions = listOf(session("a"))
        val model = viewModel()
        model.refresh()
        settleUntil { model.state.value.items.isNotEmpty() }
        source.jobsFailure = java.io.IOException("timeout")

        model.refreshQuietly()

        settleUntil { logged.isNotEmpty() }
        assertEquals(listOf("cron jobs failed to load: java.io.IOException: timeout"), logged)
        assertEquals(null, model.state.value.error)
    }

    @Test
    fun loadingMoreGoesOnUntilAPageAddsRowsToTheList() {
        seen.markArchivePass(NOW)
        val listed = (1..50).map { session("s$it", lastActive = NOW.minusSeconds(it * 60L)) }
        // Chats opened but never written to are not listed: two whole pages of them add nothing.
        val empty = (1..100).map { session("e$it", lastActive = NOW.minusSeconds(4_000L + it * 60)).copy(messageCount = 0) }
        source.sessions = listed + empty + session("o1", lastActive = NOW.minusSeconds(20_000))
        source.total = source.sessions.size
        val model = viewModel()
        model.refresh()
        settleUntil { model.state.value.items.isNotEmpty() }

        model.loadMore()

        settleUntil { model.state.value.items.any { it is net.jkraemer.hermes.core.timeline.TimelineItem.Row && it.session.id == "o1" } }
        assertEquals(listOf(0, 50, 100, 150), source.listCalls)
    }

    @Test
    fun aJobsSummaryReadsOnlyItsTenNewestRunsWhenOneOfThemReported() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1"))
        source.runs = mapOf("j1" to (1..30).map { cronRun("r$it", NOW.minusSeconds(it * 3_600L)) })
        source.replies = mapOf("r1" to "[SILENT]", "r2" to "News")
        val model = viewModel()

        model.refreshQuietly()

        settleUntil { model.state.value.drawer.scheduled.isNotEmpty() }
        assertEquals("r2", model.state.value.drawer.scheduled.single().latestShownRun?.sessionId)
        assertEquals(listOf("j1" to 10), source.runsRequests)
    }

    @Test
    fun aJobWhoseTenNewestRunsWereSilentLooksFurtherBack() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1"))
        source.runs = mapOf("j1" to (1..30).map { cronRun("r$it", NOW.minusSeconds(it * 3_600L)) })
        source.replies = (1..10).associate { "r$it" to "[SILENT]" } + ("r11" to "News")
        val model = viewModel()

        model.refreshQuietly()

        settleUntil { model.state.value.drawer.scheduled.isNotEmpty() }
        assertEquals("r11", model.state.value.drawer.scheduled.single().latestShownRun?.sessionId)
        assertEquals(listOf("j1" to 10, "j1" to 100), source.runsRequests)
    }

    @Test
    fun aJobWithFewerThanTenRunsAndNoReportIsNotReadAgain() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1"))
        source.runs = mapOf("j1" to listOf(cronRun("r1", NOW.minusSeconds(3_600))))
        source.replies = mapOf("r1" to "[SILENT]")
        val model = viewModel()

        model.refreshQuietly()

        settleUntil { model.state.value.drawer.scheduled.isNotEmpty() }
        settle()
        assertEquals(listOf("j1" to 10), source.runsRequests)
    }

    @Test
    fun aJobThatFailsToLoadKeepsWhatItShowedAndTheOthersAreUpdated() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1", lastRunAt = daysAgo(1)), cronJob("j2", lastRunAt = daysAgo(1)))
        source.runs = mapOf("j1" to listOf(cronRun("a1", daysAgo(1))), "j2" to listOf(cronRun("b1", daysAgo(1))))
        source.replies = mapOf("a1" to "A news", "b1" to "B news")
        val model = viewModel()
        model.refreshQuietly()
        settleUntil { model.state.value.drawer.scheduled.size == 2 }

        // Both jobs ran again; j1's runs can no longer be read.
        source.jobs = listOf(cronJob("j1", lastRunAt = NOW), cronJob("j2", lastRunAt = NOW))
        source.runs = mapOf("j1" to listOf(cronRun("a2", NOW.minusSeconds(60))), "j2" to listOf(cronRun("b2", NOW.minusSeconds(60))))
        source.replies = mapOf("a2" to "Newer A", "b2" to "Newer B")
        source.failingRuns = setOf("j1")
        model.refreshQuietly()

        settleUntil { model.state.value.drawer.scheduled.any { it.latestShownRun?.sessionId == "b2" } }
        assertEquals(setOf("a1", "b2"), model.state.value.drawer.scheduled.map { it.latestShownRun?.sessionId }.toSet())
        assertTrue(logged.toString(), logged.any { it.startsWith("cron job j1 failed to load") })
    }

    @Test
    fun aJobThatFailsOnItsFirstLoadIsLeftOutWhileTheOthersShow() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1"), cronJob("j2"))
        source.runs = mapOf("j2" to listOf(cronRun("b1", daysAgo(1))))
        source.replies = mapOf("b1" to "B news")
        source.failingRuns = setOf("j1")
        val model = viewModel()

        model.refreshQuietly()

        settleUntil { model.state.value.drawer.scheduled.isNotEmpty() }
        assertEquals(listOf("j2"), model.state.value.drawer.scheduled.map { it.job.id })
    }

    @Test
    fun theOtherJobsRunsAreArchivedWhenOneJobFails() {
        jobWithOldRuns()
        source.jobs = listOf(cronJob("broken")) + source.jobs
        source.failingRuns = setOf("broken")

        viewModel().refresh()

        settleUntil { archived.size == 2 }
        assertEquals(listOf("old-unread-silent", "old-read"), archived)
    }

    @Test
    fun tenNewestRowsWithScriptOnlyOutputAmongThemStillCountAsFull() {
        seen.markArchivePass(NOW)
        source.jobs = listOf(cronJob("j1"))
        source.runs = mapOf("j1" to (1..30).map { cronRun("r$it", NOW.minusSeconds(it * 3_600L)) })
        source.scriptOnlyRows = mapOf("j1" to 3)
        source.replies = (1..7).associate { "r$it" to "[SILENT]" } + ("r8" to "News")
        val model = viewModel()

        model.refreshQuietly()

        settleUntil { model.state.value.drawer.scheduled.isNotEmpty() }
        assertEquals("r8", model.state.value.drawer.scheduled.single().latestShownRun?.sessionId)
        assertEquals(listOf("j1" to 10, "j1" to 100), source.runsRequests)
    }
}
