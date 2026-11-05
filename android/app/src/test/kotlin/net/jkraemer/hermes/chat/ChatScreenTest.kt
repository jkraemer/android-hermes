package net.jkraemer.hermes.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

/** A Hermes connection with scripted answers; events and server requests are pushed by the test. */
private class FakePeer : RpcPeer {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
    override val events = MutableSharedFlow<RpcFrame.Event>(extraBufferCapacity = 64)
    override val serverRequests = MutableSharedFlow<RpcFrame.ServerRequest>(extraBufferCapacity = 64)

    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val answers = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    var failing = emptySet<String>()
    var resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":false,"open_requests":[]}"""

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        calls += method to params
        if (method in failing) throw java.io.IOException("socket closed")
        return Json.parseToJsonElement(
            when (method) {
                "session.resume", "session.create" -> resume
                "session.events.since" -> """{"events":[],"latest_seq":0,"truncated":false,"epoch":"e1","open_requests":[]}"""
                else -> "{}"
            },
        )
    }

    override suspend fun answer(requestId: String, result: JsonObject) {
        answers += requestId to result
    }

    fun event(type: String, payload: String = "{}") {
        events.tryEmit(RpcFrame.Event(type, "live1", null, obj(payload)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    private val peer = FakePeer()
    private val needsAnswer = MutableStateFlow(emptySet<String>())
    private var reconnects = 0
    private var backs = 0
    private val storedIds = mutableListOf<String>()
    private lateinit var viewModel: ChatViewModel
    private lateinit var seenStore: SeenStore

    private fun show(storedSessionId: String? = "stored1", title: String? = "Morning briefing") {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hermes-test", Context.MODE_PRIVATE)
        seenStore = SeenStore(prefs)
        viewModel = ChatViewModel(peer, storedSessionId, title, needsAnswer, seenStore) { NOW }
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    viewModel,
                    onBack = { backs++ },
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

        compose.onNodeWithText("Send answers").assertIsNotEnabled()
        compose.onNodeWithText("red").performClick()
        compose.onNodeWithText("Send answers").assertIsNotEnabled()
        compose.onNodeWithText("a").performClick()
        compose.onNodeWithText("Send answers").assertIsEnabled()

        compose.onNodeWithText("Send answers").performClick()
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
        compose.onNodeWithText("Send answers").assertIsNotEnabled()
        compose.onNodeWithTag("other-q1").performTextInput("green")
        compose.onNodeWithText("Send answers").assertIsEnabled()
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
    fun backButtonLeaves() {
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
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
}
