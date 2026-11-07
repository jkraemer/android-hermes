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
}
