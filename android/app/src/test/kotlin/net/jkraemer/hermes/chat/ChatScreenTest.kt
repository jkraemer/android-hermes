package net.jkraemer.hermes.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.chat.RpcPeer
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcFrame
import net.jkraemer.hermes.settings.SeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

private val NOW = Instant.parse("2026-11-05T12:00:00Z")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    private val peer = FakePeer()
    private val needsAnswer = MutableStateFlow(emptySet<String>())
    private var reconnects = 0
    private var menus = 0
    private var newChats = 0
    private val storedIds = mutableListOf<String>()
    private lateinit var viewModel: ChatViewModel
    private lateinit var seenStore: SeenStore
    private lateinit var focusManager: FocusManager

    private fun show(
        storedSessionId: String? = "stored1",
        title: String? = "Morning briefing",
        cron: Boolean = false,
        restoration: StateRestorationTester? = null,
    ) {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-test", Context.MODE_PRIVATE)
        seenStore = SeenStore(prefs)
        viewModel = ChatViewModel(peer, storedSessionId, title, needsAnswer, seenStore) { NOW }
        val setContent: (@androidx.compose.runtime.Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: compose::setContent
        setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                ChatScreen(
                    viewModel,
                    cron = cron,
                    onOpenMenu = { menus++ },
                    onNewChat = { newChats++ },
                    onRetryConnect = { reconnects++ },
                    onStoredSessionId = { storedIds += it },
                    clock = { NOW },
                    keyboardVisible = keyboardVisible.value,
                )
            }
        }
    }

    /**
     * A reply is parsed off the main thread and handed back to it; waitUntil only polls, so each
     * poll also runs what is queued on the main looper, or a late hand-back would never land.
     */
    private fun waitForParsedReplies(count: Int) = compose.waitUntil(10_000) {
        ShadowLooper.idleMainLooper()
        viewModel.parsedReplies.value.size == count
    }

    private fun waitForText(text: String, substring: Boolean = false) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private val promptTranscript = """{"session_id":"live1","stored_session_id":"stored1","messages":[
        {"role":"user","text":"/attention-check"},{"role":"assistant","text":"All quiet."},{"role":"user","text":"thanks"}
        ],"running":false,"open_requests":[]}"""

    private fun reply(n: Int) = (1..6).joinToString("\n\n") { "Reply $n paragraph $it says something **useful** about the question, long enough to wrap over several lines on a phone screen." }

    /** [pairs] questions, each answered by a finished reply of several paragraphs, and then [lastReply] if there is one. */
    private fun longTranscript(pairs: Int, lastReply: String? = null, running: Boolean = false): String {
        val messages = buildJsonArray {
            for (n in 1..pairs) {
                add(buildJsonObject { put("role", "user"); put("text", "Question $n") })
                add(buildJsonObject { put("role", "assistant"); put("text", reply(n)) })
            }
            if (lastReply != null) add(buildJsonObject { put("role", "assistant"); put("text", lastReply) })
        }
        return """{"session_id":"live1","stored_session_id":"stored1","messages":$messages,"running":$running,"open_requests":[]}"""
    }

    private fun showLongChat(pairs: Int, restoration: StateRestorationTester? = null) {
        peer.resume = longTranscript(pairs)
        show(restoration = restoration)
        compose.waitUntil(10_000) { viewModel.chat.value.items.size == 2 * pairs }
        // The replies are parsed on Dispatchers.Default, which waitForIdle does not wait for.
        waitForParsedReplies(pairs)
        compose.waitForIdle()
    }

    private fun present(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The top of each question bubble laid out now, by the question's number. */
    private fun questionTops(pairs: Int) = (1..pairs).mapNotNull { n ->
        compose.onAllNodesWithText("Question $n").fetchSemanticsNodes().firstOrNull()?.let { n to it.positionInRoot.y }
    }.toMap()

    @Test
    fun aLongChatOpensAtItsEnd() {
        showLongChat(6)

        compose.onNode(hasText("Reply 6 paragraph 6", substring = true)).assertIsDisplayed()
    }

    /** Paragraphs [from]..[to] of reply 7, which is several screens long once complete. */
    private fun longReply(from: Int, to: Int) =
        (from..to).joinToString("\n\n") { "Reply 7 paragraph $it says something **useful** about the question, long enough to wrap over several lines on a phone screen." }

    private fun textPayload(text: String) = buildJsonObject { put("text", text) }.toString()

    /** The long chat of six questions, scrolled to its end, while the first half of reply 7 streams in. */
    private fun streamHalfALongReply() {
        showLongChat(6)
        peer.event("message.start")
        peer.event("message.delta", textPayload(longReply(1, 15)))
        waitForText("Reply 7 paragraph 15 says", substring = true)
        compose.waitForIdle()
        compose.onNode(hasText("Reply 7 paragraph 15 says", substring = true)).assertIsDisplayed()
    }

    private fun dropTheConnection() {
        peer.state.value = ConnectionState.Connecting
        compose.waitForIdle()
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
    }

    /** Returns once the resync after the drop is done and the banner is gone. */
    private fun reconnect() {
        val before = peer.calls.size
        peer.state.value = ConnectionState.Ready
        compose.waitUntil(10_000) { peer.calls.drop(before).any { it.first == "session.events.since" } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reconnecting…").fetchSemanticsNodes().isEmpty() }
    }

    /** The rest of reply 7 arrives and the reply completes. */
    private fun finishTheLongReply() {
        peer.event("message.delta", textPayload("\n\n" + longReply(16, 30)))
        peer.event("message.complete", textPayload(longReply(1, 30)))
        // Emitted events reach the chat through the main looper, which waitUntil alone does not run.
        compose.waitForIdle()
        waitForParsedReplies(7)
        compose.waitForIdle()
    }

    private fun assertTheLongReplyEndIsShown() =
        compose.onNode(hasText("Reply 7 paragraph 30 says", substring = true)).assertIsDisplayed()

    @Test
    fun aReconnectMidReplyKeepsFollowingTheReplyToItsEnd() {
        streamHalfALongReply()
        dropTheConnection()

        reconnect()
        finishTheLongReply()

        assertTheLongReplyEndIsShown()
    }

    @Test
    fun aReconnectThatReopensTheChatShowsTheEndOfTheReplyFinishedMeanwhile() {
        streamHalfALongReply()
        dropTheConnection()
        // Hermes reaped the detached session and finished the reply without us; the reopened transcript has all of it.
        peer.refusing = setOf("session.activate")
        peer.resume = longTranscript(6, lastReply = longReply(1, 30))

        reconnect()
        waitForParsedReplies(7)
        compose.waitForIdle()

        assertEquals("reopened", 2, peer.calls.count { it.first == "session.resume" })
        assertTheLongReplyEndIsShown()
    }

    @Test
    fun aReconnectThatReopensTheChatKeepsFollowingTheReply() {
        streamHalfALongReply()
        dropTheConnection()
        // The reopened transcript ends with the question; the reply goes on streaming.
        peer.refusing = setOf("session.activate")
        peer.resume = longTranscript(6, running = true)

        reconnect()
        finishTheLongReply()

        assertEquals("reopened", 2, peer.calls.count { it.first == "session.resume" })
        assertTheLongReplyEndIsShown()
    }

    @Test
    fun aReconnectLeavesAChatScrolledAwayFromItsEndWhereItWas() {
        streamHalfALongReply()
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val (paragraph, top) = firstParagraphShown()
        dropTheConnection()

        reconnect()
        finishTheLongReply()

        assertEquals(paragraph, top, compose.onNodeWithText(paragraph, substring = true).fetchSemanticsNode().positionInRoot.y, 0.5f)
    }

    /** The first question or reply paragraph that is in view, and its top. */
    private fun firstParagraphShown(): Pair<String, Float> {
        val messages = compose.onNodeWithTag("messages").fetchSemanticsNode().boundsInRoot
        val paragraphs = (1..7).flatMap { n -> listOf("Question $n") + (1..15).map { "Reply $n paragraph $it says" } }
        return paragraphs.firstNotNullOf { text ->
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().firstOrNull()
                ?.positionInRoot?.y?.takeIf { it >= messages.top && it < messages.bottom }?.let { text to it }
        }
    }

    @Test
    fun aFinishedReplyHasItsTextInTheFrameItIsScrolledTo() {
        showLongChat(6)
        // A jump to an index is not a scroll the chat sees, so the user first scrolls away from the end it follows.
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("messages").performScrollToIndex(3)

        assertTrue("reply 2 is laid out with its text, not empty", present("Reply 2 paragraph 1"))
    }

    @Test
    fun draggingUpMovesTheChatWithTheFingerWhileEarlierRepliesComeIntoView() {
        val pairs = 8
        showLongChat(pairs)
        compose.onNodeWithTag("messages").performScrollToIndex(2 * pairs - 1)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val step = 40f
        // The first move also takes up the touch slop, so it moves the chat by less than a step.
        compose.onNodeWithTag("messages").performTouchInput { down(center); moveBy(Offset(0f, step)) }
        compose.mainClock.advanceTimeByFrame()
        val repliesAtStart = (1..pairs).filter { present("Reply $it paragraph") }

        var before = questionTops(pairs)
        repeat(20) { frame ->
            compose.onNodeWithTag("messages").performTouchInput { moveBy(Offset(0f, step)) }
            compose.mainClock.advanceTimeByFrame()
            val after = questionTops(pairs)
            for (n in before.keys intersect after.keys) {
                assertEquals("Question $n moves with the finger in frame $frame", before.getValue(n) + step, after.getValue(n), 0.5f)
            }
            before = after
        }
        compose.onNodeWithTag("messages").performTouchInput { up() }
        assertTrue("an earlier reply came into view from the top", (1..pairs).any { it !in repliesAtStart && present("Reply $it paragraph") })
    }

    @Test
    fun aReplyStaysRenderedAsItFinishesStreaming() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[]}"""
        show()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        val text = buildJsonObject { put("text", "Some **bold** words.\n\nA second paragraph.") }.toString()
        peer.event("message.delta", text)
        compose.waitUntil(10_000) { present("Some bold words.") }
        compose.mainClock.autoAdvance = false

        peer.event("message.complete", text)

        repeat(3) { frame ->
            compose.mainClock.advanceTimeByFrame()
            assertTrue("frame $frame shows the reply as Markdown", present("Some bold words.") && !present("**"))
        }
    }

    /** A cron run that worked its way to a reply, which the user then answered. */
    private val workTranscript = """{"session_id":"live1","stored_session_id":"stored1","messages":[
        {"role":"user","text":"/attention-check"},
        {"role":"assistant","text":"Let me look at the inbox."},
        {"role":"assistant","text":""},
        {"role":"tool","tool_call_id":"t1","name":"read_file"},
        {"role":"tool","tool_call_id":"t2","name":"search_files"},
        {"role":"assistant","text":"Two new mails."},
        {"role":"user","text":"thanks"},
        {"role":"assistant","text":"You're welcome."}
        ],"running":false,"open_requests":[]}"""

    @Test
    fun cronChatCollapsesTheJobPromptAndExpandsItOnTap() {
        peer.resume = promptTranscript
        show(cron = true)

        waitForText("Show work (1 step)")
        compose.onNodeWithText("/attention-check").assertDoesNotExist()
        compose.onNodeWithText("All quiet.").assertIsDisplayed()

        compose.onNodeWithText("Show work (1 step)").performClick()
        compose.onNodeWithText("/attention-check").assertIsDisplayed()

        compose.onNodeWithText("Show work (1 step)").performClick()
        compose.onNodeWithText("/attention-check").assertDoesNotExist()
    }

    @Test
    fun cronChatShowsLaterUserMessagesAsBubbles() {
        peer.resume = promptTranscript
        show(cron = true)

        waitForText("thanks")
        compose.onAllNodesWithText("Show work", substring = true).assertCountEquals(1)
    }

    @Test
    fun otherChatShowsTheFirstUserMessageAsABubble() {
        peer.resume = promptTranscript
        show()

        waitForText("/attention-check")
        compose.onAllNodesWithText("Show work", substring = true).assertCountEquals(0)
    }

    @Test
    fun cronChatFoldsEverythingBeforeTheFinalReplyIntoOneLine() {
        peer.resume = workTranscript
        show(cron = true)

        waitForText("Show work (4 steps)")
        compose.onNodeWithText("Two new mails.").assertIsDisplayed()
        compose.onNodeWithText("thanks").assertIsDisplayed()
        compose.onNodeWithText("You're welcome.").assertIsDisplayed()
        compose.onNodeWithText("/attention-check").assertDoesNotExist()
        compose.onNodeWithText("Let me look at the inbox.").assertDoesNotExist()
        compose.onAllNodesWithText("read_file", substring = true).assertCountEquals(0)

        compose.onNodeWithText("Show work (4 steps)").performClick()

        compose.onNodeWithText("/attention-check").assertIsDisplayed()
        compose.onNodeWithText("Let me look at the inbox.").assertIsDisplayed()
        compose.onNodeWithText("read_file · 1 more tool").assertIsDisplayed()
        compose.onNodeWithText("Two new mails.").assertIsDisplayed()
    }

    @Test
    fun theWorkLineHasTheClockIcon() {
        peer.resume = workTranscript
        show(cron = true)

        waitForText("Show work (4 steps)")
        compose.onNode(hasTestTag("work-icon"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun toolLineCollapsesAndExpands() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[]}"""
        show()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        peer.event("tool.start", """{"tool_id":"t1","name":"terminal"}""")
        peer.event("tool.complete", """{"tool_id":"t1","summary":"ls -la"}""")
        peer.event("tool.start", """{"tool_id":"t2","name":"read_file"}""")
        peer.event("tool.complete", """{"tool_id":"t2","summary":"notes.md"}""")
        peer.event("tool.start", """{"tool_id":"t3","name":"patch"}""")
        peer.event("tool.complete", """{"tool_id":"t3","summary":"x.kt","error":"conflict"}""")

        waitForText("terminal · ls -la · 2 more tools")
        compose.onNodeWithText("read_file · notes.md").assertDoesNotExist()

        compose.onNodeWithText("terminal · ls -la · 2 more tools").performClick()

        compose.onNodeWithText("terminal · ls -la").assertIsDisplayed()
        compose.onNodeWithText("read_file · notes.md").assertIsDisplayed()
        compose.onNodeWithText("patch · x.kt · failed").assertIsDisplayed()

        compose.onNodeWithText("terminal · ls -la").performClick()
        compose.onNodeWithText("read_file · notes.md").assertDoesNotExist()
    }

    @Test
    fun stopShownWhileRunning() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[]}"""
        show()

        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Stop")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()

        compose.onNodeWithContentDescription("Stop").performClick()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.interrupt" } }

        peer.event("message.complete", """{"text":"done"}""")
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Send")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun sendDisabledWhileReconnecting() {
        show()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        compose.onNodeWithTag("composer").performTextInput("hello")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.onNodeWithText("Reconnecting…").assertDoesNotExist()

        peer.state.value = ConnectionState.Connecting

        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, reconnects)
    }

    @Test
    fun sendSubmitsThePrompt() {
        show()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        compose.onNodeWithTag("composer").performTextInput("hello")
        compose.onNodeWithContentDescription("Send").performClick()

        compose.waitUntil(10_000) { peer.calls.any { it.first == "prompt.submit" } }
        assertEquals("hello", (peer.calls.first { it.first == "prompt.submit" }.second["text"] as kotlinx.serialization.json.JsonPrimitive).content)
        waitForText("hello")
    }

    @Test
    fun clarifySendEnabledOnlyWhenAnswered() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[
            {"id":"srq-2","method":"clarify","params":{"session_id":"live1","questions":[
              {"qid":"q1","question":"Colour?","choices":["red","blue"],"multi_select":false},
              {"qid":"q2","question":"Sizes?","choices":["a","b"],"multi_select":true}]}}]}"""
        show()
        waitForText("Colour?")

        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("red").performClick()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("a").performClick()
        compose.onNodeWithText("Send").assertIsEnabled()

        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) { peer.answers.isNotEmpty() }
        assertEquals("srq-2", peer.answers.single().first)
    }

    @Test
    fun otherChipTakesFreeText() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[
            {"id":"srq-2","method":"clarify","params":{"session_id":"live1","questions":[
              {"qid":"q1","question":"Colour?","choices":["red"],"multi_select":false}]}}]}"""
        show()
        waitForText("Colour?")

        compose.onNodeWithText("Other…").performClick()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithTag("other-q1").performTextInput("green")
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test
    fun approvalCardAnswersThroughTheController() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[
            {"id":"srq-1","method":"approval","params":{"session_id":"live1","request_id":"r1","command":"rm x","choices":["once","deny"]}}]}"""
        show()
        waitForText("Approval needed")
        assertTrue(needsAnswer.value.contains("stored1"))

        compose.onNodeWithText("Allow once").performClick()

        compose.waitUntil(10_000) { peer.answers.isNotEmpty() }
        assertEquals("srq-1", peer.answers.single().first)
        waitForText("Allowed once")
        compose.waitUntil(10_000) { needsAnswer.value.isEmpty() }
    }

    @Test
    fun titleComesFromTheListThenFromHermes() {
        show(title = "Morning briefing")
        compose.onNodeWithText("Morning briefing").assertIsDisplayed()

        peer.event("session.title", """{"session_id":"stored1","title":"Renamed by Hermes"}""")
        waitForText("Renamed by Hermes")
    }

    @Test
    fun newChatShowsPlaceholderTitleAndReportsItsStoredId() {
        show(storedSessionId = null, title = null)
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.waitUntil(10_000) { storedIds == listOf("stored1") }
        assertTrue(peer.calls.any { it.first == "session.create" })
    }

    @Test
    fun menuButtonOpensTheDrawerInsteadOfGoingBack() {
        show()
        compose.onAllNodes(hasContentDescription("Back")).assertCountEquals(0)
        compose.onNodeWithContentDescription("Menu").performClick()
        assertEquals(1, menus)
    }

    @Test
    fun plusButtonStartsANewChat() {
        show()
        compose.onNodeWithContentDescription("New chat").performClick()
        assertEquals(1, newChats)
    }

    @Test
    fun openingAndFinishingAReplyMarksTheSessionSeen() {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":true,"open_requests":[]}"""
        show()
        compose.waitUntil(10_000) { seenStore.lastOpened()["stored1"] == NOW }
    }

    @Test
    fun aFailedSendShowsAMessageInsteadOfCrashing() {
        peer.failing = setOf("prompt.submit")
        show()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        compose.onNodeWithTag("composer").performTextInput("hello")
        compose.onNodeWithContentDescription("Send").performClick()

        waitForText("Couldn't send: socket closed")
    }

    @Test
    fun aFailedOpenShowsAMessageAndRetriesWhenReconnected() {
        peer.failing = setOf("session.resume")
        show()
        waitForText("Couldn't open the chat: socket closed")

        peer.failing = emptySet()
        peer.state.value = ConnectionState.Connecting
        compose.waitForIdle()
        peer.state.value = ConnectionState.Ready
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
    }

    @Test
    fun sendStaysDisabledUntilTheChatIsOpen() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        peer.openGate = gate
        show()
        compose.onNodeWithTag("composer").performTextInput("hello")
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.resume" } }

        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        gate.complete(Unit)
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test
    fun aFailedOpenOffersRetryWhileConnected() {
        peer.failing = setOf("session.resume")
        show()
        waitForText("Couldn't open this chat")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        peer.failing = emptySet()
        compose.onNodeWithText("Retry").performClick()

        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn't open this chat").fetchSemanticsNodes().isEmpty() }
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun showOpenChat(running: Boolean = false) {
        peer.resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":$running,"open_requests":[]}"""
        show()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.events.since" } }
        compose.waitForIdle()
    }

    /** Whether [button] sits beside the message field, in one row with it, rather than below it. */
    private fun assertBesideTheField(button: SemanticsNodeInteraction) {
        val field = compose.onNodeWithTag("composer").bounds()
        val bounds = button.bounds()
        assertTrue("button $bounds is right of the field $field", bounds.left >= field.right)
        assertTrue("button $bounds is level with the field $field", bounds.center.y > field.top && bounds.center.y < field.bottom)
    }

    private fun assertBelowTheField(button: SemanticsNodeInteraction) {
        val field = compose.onNodeWithTag("composer").bounds()
        assertTrue("button ${button.bounds()} is below the field $field", button.bounds().top >= field.bottom)
    }

    private val keyboardVisible = androidx.compose.runtime.mutableStateOf(false)

    private fun clearFocus() {
        compose.runOnIdle { focusManager.clearFocus() }
        compose.waitForIdle()
    }

    @Test
    fun anEmptyUnfocusedComposerIsOnePillWithSendBesideTheField() {
        showOpenChat()

        compose.onNodeWithText("Message Hermes").assertIsDisplayed()
        assertBesideTheField(compose.onNodeWithContentDescription("Send"))
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun aRunningTurnShowsStopInThePill() {
        showOpenChat(running = true)

        assertBesideTheField(compose.onNodeWithContentDescription("Stop"))
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
    }

    @Test
    fun focusingTheComposerMovesSendToARowBelowTheField() {
        showOpenChat()

        compose.onNodeWithTag("composer").requestFocus()
        compose.waitForIdle()
        assertBelowTheField(compose.onNodeWithContentDescription("Send"))
        compose.onNodeWithText("Message Hermes").assertIsDisplayed()

        clearFocus()
        assertBesideTheField(compose.onNodeWithContentDescription("Send"))
    }

    @Test
    fun hidingTheKeyboardCollapsesAnEmptyComposer() {
        showOpenChat()
        compose.onNodeWithTag("composer").requestFocus()
        compose.runOnIdle { keyboardVisible.value = true }
        compose.waitForIdle()
        assertBelowTheField(compose.onNodeWithContentDescription("Send"))

        // Back or the keyboard's hide key closes the keyboard but leaves the field focused.
        compose.runOnIdle { keyboardVisible.value = false }
        compose.waitForIdle()

        assertBesideTheField(compose.onNodeWithContentDescription("Send"))
    }

    @Test
    fun hidingTheKeyboardKeepsAComposerWithTextExpanded() {
        showOpenChat()
        compose.onNodeWithTag("composer").performTextInput("hello")
        compose.runOnIdle { keyboardVisible.value = true }
        compose.waitForIdle()

        compose.runOnIdle { keyboardVisible.value = false }
        compose.waitForIdle()

        assertBelowTheField(compose.onNodeWithContentDescription("Send"))
    }

    @Test
    fun aComposerWithTextStaysExpandedWithoutFocus() {
        showOpenChat()
        compose.onNodeWithTag("composer").performTextInput("hello")

        clearFocus()

        assertBelowTheField(compose.onNodeWithContentDescription("Send"))
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test
    fun anExpandedComposerShowsStopInItsActionRowWhileRunning() {
        showOpenChat(running = true)

        compose.onNodeWithTag("composer").requestFocus()
        compose.waitForIdle()

        assertBelowTheField(compose.onNodeWithContentDescription("Stop"))
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.waitUntil(10_000) { peer.calls.any { it.first == "session.interrupt" } }
    }

    @Test
    fun theLastMessageIsNotCoveredByTheComposerAtTheEnd() {
        showLongChat(6)
        val last = compose.onNode(hasText("Reply 6 paragraph 6", substring = true))
        assertTrue("${last.bounds()} ends above the composer ${compose.onNodeWithTag("composer-box").bounds()}", last.bounds().bottom <= compose.onNodeWithTag("composer-box").bounds().top)

        compose.onNodeWithTag("composer").performTextInput("one\ntwo\nthree")
        compose.waitForIdle()

        assertTrue("${last.bounds()} ends above the grown composer ${compose.onNodeWithTag("composer-box").bounds()}", last.bounds().bottom <= compose.onNodeWithTag("composer-box").bounds().top)
    }

    @Test
    fun theMessagesScrollUnderneathTheComposer() {
        showLongChat(6)

        assertTrue(compose.onNodeWithTag("messages").bounds().bottom > compose.onNodeWithTag("composer-box").bounds().top)
    }

    @Test
    fun scrollToEndIsHiddenAtTheEndAndShownAfterScrollingUp() {
        showLongChat(6)
        compose.onNodeWithContentDescription("Scroll to end").assertDoesNotExist()

        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()

        val button = compose.onNodeWithContentDescription("Scroll to end")
        button.assertIsDisplayed()
        val composer = compose.onNodeWithTag("composer-box").bounds()
        assertTrue("${button.bounds()} is just above the composer $composer", button.bounds().bottom <= composer.top && button.bounds().bottom > composer.top - 48f)
        assertEquals("centred", composer.center.x, button.bounds().center.x, 1f)

        compose.onNodeWithTag("messages").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Scroll to end").assertDoesNotExist()
    }

    @Test
    fun scrollToEndGoesToTheEndAndFollowsItAgain() {
        showLongChat(6)
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        peer.event("message.start")
        peer.event("message.delta", textPayload(longReply(1, 3)))
        compose.waitForIdle()
        compose.onNode(hasText("Reply 7 paragraph 3 says", substring = true)).assertDoesNotExist()

        compose.onNodeWithContentDescription("Scroll to end").performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Reply 7 paragraph 3 says", substring = true)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Scroll to end").assertDoesNotExist()

        peer.event("message.delta", textPayload("\n\n" + longReply(4, 30)))
        compose.waitForIdle()
        compose.onNode(hasText("Reply 7 paragraph 30 says", substring = true)).assertIsDisplayed()
    }

    @Test
    fun scrollToEndScrollsVisiblyRatherThanJumping() {
        showLongChat(6)
        compose.onNodeWithTag("messages").performTouchInput { swipeDown(durationMillis = 100) }
        compose.onNodeWithTag("messages").performTouchInput { swipeDown(durationMillis = 100) }
        compose.waitForIdle()
        val endText = hasText("Reply 6 paragraph 6 says", substring = true)
        compose.onNode(endText).assertDoesNotExist()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Scroll to end").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // Two frames in, the list is on its way: the end is not reached in one jump.
        val midway = compose.onAllNodes(endText).fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
        val pill = compose.onNodeWithTag("composer-box").bounds()
        assertTrue("still moving after two frames (end text at $midway, composer top ${pill.top})", midway == null || midway > pill.top)

        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNode(endText).assertIsDisplayed()
        compose.onNodeWithContentDescription("Scroll to end").assertDoesNotExist()
    }

    @Test
    fun aChatScrolledAwayFromItsEndStaysThereWhenTheScreenIsRecreated() {
        val restoration = StateRestorationTester(compose)
        showLongChat(6, restoration)
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val (paragraph, top) = firstParagraphShown()

        // What a rotation does to the screen's state.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(paragraph, top, compose.onNodeWithText(paragraph, substring = true).fetchSemanticsNode().positionInRoot.y, 0.5f)
        compose.onNodeWithContentDescription("Scroll to end").assertIsDisplayed()
    }

    @Test
    fun theScrollToEndButtonIsBigEnoughToTap() {
        showLongChat(6)
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()

        val touch = compose.onNodeWithContentDescription("Scroll to end").fetchSemanticsNode().touchBoundsInRoot
        val minimum = with(compose.density) { 48.dp.toPx() }
        assertTrue("touch area ${touch.width} x ${touch.height} px, at least $minimum px", touch.width >= minimum && touch.height >= minimum)
    }
}
