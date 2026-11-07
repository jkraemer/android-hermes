package net.jkraemer.hermes.cron

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import net.jkraemer.hermes.sessions.FakeSource
import net.jkraemer.hermes.sessions.cronRun
import net.jkraemer.hermes.settings.SeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

private val NOW = ZonedDateTime.of(2026, 11, 7, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()

private fun at(day: Int, hour: Int, minute: Int): Instant = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class JobFeedScreenTest {
    @get:Rule val compose = createComposeRule()

    private val source = FakeSource()
    private val openedRuns = mutableListOf<String>()
    private val replies = mutableListOf<Pair<String, String>>()
    private var menus = 0
    private lateinit var seen: SeenStore
    private lateinit var viewModel: JobFeedViewModel

    /** Job j1 with runs today 10:03, today 09:00 (silent), yesterday 08:05 and Nov 3 07:21, and one still running. */
    private fun jobWithRuns() {
        source.runs = mapOf(
            "j1" to listOf(
                net.jkraemer.hermes.core.cron.CronRun("running", at(7, 11, 0), running = true, archived = false),
                cronRun("r4", at(7, 10, 3)),
                cronRun("r3", at(7, 9, 0)),
                cronRun("r2", at(6, 8, 5)),
                cronRun("r1", at(3, 7, 21)),
            ),
        )
        source.replies = mapOf(
            "r4" to "Two **new** mails.", "r3" to "[SILENT]", "r2" to "Backups done.", "r1" to "The oldest report.",
        )
    }

    /** Whether the feed is on screen; taking it off and back is what a rotation does to its composition. */
    private val onScreen = mutableStateOf(true)

    /** [restoration], when given, sets the content so that a test can emulate the screen being recreated. */
    private fun show(restoration: StateRestorationTester? = null) {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-test", Context.MODE_PRIVATE)
        seen = SeenStore(prefs) { NOW.minusSeconds(30 * 86_400L) }
        viewModel = JobFeedViewModel(source, "j1", seen)
        val setContent: (@androidx.compose.runtime.Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: compose::setContent
        setContent {
            if (onScreen.value) MaterialTheme {
                JobFeedScreen(
                    viewModel,
                    name = "Attention Check",
                    onOpenMenu = { menus++ },
                    onOpenRun = { openedRuns += it },
                    onReply = { run, text -> replies += run to text },
                    clock = Clock.fixed(NOW, ZoneOffset.UTC),
                    keyboardVisible = false,
                )
            }
        }
    }

    /** Replies are parsed off the main thread; each poll also runs what is queued on the main looper. */
    private fun waitForEntries(count: Int) = compose.waitUntil(10_000) {
        ShadowLooper.idleMainLooper()
        viewModel.state.value.entries.size == count && viewModel.parsedReplies.value.size == count
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        ShadowLooper.idleMainLooper()
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun top(matcher: SemanticsMatcher) = compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun theTopBarHasTheMenuAndTheJobsNameButNoNewChat() {
        jobWithRuns()
        show()
        compose.onNodeWithText("Attention Check").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").performClick()
        assertEquals(1, menus)
        compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("New chat"))).assertCountEquals(0)
    }

    @Test
    fun eachReportedRunShowsItsTimeAboveItsReplyOldestAtTheTop() {
        jobWithRuns()
        show()
        waitForEntries(3)
        compose.waitForIdle()

        val oldest = top(hasText("The oldest report."))
        val middle = top(hasText("Backups done."))
        val newest = top(hasText("Two new mails."))
        assertTrue("$oldest < $middle < $newest", oldest < middle && middle < newest)
        assertTrue(top(hasText("Nov 3, ", substring = true)) < oldest)
        assertTrue(top(hasText("Yesterday ", substring = true)) < middle)
        assertTrue(top(hasText("Today ", substring = true)) < newest)
    }

    @Test
    fun silentRunsAndRunsStillGoingAreNotShown() {
        jobWithRuns()
        show()
        waitForEntries(3)
        compose.onAllNodesWithText("[SILENT]", substring = true).assertCountEquals(0)
        assertEquals(listOf("r1", "r2", "r4"), viewModel.state.value.entries.map { it.sessionId })
    }

    @Test
    fun aReplyIsRenderedAsMarkdown() {
        jobWithRuns()
        show()
        waitForEntries(3)
        compose.onNodeWithText("Two new mails.").assertIsDisplayed()
        compose.onAllNodesWithText("**", substring = true).assertCountEquals(0)
    }

    @Test
    fun showTheWholeRunOpensThatRunsChat() {
        jobWithRuns()
        show()
        waitForEntries(3)
        val buttons = compose.onAllNodes(hasText("Show the whole run") and hasClickAction()).fetchSemanticsNodes()
        assertEquals(3, buttons.size)
        // The newest entry is the lowest on screen.
        val lowest = buttons.maxBy { it.boundsInRoot.top }
        compose.onNode(SemanticsMatcher("node ${lowest.id}") { it.id == lowest.id }).performClick()
        assertEquals(listOf("r4"), openedRuns)
    }

    @Test
    fun aJobThatNeverReportedAnythingSaysSoAndCannotBeAnswered() {
        source.runs = mapOf("j1" to listOf(cronRun("r1", at(7, 10, 3))))
        source.replies = mapOf("r1" to "[SILENT]")
        show()
        waitForText("Nothing reported yet")
        compose.onNodeWithTag("composer").performTextInput("hello")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun aProgressIndicatorShowsUntilTheFirstReplyIsIn() {
        jobWithRuns()
        val gate = CompletableDeferred<Unit>()
        source.replyGate = gate
        show()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("feed-loading")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Nothing reported yet").assertCountEquals(0)

        gate.complete(Unit)
        waitForEntries(3)
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag("feed-loading")).assertCountEquals(0)
    }

    @Test
    fun aReplyThatFailsToLoadLeavesOnlyThatRunOut() {
        jobWithRuns()
        source.failingReplies = setOf("r2")
        show()
        waitForEntries(2)
        assertEquals(listOf("r1", "r4"), viewModel.state.value.entries.map { it.sessionId })
    }

    @Test
    fun runsThatFailToLoadOfferARetry() {
        source.failure = java.io.IOException("timeout")
        show()
        waitForText("Couldn't load the runs")
        source.failure = null
        jobWithRuns()
        compose.onNode(hasText("Retry") and hasClickAction()).performClick()
        waitForEntries(3)
    }

    @Test
    fun sendingAReplyHandsItToTheNewestReportedRun() {
        jobWithRuns()
        show()
        waitForEntries(3)
        compose.onNodeWithTag("composer").performTextInput("What was the second mail?")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("r4" to "What was the second mail?"), replies)
    }

    @Test
    fun openingTheFeedMarksTheJobSeenUpToItsNewestReportedRun() {
        jobWithRuns()
        show()
        waitForEntries(3)
        assertEquals(at(7, 10, 3), seen.jobSeenUntil("j1"))
    }

    @Test
    fun theComposerSitsBelowTheNewestEntry() {
        jobWithRuns()
        show()
        waitForEntries(3)
        compose.waitForIdle()
        val newest = compose.onNode(hasText("Two new mails.") and hasAnyAncestor(hasTestTag("job-feed")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("composer-box").fetchSemanticsNode().boundsInRoot
        assertTrue("$newest overlaps the composer $composer", newest.bottom <= composer.top)
    }

    @Test
    fun aRecreatedFeedDoesNotReadItsRunsAgain() {
        jobWithRuns()
        val restoration = StateRestorationTester(compose)
        show(restoration)
        waitForEntries(3)

        // What a rotation does: the screen is composed anew from its saved state, with the same view model.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        waitForText("Two new mails.")
        ShadowLooper.idleMainLooper()

        assertEquals(listOf("j1"), source.runsCalls)
    }

    @Test
    fun comingBackToTheFeedReadsItsNewRunsAndKeepsWhatItShows() {
        jobWithRuns()
        show()
        waitForEntries(3)
        // The entries come from the view model, which loads on its own; the screen's effects run with its frames.
        compose.waitForIdle()
        val gate = CompletableDeferred<Unit>()

        // Away, e.g. in a run's chat, while the job ran again.
        onScreen.value = false
        compose.waitForIdle()
        source.runs = mapOf("j1" to listOf(cronRun("r5", at(7, 11, 30))) + source.runs.getValue("j1"))
        source.replies = source.replies + ("r5" to "A third mail.")
        source.replyCalls.clear()
        source.replyGate = gate
        onScreen.value = true
        // A state written outside composition reaches the recomposer once the test lets the UI settle.
        compose.waitForIdle()

        compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); source.runsCalls.size == 2 }
        // While the new reply is on its way, the feed shows what it did, with no progress indicator.
        assertEquals(listOf("r1", "r2", "r4"), viewModel.state.value.entries.map { it.sessionId })
        compose.onAllNodes(hasTestTag("feed-loading")).assertCountEquals(0)
        gate.complete(Unit)
        waitForEntries(4)
        assertEquals(listOf("r1", "r2", "r4", "r5"), viewModel.state.value.entries.map { it.sessionId })
        // Only the new run's reply was read; the others were kept, the silent one's included.
        assertEquals(listOf("r5"), source.replyCalls)
    }

    @Test
    fun repliesAreReadSeveralAtATimeButNoMoreThanFour() {
        source.runs = mapOf("j1" to (1..10).map { cronRun("r$it", at(7, 10, 0).minusSeconds(it * 3_600L)) })
        source.replies = (1..10).associate { "r$it" to "Report $it" }
        val gate = CompletableDeferred<Unit>()
        source.replyGate = gate
        show()

        compose.waitUntil(10_000) { source.repliesInFlight == 4 }
        Thread.sleep(200)
        compose.waitForIdle()
        assertEquals(4, source.mostRepliesInFlight)

        gate.complete(Unit)
        waitForEntries(10)
        assertEquals(4, source.mostRepliesInFlight)
    }
}
