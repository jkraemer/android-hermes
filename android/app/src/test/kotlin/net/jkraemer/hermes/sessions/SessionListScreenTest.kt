package net.jkraemer.hermes.sessions

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import net.jkraemer.hermes.core.auth.NotLoggedInException
import net.jkraemer.hermes.core.sessions.SessionPage
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.settings.SeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val NOW = Instant.parse("2026-11-05T12:00:00Z")

private fun session(
    id: String,
    title: String? = id,
    preview: String? = null,
    source: String = "tui",
    pinned: Boolean = false,
    lastActive: Instant = NOW.minusSeconds(60),
) = SessionSummary(id, title, preview, source, lastActive, lastActive, 1, pinned, archived = false)

private class FakeSource(
    var sessions: List<SessionSummary> = emptyList(),
    var total: Int = sessions.size,
    var searchResults: List<SessionSummary> = emptyList(),
    var failure: Exception? = null,
) : SessionListSource {
    val updates = mutableListOf<List<Any?>>()
    val listCalls = mutableListOf<Int>()

    /** When set, searches wait for it; [searchStarted] counts the searches that have begun. */
    var searchGate: CompletableDeferred<Unit>? = null
    @Volatile var searchStarted = 0

    override suspend fun list(offset: Int, limit: Int): SessionPage {
        failure?.let { throw it }
        listCalls += offset
        return SessionPage(sessions.drop(offset).take(limit), total)
    }

    override suspend fun search(query: String, limit: Int): List<SessionSummary> {
        failure?.let { throw it }
        searchStarted++
        searchGate?.await()
        return searchResults
    }

    override suspend fun update(id: String, title: String?, pinned: Boolean?, archived: Boolean?) {
        updates += listOf(id, title, pinned, archived)
        sessions = sessions.map { if (it.id == id) it.copy(pinned = pinned ?: it.pinned) else it }
    }
}

@RunWith(RobolectricTestRunner::class)
class SessionListScreenTest {
    @get:Rule val compose = createComposeRule()

    private val source = FakeSource()
    private val needsAnswer = MutableStateFlow(emptySet<String>())
    private val opened = mutableListOf<Pair<String?, String?>>()
    private var loggedOut = 0
    private var reconnects = 0

    private fun show(vararg sessions: SessionSummary, total: Int = sessions.size) {
        source.sessions = sessions.toList()
        source.total = total
        show()
    }

    private fun show() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-test", Context.MODE_PRIVATE)
        val viewModel = SessionListViewModel(
            source, needsAnswer, SeenStore(prefs) { NOW.minusSeconds(86_400) },
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
        compose.setContent {
            MaterialTheme {
                SessionListScreen(
                    viewModel, host = "hermes.example.ts.net",
                    onOpenChat = { id, title -> opened += id to title },
                    onNotLoggedIn = { loggedOut++ },
                    onRetryConnect = { reconnects++ },
                )
            }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun showsCronTag() {
        show(session("a", title = "Morning briefing", source = "cron"))
        waitForText("Morning briefing")
        compose.onNodeWithText("cron").assertIsDisplayed()
    }

    @Test
    fun showsApprovalTagForSessionsNeedingAnAnswer() {
        needsAnswer.value = setOf("a")
        show(session("a", title = "Disk cleanup"))
        waitForText("Disk cleanup")
        compose.onNodeWithText("approval").assertIsDisplayed()
        compose.onNodeWithText("Needs answer").assertIsDisplayed()
    }

    @Test
    fun showsUntitledFallback() {
        show(session("a", title = null, preview = null), session("b", title = null, preview = "At 75% you'd want"))
        waitForText("Untitled")
        compose.onNodeWithText("Untitled").assertIsDisplayed()
        // The preview stands in for the title, so it is not repeated as the subtitle.
        compose.onNodeWithText("At 75% you'd want").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp")
    fun showsBucketHeaders() {
        show(
            session("a", pinned = true),
            session("b"),
            session("c", lastActive = NOW.minusSeconds(86_400)),
            session("d", lastActive = NOW.minusSeconds(5 * 86_400)),
        )
        waitForText("Pinned")
        listOf("Pinned", "Today", "Yesterday", "Older").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun searchReplacesTimeline() {
        source.searchResults = listOf(session("hit", title = "Sourdough hydration", lastActive = NOW.minusSeconds(9 * 86_400)))
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")

        compose.onNodeWithTag("search").performTextInput("sourdough")
        waitForText("Sourdough hydration")

        compose.onAllNodes(androidx.compose.ui.test.hasText("Morning briefing")).assertCountEquals(0)
        compose.onAllNodes(androidx.compose.ui.test.hasText("Today")).assertCountEquals(0)
        compose.onAllNodes(androidx.compose.ui.test.hasText("Older")).assertCountEquals(0)

        compose.onNodeWithTag("search").performTextClearance()
        waitForText("Morning briefing")
    }

    @Test
    fun aSearchFinishingAfterTheQueryWasClearedDoesNotBringBackSearchRows() {
        source.searchResults = listOf(session("hit", title = "Sourdough hydration"))
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")
        compose.onNodeWithTag("search").performTextInput("sourdough")
        waitForText("Sourdough hydration")

        // A refresh searches again; clear the query while that search is in flight.
        source.searchGate = CompletableDeferred()
        val started = source.searchStarted
        compose.onNodeWithTag("session-list").performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { source.searchStarted > started }
        compose.onNodeWithTag("search").performTextClearance()
        source.searchGate!!.complete(Unit)

        waitForText("Morning briefing")
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Sourdough hydration")).assertCountEquals(0)
    }

    @Test
    fun duplicateSearchResultsShowOneRow() {
        source.searchResults = listOf(session("hit", title = "Sourdough hydration"), session("hit", title = "Sourdough hydration"))
        show(session("a", title = "Morning briefing"))
        compose.onNodeWithTag("search").performTextInput("sourdough")
        waitForText("Sourdough hydration")
        compose.onAllNodes(androidx.compose.ui.test.hasText("Sourdough hydration")).assertCountEquals(1)
    }

    @Test
    fun longPressPinCallsUpdate() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")

        compose.onNodeWithText("Morning briefing").performTouchInput { longClick() }
        compose.onNodeWithText("Pin").performClick()

        compose.waitUntil(10_000) { source.updates.isNotEmpty() }
        assertEquals(listOf(listOf<Any?>("a", null, true, null)), source.updates)
        // The list reloads, so the pinned session now sits under its header.
        waitForText("Pinned")
    }

    @Test
    fun longPressOnAPinnedSessionOffersUnpin() {
        show(session("a", title = "Morning briefing", pinned = true))
        waitForText("Morning briefing")

        compose.onNodeWithText("Morning briefing").performTouchInput { longClick() }
        compose.onNodeWithText("Unpin").performClick()

        compose.waitUntil(10_000) { source.updates.isNotEmpty() }
        assertEquals(listOf(listOf<Any?>("a", null, false, null)), source.updates)
    }

    @Test
    fun longPressArchiveCallsUpdate() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")

        compose.onNodeWithText("Morning briefing").performTouchInput { longClick() }
        compose.onNodeWithText("Archive").performClick()

        compose.waitUntil(10_000) { source.updates.isNotEmpty() }
        assertEquals(listOf(listOf<Any?>("a", null, null, true)), source.updates)
    }

    @Test
    fun longPressRenameAsksForANewTitle() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")

        compose.onNodeWithText("Morning briefing").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("rename-field").performTextClearance()
        compose.onNodeWithTag("rename-field").performTextInput("Daily briefing")
        compose.onNodeWithText("Save").performClick()

        compose.waitUntil(10_000) { source.updates.isNotEmpty() }
        assertEquals(listOf(listOf<Any?>("a", "Daily briefing", null, null)), source.updates)
    }

    @Test
    fun renameDialogStartsWithTheCurrentTitle() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")

        compose.onNodeWithText("Morning briefing").performTouchInput { longClick() }
        compose.onNodeWithText("Rename").performClick()

        compose.onNodeWithTag("rename-field").assertTextEquals("Morning briefing")
    }

    @Test
    fun tappingARowOpensItsChat() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")
        compose.onNodeWithText("Morning briefing").performClick()
        assertEquals(listOf<Pair<String?, String?>>("a" to "Morning briefing"), opened)
    }

    @Test
    fun newChatOpensChat() {
        show(session("a"))
        compose.onNodeWithText("New chat").performClick()
        assertEquals(listOf<Pair<String?, String?>>(null to null), opened)
    }

    @Test
    fun loadsTheNextPageWhenScrolledToTheEnd() {
        val all = (1..60).map { session("s$it", lastActive = NOW.minusSeconds(it * 60L)) }
        show(*all.toTypedArray())
        compose.waitUntil(10_000) { source.listCalls.isNotEmpty() }

        compose.onNodeWithTag("session-list").performScrollToIndex(50)

        compose.waitUntil(10_000) { source.listCalls.size >= 2 }
        assertEquals(listOf(0, 50), source.listCalls.take(2))
    }

    @Test
    fun unreachableServerShowsTailscaleHintAndRetries() {
        source.failure = IOException("timeout")
        show()
        waitForText("Can't reach hermes.example.ts.net — is Tailscale on?")

        source.failure = null
        source.sessions = listOf(session("a", title = "Morning briefing"))
        compose.onNodeWithText("Retry").performClick()

        waitForText("Morning briefing")
        assertEquals(1, reconnects)
    }

    @Test
    fun unreachableServerKeepsLoadedRows() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")

        source.failure = IOException("timeout")
        compose.onNodeWithTag("session-list").performTouchInput { swipeDown() }
        waitForText("Can't reach hermes.example.ts.net — is Tailscale on?")

        compose.onNodeWithText("Morning briefing").assertIsDisplayed()
    }

    @Test
    fun lostLoginLeavesForTheLoginScreen() {
        source.failure = NotLoggedInException()
        show()
        compose.waitUntil(10_000) { loggedOut > 0 }
        assertEquals(1, loggedOut)
    }

    @Test
    fun otherFailuresShowAnErrorInsteadOfCrashing() {
        source.failure = IllegalStateException("boom")
        show()
        waitForText("Something went wrong")
        assertTrue(compose.onAllNodes(hasTestTag("session-list")).fetchSemanticsNodes().isNotEmpty())
    }
}
