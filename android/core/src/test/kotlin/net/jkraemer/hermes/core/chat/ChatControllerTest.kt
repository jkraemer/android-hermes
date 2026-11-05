package net.jkraemer.hermes.core.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcError
import net.jkraemer.hermes.core.rpc.RpcException
import net.jkraemer.hermes.core.rpc.RpcFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

private class FakePeer : RpcPeer {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
    override val events = MutableSharedFlow<RpcFrame.Event>(extraBufferCapacity = 8192)
    override val serverRequests = MutableSharedFlow<RpcFrame.ServerRequest>(extraBufferCapacity = 64)

    val calls: MutableList<Pair<String, JsonObject>> = CopyOnWriteArrayList()
    val answers = mutableListOf<Pair<String, JsonObject>>()
    private val handlers = ConcurrentHashMap<String, suspend (JsonObject) -> JsonElement>()

    init {
        // What an idle live session answers; tests override the ones they care about.
        on("session.events.since", """{"events":[],"latest_seq":0,"truncated":false,"epoch":"e1","open_requests":[]}""")
        on("session.activate", """{"session_id":"live1","messages":[],"running":false,"open_requests":[]}""")
    }

    fun on(method: String, handler: suspend (JsonObject) -> JsonElement) {
        handlers[method] = handler
    }

    fun on(method: String, result: String) = on(method) { Json.parseToJsonElement(result) }

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        calls += method to params
        return (handlers[method] ?: error("unscripted call $method"))(params)
    }

    override suspend fun answer(requestId: String, result: JsonObject) {
        answers += requestId to result
    }

    fun event(type: String, payload: String = "{}", seq: Long? = null, session: String = "live1") =
        events.tryEmit(RpcFrame.Event(type, session, seq, obj(payload)))
}

private val NOW = Instant.parse("2026-01-01T00:00:00Z")

private fun resumeResult(
    messages: String = "[]",
    openRequests: String = "[]",
    extra: String = "",
) = """{"session_id":"live1","stored_session_id":"stored1","messages":$messages,"running":false,"open_requests":$openRequests$extra}"""

private const val APPROVAL_REQUEST =
    """{"id":"srq-1","method":"approval","params":{"session_id":"live1","request_id":"r1","command":"rm x"}}"""

private const val CLARIFY_REQUEST =
    """{"id":"srq-2","method":"clarify","params":{"session_id":"live1","questions":[
        {"qid":"q1","question":"Colour?","choices":["red","blue"],"multi_select":false},
        {"qid":"q2","question":"Sizes?","choices":["a","b","c"],"multi_select":true}]}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private fun test(block: suspend kotlinx.coroutines.test.TestScope.(FakePeer, ChatController) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val peer = FakePeer()
            block(peer, ChatController(peer, backgroundScope) { NOW })
        }

    @Test fun openFeedsTranscript() = test { peer, chat ->
        peer.on("session.resume", resumeResult("""[{"role":"user","text":"hi"},{"role":"assistant","text":"yo"}]"""))

        chat.open("stored1")

        assertEquals("stored1", peer.calls.first().second["session_id"]!!.jsonPrimitive.content)
        assertEquals("live1", chat.state.value.liveSessionId)
        assertEquals("stored1", chat.state.value.storedSessionId)
        assertEquals(listOf(ChatItem.User("hi"), ChatItem.Assistant("yo", streaming = false)), chat.state.value.items)
    }

    @Test fun openReplaysPendingApproval() = test { peer, chat ->
        // pending_approval cannot be answered by a response frame; only open_requests are replayed.
        peer.on("session.resume", resumeResult(
            openRequests = "[$APPROVAL_REQUEST]",
            extra = ""","pending_approval":{"request_id":"r1","command":"rm x"}""",
        ))

        chat.open("stored1")

        val card = chat.state.value.items.single() as ChatItem.Approval
        assertEquals("srq-1", card.requestId)
        assertEquals("r1", card.approvalId)
        assertEquals("rm x", card.command)
        assertEquals(CardStatus.Open, card.status)
        assertEquals(NOW.plusSeconds(APPROVAL_TIMEOUT_SECONDS), card.deadline)
    }

    @Test fun sendAddsUserItemThenSubmits() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        var itemsAtSubmit: List<ChatItem>? = null
        peer.on("prompt.submit") { itemsAtSubmit = chat.state.value.items; obj("{}") }
        chat.startNew()

        chat.send("hello")

        assertEquals(listOf<ChatItem>(ChatItem.User("hello")), itemsAtSubmit)
        assertEquals("prompt.submit", peer.calls.last().first)
        assertEquals(obj("""{"session_id":"live1","text":"hello"}"""), peer.calls.last().second)
        assertTrue(chat.state.value.running)
    }

    @Test fun answerApprovalSendsWireChoice() = test { peer, chat ->
        peer.on("session.resume", resumeResult(openRequests = "[$APPROVAL_REQUEST]"))
        chat.open("stored1")

        chat.answerApproval("srq-1", ApprovalChoice.SESSION)

        assertEquals(listOf("srq-1" to obj("""{"choice":"session"}""")), peer.answers)
        assertEquals(CardStatus.Answered("Allowed for this session"), (chat.state.value.items.single() as ChatItem.Approval).status)
    }

    @Test fun answerApprovalLabels() = test { peer, chat ->
        peer.on("session.resume", resumeResult(openRequests = "[$APPROVAL_REQUEST]"))
        val labels = mapOf(
            ApprovalChoice.ONCE to "Allowed once",
            ApprovalChoice.SESSION to "Allowed for this session",
            ApprovalChoice.ALWAYS to "Always allowed",
            ApprovalChoice.DENY to "Denied",
        )
        for ((choice, label) in labels) {
            chat.open("stored1")
            chat.answerApproval("srq-1", choice)
            assertEquals(CardStatus.Answered(label), (chat.state.value.items.single() as ChatItem.Approval).status)
        }
    }

    @Test fun answerAfterCancelIsRefused() = test { peer, chat ->
        peer.on("session.resume", resumeResult(openRequests = "[$APPROVAL_REQUEST]"))
        chat.open("stored1")
        peer.event("request.cancel", """{"id":"srq-1","method":"approval","reason":"gone"}""", seq = 1)

        try {
            chat.answerApproval("srq-1", ApprovalChoice.ONCE)
            fail("expected AnswerRefusedException")
        } catch (_: AnswerRefusedException) {
        }
        assertTrue(peer.answers.isEmpty())
    }

    @Test fun answerTwiceAndUnknownCardAreRefused() = test { peer, chat ->
        peer.on("session.resume", resumeResult(openRequests = "[$APPROVAL_REQUEST]"))
        chat.open("stored1")
        chat.answerApproval("srq-1", ApprovalChoice.DENY)

        for (id in listOf("srq-1", "srq-nope")) {
            try {
                chat.answerApproval(id, ApprovalChoice.ONCE)
                fail("expected AnswerRefusedException for $id")
            } catch (_: AnswerRefusedException) {
            }
        }
        assertEquals(1, peer.answers.size)
    }

    @Test fun answerClarifySingleSelectSendsString() = test { peer, chat ->
        peer.on("session.resume", resumeResult(openRequests = "[$CLARIFY_REQUEST]"))
        chat.open("stored1")

        chat.answerClarify("srq-2", mapOf("q1" to listOf("red"), "q2" to listOf("a", "c")))

        assertEquals(listOf("srq-2" to obj("""{"answers":{"q1":"red","q2":["a","c"]}}""")), peer.answers)
        assertEquals(CardStatus.Answered("Answered"), (chat.state.value.items.single() as ChatItem.Clarify).status)
    }

    @Test fun answerClarifyAfterCancelIsRefused() = test { peer, chat ->
        peer.on("session.resume", resumeResult(openRequests = "[$CLARIFY_REQUEST]"))
        chat.open("stored1")
        peer.event("request.cancel", """{"id":"srq-2","method":"clarify","reason":"gone"}""", seq = 1)

        try {
            chat.answerClarify("srq-2", mapOf("q1" to listOf("red")))
            fail("expected AnswerRefusedException")
        } catch (_: AnswerRefusedException) {
        }
        assertTrue(peer.answers.isEmpty())
    }

    @Test fun liveServerRequestBecomesCard() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()

        peer.serverRequests.tryEmit(RpcFrame.ServerRequest("srq-1", "approval", obj("""{"session_id":"live1","request_id":"r1","command":"ls"}""")))

        assertEquals("srq-1", (chat.state.value.items.single() as ChatItem.Approval).requestId)
    }

    @Test fun interruptCallsSessionInterrupt() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        peer.on("session.interrupt", "{}")
        chat.startNew()

        chat.interrupt()

        assertEquals("session.interrupt" to obj("""{"session_id":"live1"}"""), peer.calls.last())
    }

    private fun sinceResult(
        events: String,
        truncated: Boolean = false,
        openRequests: String = "[]",
        latestSeq: Int = 5,
        epoch: String = "e1",
    ) = """{"events":$events,"latest_seq":$latestSeq,"truncated":$truncated,"epoch":"$epoch","open_requests":$openRequests}"""

    private fun delta(seq: Int, text: String) =
        """{"type":"message.delta","session_id":"live1","seq":$seq,"payload":{"text":"$text"}}"""

    private fun dropConnection(peer: FakePeer) {
        peer.state.value = ConnectionState.Connecting
        peer.state.value = ConnectionState.Ready
    }

    @Test fun resyncAfterDropDoesNotDuplicateDeltas() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.event("message.start", seq = 1)
        peer.event("message.delta", """{"text":"a"}""", seq = 2)
        peer.event("message.delta", """{"text":"b"}""", seq = 3)
        peer.on("session.events.since", sinceResult("[${delta(2, "a")},${delta(3, "b")},${delta(4, "c")},${delta(5, "d")}]"))

        dropConnection(peer)

        assertEquals("session.events.since" to obj("""{"session_id":"live1","last_seen":3}"""), peer.calls.last())
        assertEquals(listOf<ChatItem>(ChatItem.Assistant("abcd", streaming = true)), chat.state.value.items)
        assertEquals(5, chat.state.value.lastSeq)
    }

    @Test fun resyncRestoresOpenRequests() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.on("session.events.since", sinceResult("[]", openRequests = "[$APPROVAL_REQUEST]"))

        dropConnection(peer)

        assertEquals("srq-1", (chat.state.value.items.single() as ChatItem.Approval).requestId)
    }

    @Test fun truncatedReplayFallsBackToResume() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.on("session.events.since", sinceResult("[]", truncated = true))
        peer.on("session.resume", resumeResult("""[{"role":"assistant","text":"fresh"}]"""))

        dropConnection(peer)

        assertEquals("session.resume" to obj("""{"session_id":"stored1"}"""), peer.calls.last { it.first == "session.resume" })
        assertEquals(listOf<ChatItem>(ChatItem.Assistant("fresh", streaming = false)), chat.state.value.items)
    }

    @Test fun liveEventsDuringResyncAreAppliedAfterTheReplay() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.event("message.start", seq = 1)
        val release = CompletableDeferred<Unit>()
        peer.on("session.events.since") { release.await(); Json.parseToJsonElement(sinceResult("[${delta(2, "b")}]")) }

        dropConnection(peer)
        peer.event("message.delta", """{"text":"c"}""", seq = 3)
        release.complete(Unit)

        assertEquals(listOf<ChatItem>(ChatItem.Assistant("bc", streaming = true)), chat.state.value.items)
    }

    @Test fun readyWithoutPriorDropDoesNotResync() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()

        peer.state.value = ConnectionState.Ready

        // session.create plus the probe that anchors the replay position; no resync follows.
        assertEquals(listOf("session.create", "session.events.since"), peer.calls.map { it.first })
    }

    @Test fun closeStopsCollecting() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()

        chat.close()
        peer.event("message.start", seq = 1)

        assertTrue(chat.state.value.items.isEmpty())
    }

    private fun callNames(peer: FakePeer) = peer.calls.map { it.first }

    @Test fun dropReattachesWithActivateBeforeReplayingEvents() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.on("session.activate", """{"session_id":"live1","messages":[],"running":true,"open_requests":[$APPROVAL_REQUEST]}""")
        val before = peer.calls.size

        dropConnection(peer)

        assertEquals(listOf("session.activate", "session.events.since"), peer.calls.drop(before).map { it.first })
        assertEquals(obj("""{"session_id":"live1","omit_messages":true}"""), peer.calls[before].second)
        assertEquals("srq-1", (chat.state.value.items.single() as ChatItem.Approval).requestId)
    }

    @Test fun reopenedLiveSessionDropDoesNotReplayOldEvents() = test { peer, chat ->
        // session.resume reuses the live sid, so its ring still holds seqs 1-7 from before the open.
        peer.on("session.resume", resumeResult("""[{"role":"assistant","text":"yo"}]"""))
        peer.on("session.events.since", sinceResult("[]", latestSeq = 7))
        chat.open("stored1")
        peer.on("session.events.since", sinceResult((3..7).joinToString(",", "[", "]") { delta(it, "x") }, latestSeq = 7))

        dropConnection(peer)

        assertEquals(listOf<ChatItem>(ChatItem.Assistant("yo", streaming = false)), chat.state.value.items)
        assertEquals(7, chat.state.value.lastSeq)
    }

    @Test fun serverRestartReopensStoredSession() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.on("session.events.since", sinceResult("[]", epoch = "e2", latestSeq = 0))
        peer.on("session.resume", resumeResult("""[{"role":"assistant","text":"fresh"}]"""))

        dropConnection(peer)

        assertEquals("session.resume" to obj("""{"session_id":"stored1"}"""), peer.calls.last { it.first == "session.resume" })
        assertEquals(listOf<ChatItem>(ChatItem.Assistant("fresh", streaming = false)), chat.state.value.items)
    }

    @Test fun lowerLatestSeqThanSeenReopensStoredSession() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.event("message.start", seq = 1)
        peer.event("message.delta", """{"text":"a"}""", seq = 2)
        peer.on("session.events.since", sinceResult("[]", latestSeq = 0))
        peer.on("session.resume", resumeResult("""[{"role":"assistant","text":"fresh"}]"""))

        dropConnection(peer)

        assertEquals(listOf<ChatItem>(ChatItem.Assistant("fresh", streaming = false)), chat.state.value.items)
    }

    @Test fun activateRefusedReopensStoredSession() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        chat.startNew()
        peer.on("session.activate") { throw RpcException(RpcError(4007, "session not found")) }
        peer.on("session.resume", resumeResult("""[{"role":"assistant","text":"fresh"}]"""))

        dropConnection(peer)

        assertEquals(listOf<ChatItem>(ChatItem.Assistant("fresh", streaming = false)), chat.state.value.items)
        assertEquals("live1", chat.state.value.liveSessionId)
    }

    @Test fun failedSendAddsErrorAndStopsRunning() = test { peer, chat ->
        peer.on("session.create", resumeResult())
        peer.on("prompt.submit") { throw IOException("Connection closed") }
        chat.startNew()

        try {
            chat.send("hello")
            fail("expected the send to fail")
        } catch (_: IOException) {
        }

        assertFalse(chat.state.value.running)
        assertEquals(ChatItem.User("hello"), chat.state.value.items.first())
        assertEquals(ChatItem.Error("Connection closed"), chat.state.value.items.last())
    }

    @Test fun eventsAcrossRepeatedResyncsOnRealThreadsAreAppliedOnceInOrder() = runBlocking {
        // Producer, collector and resync coroutines run on separate threads. A smoke test, not a
        // proof: the window for a lost event is a few instructions wide, so a mutant that flushes
        // outside the lock was not caught in 3 runs. The locking itself is by construction (one
        // lock around every state change and around the hold/flush decision).
        val total = 3000
        val peer = FakePeer()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val chat = ChatController(peer, scope) { NOW }
        peer.on("session.create", resumeResult())
        chat.startNew()
        val emitted = CopyOnWriteArrayList<String>()
        peer.on("session.events.since") { params ->
            val from = params["last_seen"]!!.jsonPrimitive.content.toLong().coerceAtMost(emitted.size.toLong()).toInt()
            val snapshot = emitted.toList()
            Json.parseToJsonElement(sinceResult(
                (from + 1..snapshot.size).joinToString(",", "[", "]") { delta(it, snapshot[it - 1]) },
                latestSeq = snapshot.size,
            ))
        }
        try {
            val producer = launch(Dispatchers.Default) {
                for (i in 1..total) {
                    val char = ('a' + i % 26).toString()
                    emitted += char
                    peer.event("message.delta", """{"text":"$char"}""", seq = i.toLong())
                    if (i % 50 == 0) yield()
                }
            }
            while (producer.isActive) {
                dropConnection(peer)
                delay(1)
            }
            dropConnection(peer)
            val expected = (1..total).joinToString("") { ('a' + it % 26).toString() }
            withTimeout(20_000) {
                while ((chat.state.value.items.lastOrNull() as? ChatItem.Assistant)?.markdown != expected) delay(20)
            }
            assertEquals(1, chat.state.value.items.size)
        } finally {
            chat.close()
            scope.cancel()
        }
    }
}
