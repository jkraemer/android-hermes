package net.jkraemer.hermes.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
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

    private fun show(storedSessionId: String? = "stored1", title: String? = "Morning briefing", cron: Boolean = false) {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-test", Context.MODE_PRIVATE)
        seenStore = SeenStore(prefs)
        viewModel = ChatViewModel(peer, storedSessionId, title, needsAnswer, seenStore) { NOW }
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    viewModel,
                    cron = cron,
                    onOpenMenu = { menus++ },
                    onNewChat = { newChats++ },
                    onRetryConnect = { reconnects++ },
                    onStoredSessionId = { storedIds += it },
                    clock = { NOW },
                )
            }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private val promptTranscript = """{"session_id":"live1","stored_session_id":"stored1","messages":[
        {"role":"user","text":"/attention-check"},{"role":"assistant","text":"All quiet."},{"role":"user","text":"thanks"}
        ],"running":false,"open_requests":[]}"""

    private fun reply(n: Int) = (1..6).joinToString("\n\n") { "Reply $n paragraph $it says something **useful** about the question, long enough to wrap over several lines on a phone screen." }

    /** [pairs] questions, each answered by a finished reply of several paragraphs. */
    private fun longTranscript(pairs: Int): String {
        val messages = buildJsonArray {
            for (n in 1..pairs) {
                add(buildJsonObject { put("role", "user"); put("text", "Question $n") })
                add(buildJsonObject { put("role", "assistant"); put("text", reply(n)) })
            }
        }
        return """{"session_id":"live1","stored_session_id":"stored1","messages":$messages,"running":false,"open_requests":[]}"""
    }

    private fun showLongChat(pairs: Int) {
        peer.resume = longTranscript(pairs)
        show()
        compose.waitUntil(10_000) { viewModel.chat.value.items.size == 2 * pairs }
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

    @Test
    fun aFinishedReplyHasItsTextInTheFrameItIsScrolledTo() {
        showLongChat(6)
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

    @Test
    fun cronChatCollapsesTheJobPromptAndExpandsItOnTap() {
        peer.resume = promptTranscript
        show(cron = true)

        waitForText("Scheduled job prompt")
        compose.onNodeWithText("/attention-check").assertDoesNotExist()
        compose.onNodeWithText("All quiet.").assertIsDisplayed()

        compose.onNodeWithText("Scheduled job prompt").performClick()
        compose.onNodeWithText("/attention-check").assertIsDisplayed()

        compose.onNodeWithText("Scheduled job prompt").performClick()
        compose.onNodeWithText("/attention-check").assertDoesNotExist()
    }

    @Test
    fun cronChatShowsLaterUserMessagesAsBubbles() {
        peer.resume = promptTranscript
        show(cron = true)

        waitForText("thanks")
        compose.onAllNodesWithText("Scheduled job prompt").assertCountEquals(1)
    }

    @Test
    fun otherChatShowsTheFirstUserMessageAsABubble() {
        peer.resume = promptTranscript
        show()

        waitForText("/attention-check")
        compose.onNodeWithText("Scheduled job prompt").assertDoesNotExist()
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
}
