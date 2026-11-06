package net.jkraemer.hermes.sessions

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
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
import net.jkraemer.hermes.core.sessions.SearchHit
import net.jkraemer.hermes.core.sessions.SessionPage
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.sessions.Snippet
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

internal val NOW = Instant.parse("2026-11-05T12:00:00Z")

internal fun session(
    id: String,
    title: String? = id,
    preview: String? = null,
    source: String = "tui",
    pinned: Boolean = false,
    lastActive: Instant = NOW.minusSeconds(60),
) = SessionSummary(id, title, preview, source, lastActive, lastActive, 1, pinned, archived = false)

internal fun hit(session: SessionSummary, snippet: Snippet? = null) = SearchHit(session, snippet)

internal class FakeSource(
    var sessions: List<SessionSummary> = emptyList(),
    var total: Int = sessions.size,
    var searchResults: List<SearchHit> = emptyList(),
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

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
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
    private var menus = 0
    private lateinit var seen: SeenStore
    private lateinit var viewModel: SessionListViewModel

    private fun show(vararg sessions: SessionSummary, total: Int = sessions.size) {
        source.sessions = sessions.toList()
        source.total = total
        show()
    }

    private fun show() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-test", Context.MODE_PRIVATE)
        seen = SeenStore(prefs) { NOW.minusSeconds(86_400) }
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        viewModel = SessionListViewModel(source, needsAnswer, seen, clock)
        compose.setContent {
            MaterialTheme {
                SessionListScreen(
                    viewModel, host = "hermes.example.ts.net",
                    onOpenMenu = { menus++ },
                    clock = clock,
                    onOpenChat = { id, title -> opened += id to title },
                    onNotLoggedIn = { loggedOut++ },
                    onRetryConnect = { reconnects++ },
                )
            }
        }
    }

    private fun openSearch() = compose.onNodeWithContentDescription("Search").performClick()

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun hasCount(description: String) =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    @Test
    fun topBarIsCenteredRecentsWithAMenuButtonAndNoHermesTitle() {
        show(session("a"))
        waitForText("a")
        compose.onNodeWithText("Recents").assertIsDisplayed()
        compose.onAllNodes(hasText("Hermes")).assertCountEquals(0)
        compose.onNodeWithContentDescription("Menu").performClick()
        assertEquals(1, menus)
        val bar = compose.onNodeWithText("Recents").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, bar.center.x, 2f)
    }

    @Test
    fun cronRowsShowAClockIconAndOtherRowsAChatIconInsteadOfATag() {
        show(session("a", title = "Morning briefing", source = "cron"), session("b", title = "Chatty"))
        waitForText("Morning briefing")
        assertEquals(1, hasCount("Cron run"))
        assertEquals(1, hasCount("Chat"))
        compose.onAllNodes(hasText("cron")).assertCountEquals(0)
    }

    @Test
    fun rowsShowNoPreviewAndTheTimeAsTheSecondLine() {
        show(session("a", title = "Disk cleanup", preview = "Free up space on the NAS", lastActive = NOW.minusSeconds(5 * 60)))
        waitForText("Disk cleanup")
        compose.onNodeWithText("5 minutes ago").assertIsDisplayed()
        compose.onAllNodes(hasText("Free up space", substring = true)).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp")
    fun olderRowsShowDateAndClockTime() {
        show(session("a", title = "Old one", lastActive = Instant.parse("2026-11-03T14:03:00Z")))
        waitForText("Old one")
        // The clock time uses the locale's own spacing, so only the date part is matched.
        compose.onNode(hasText("Nov 3, ", substring = true) and hasText("2:03", substring = true)).assertIsDisplayed()
    }

    @Test
    fun theUnreadDotSitsAtTheRightEndOfTheRowAfterTheTitle() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")
        val title = compose.onNodeWithText("Morning briefing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dot = compose.onNodeWithTag("unread-dot", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("dot $dot is not after the title $title", dot.left >= title.right)
        assertTrue("dot $dot is not at the right end of $root", root.right - dot.right < 48f * 3)
    }

    @Test
    fun aRowOpenedSinceItsLastActivityHasNoDot() {
        show(session("a", title = "Morning briefing"))
        seen.markOpened("a", NOW)
        // Opening a chat does not reload the list by itself; a changed input makes the state read the store again.
        needsAnswer.value = setOf("unrelated")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("unread-dot"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
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
        source.searchResults = listOf(hit(session("hit", title = "Sourdough hydration", lastActive = NOW.minusSeconds(9 * 86_400))))
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")

        openSearch()
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
        source.searchResults = listOf(hit(session("hit", title = "Sourdough hydration")))
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")
        openSearch()
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
        source.searchResults = listOf(hit(session("hit", title = "Sourdough hydration")), hit(session("hit", title = "Sourdough hydration")))
        show(session("a", title = "Morning briefing"))
        openSearch()
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
    fun roundSearchAndNewChatButtonsShowNoLabelText() {
        show(session("a"))
        waitForText("a")
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
        compose.onAllNodes(hasText("New chat")).assertCountEquals(0)
        val search = compose.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithContentDescription("New chat").fetchSemanticsNode().boundsInRoot
        assertTrue("search $search is not left of $plus", search.right < plus.left)
    }

    @Test
    fun openingSearchShowsAFocusedFieldAndHidesTheRoundButtons() {
        show(session("a"))
        waitForText("a")

        openSearch()

        compose.onNodeWithTag("search").assertIsDisplayed().assertIsFocused()
        assertEquals(0, hasCount("Search"))
        assertEquals(0, hasCount("New chat"))
        assertEquals(1, hasCount("Close search"))
    }

    @Test
    fun theFieldsInnerClearButtonShowsOnlyWithTextAndClearsIt() {
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")
        openSearch()
        assertEquals(0, hasCount("Clear search"))

        compose.onNodeWithTag("search").performTextInput("sour")
        assertEquals(1, hasCount("Clear search"))
        compose.onNodeWithContentDescription("Clear search").performClick()

        assertEquals("", viewModel.state.value.query)
        assertEquals(0, hasCount("Clear search"))
        assertEquals(1, hasCount("Close search"))
    }

    @Test
    fun theOuterCloseButtonClosesSearchAndRestoresTheTimelineAndButtons() {
        source.searchResults = listOf(hit(session("hit", title = "Sourdough hydration")))
        show(session("a", title = "Morning briefing"))
        waitForText("Morning briefing")
        openSearch()
        compose.onNodeWithTag("search").performTextInput("sourdough")
        waitForText("Sourdough hydration")

        compose.onNodeWithContentDescription("Close search").performClick()

        waitForText("Morning briefing")
        compose.onAllNodes(hasTestTag("search")).assertCountEquals(0)
        assertEquals(1, hasCount("Search"))
        assertEquals(1, hasCount("New chat"))
        assertEquals("", viewModel.state.value.query)
        compose.onAllNodes(hasText("Sourdough hydration")).assertCountEquals(0)
    }

    @Test
    fun searchOpenedFromOutsideStartsFocused() {
        show(session("a"))
        viewModel.openSearch()
        compose.onNodeWithTag("search").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun searchResultsAreRowsWithKindIconTitleAndTheBoldMatchAsSecondLine() {
        source.searchResults = listOf(
            hit(session("hit", title = "Baking notes", source = "cron"), Snippet("...the sourdough starter needs feeding", listOf(7..15))),
            hit(session("id", title = "Plain hit"), null),
        )
        show(session("a", title = "Morning briefing"))
        openSearch()
        compose.onNodeWithTag("search").performTextInput("sourdough")
        waitForText("Baking notes")

        assertEquals(1, hasCount("Cron run"))
        assertEquals(1, hasCount("Chat"))
        val text = compose.onNodeWithText("...the sourdough starter needs feeding", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertEquals(
            listOf(AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 7, 16)),
            text.spanStyles,
        )
        compose.onAllNodes(hasTestTag("unread-dot"), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Baking notes").performClick()
        assertEquals(listOf<Pair<String?, String?>>("hit" to "Baking notes"), opened)
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
        compose.onNodeWithContentDescription("New chat").performClick()
        assertEquals(listOf<Pair<String?, String?>>(null to null), opened)
    }

    @Test
    fun lastRowScrollsAboveTheNewChatButton() {
        val all = (1..30).map { session("s$it", lastActive = NOW.minusSeconds(it * 60L)) }
        show(*all.toTypedArray())
        waitForText("s1")

        compose.onNodeWithTag("session-list").performScrollToIndex(all.size)
        compose.waitForIdle()

        val lastRow = compose.onNodeWithText("s30").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithContentDescription("New chat").fetchSemanticsNode().boundsInRoot
        assertTrue("last row $lastRow overlaps the button $button", lastRow.bottom <= button.top)
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
    fun unreachableServerShowsConnectionHintAndRetries() {
        source.failure = IOException("timeout")
        show()
        waitForText("Can't reach hermes.example.ts.net. Check your connection or VPN.")

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
        waitForText("Can't reach hermes.example.ts.net. Check your connection or VPN.")

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
