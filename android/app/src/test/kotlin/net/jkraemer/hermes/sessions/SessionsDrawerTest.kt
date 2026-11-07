package net.jkraemer.hermes.sessions

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import net.jkraemer.hermes.settings.SeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class SessionsDrawerTest {
    @get:Rule val compose = createComposeRule()

    private val source = FakeSource()
    private val searches = mutableListOf<Unit>()
    private val newChats = mutableListOf<Unit>()
    private val viewAlls = mutableListOf<Unit>()
    private val opened = mutableListOf<Pair<String, String?>>()
    private val openedCron = mutableListOf<Boolean>()
    private val openedJobs = mutableListOf<Pair<String, String>>()

    private fun show(sessions: List<net.jkraemer.hermes.core.sessions.SessionSummary>) {
        source.sessions = sessions
        source.total = sessions.size
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-test", Context.MODE_PRIVATE)
        val viewModel = SessionListViewModel(
            source, MutableStateFlow(emptySet()), SeenStore(prefs) { NOW.minusSeconds(86_400) }, Clock.fixed(NOW, ZoneOffset.UTC),
        )
        compose.setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                SessionsDrawerContent(
                    state.drawer,
                    onSearch = { searches += Unit },
                    onNewChat = { newChats += Unit },
                    onOpenChat = { id, title, cron ->
                        opened += id to title
                        openedCron += cron
                    },
                    onOpenJob = { id, name -> openedJobs += id to name },
                    onViewAll = { viewAlls += Unit },
                )
            }
        }
        viewModel.refresh()
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun headerHasTheTitleASearchIconAndAPlusIcon() {
        show(listOf(session("a")))
        waitForText("a")
        compose.onNodeWithText("Hermes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        assertEquals(1, searches.size)
        assertEquals(1, newChats.size)
    }

    @Test
    fun showsAllPinnedSessionsAndTheTenNewestOthersThenViewAll() {
        val pins = (1..12).map { session("pin$it", pinned = true, lastActive = NOW.minusSeconds(it * 60L)) }
        val others = (1..14).map { session("s$it", lastActive = NOW.minusSeconds(3_600L + it * 60)) }
        show(pins + others)
        waitForText("pin1")

        compose.onNodeWithText("Pinned").assertIsDisplayed()
        compose.onNodeWithText("Recents").assertIsDisplayed()
        (1..12).forEach { compose.onNodeWithText("pin$it").assertIsDisplayed() }
        (1..10).forEach { compose.onNodeWithText("s$it").assertIsDisplayed() }
        (11..14).forEach { compose.onAllNodes(hasText("s$it")).assertCountEquals(0) }

        compose.onNodeWithText("View all").performClick()
        assertEquals(1, viewAlls.size)
    }

    @Test
    fun withoutPinnedSessionsThereIsNoPinnedSection() {
        show(listOf(session("a")))
        waitForText("a")
        compose.onAllNodes(hasText("Pinned")).assertCountEquals(0)
    }

    @Test
    fun withoutAnySessionsNothingButTheHeaderIsShown() {
        show(emptyList())
        compose.waitForIdle()
        compose.onNodeWithText("Hermes").assertIsDisplayed()
        compose.onAllNodes(hasText("Pinned")).assertCountEquals(0)
        compose.onAllNodes(hasText("Recents")).assertCountEquals(0)
    }

    /** Jobs called after their ids, each with one run [minutesAgo] that reported something, or nothing when null. */
    private fun jobs(vararg jobs: Pair<String, Long?>) {
        source.jobs = jobs.map { cronJob(it.first) }
        source.runs = jobs.associate { (id, minutesAgo) -> id to listOfNotNull(minutesAgo?.let { cronRun("$id-run", NOW.minusSeconds(it * 60)) }) }
        source.replies = jobs.associate { "${it.first}-run" to "Report" }
    }

    @Test
    fun entriesShowOnlyTheTitleAKindIconAndTheUnreadDot() {
        jobs("Nightly" to 2L)
        show(listOf(session("a", title = "Chat title", preview = "a preview", lastActive = NOW.minusSeconds(60))))
        waitForText("Chat title")
        waitForText("Nightly")

        compose.onAllNodes(hasText("a preview")).assertCountEquals(0)
        compose.onAllNodes(hasText("minute", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Chat")).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Cron job")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("unread-dot"), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun scheduledJobsComeFirstNewestReportFirstAndSilentOnesLast() {
        jobs("Quiet" to null, "Nightly" to 120L, "Morning" to 5L)
        show(listOf(session("a", title = "Chat title")))
        waitForText("Quiet")

        val top = { text: String -> compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Hermes") < top("Scheduled"))
        assertTrue(top("Scheduled") < top("Morning"))
        assertTrue(top("Morning") < top("Nightly"))
        assertTrue(top("Nightly") < top("Quiet"))
        assertTrue(top("Quiet") < top("Recents"))
    }

    @Test
    fun withoutJobsThereIsNoScheduledSection() {
        show(listOf(session("a")))
        waitForText("a")
        compose.onAllNodes(hasText("Scheduled")).assertCountEquals(0)
    }

    @Test
    fun cronRunsAreNotAmongTheRecents() {
        show(listOf(session("a", title = "Chat title"), session("b", title = "Nightly run", source = "cron", lastActive = NOW.minusSeconds(30))))
        waitForText("Chat title")
        compose.onAllNodes(hasText("Nightly run")).assertCountEquals(0)
    }

    @Test
    fun tappingAnEntryOpensItsChat() {
        show(listOf(session("a", title = "Chat title")))
        waitForText("Chat title")
        compose.onNodeWithText("Chat title").performClick()
        assertEquals(listOf<Pair<String, String?>>("a" to "Chat title"), opened)
    }

    @Test
    fun tappingAJobOpensTheJob() {
        jobs("Nightly" to 2L)
        show(listOf(session("a", title = "Chat title")))
        waitForText("Nightly")
        compose.onNodeWithText("Nightly").performClick()
        assertEquals(listOf("Nightly" to "Nightly"), openedJobs)
        assertEquals(emptyList<Pair<String, String?>>(), opened)
    }

    @Test
    fun tappingAPinnedCronRunOpensItAsACronChat() {
        show(listOf(session("a", title = "Chat title"), session("b", title = "Nightly run", source = "cron", pinned = true)))
        waitForText("Nightly run")
        compose.onNodeWithText("Nightly run").performClick()
        compose.onNodeWithText("Chat title").performClick()
        assertEquals(listOf(true, false), openedCron)
    }
}
