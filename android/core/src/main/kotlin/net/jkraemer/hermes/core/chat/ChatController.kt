package net.jkraemer.hermes.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import net.jkraemer.hermes.core.rpc.RpcException
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcFrame
import java.time.Instant

/** The part of the RPC connection the chat needs; lets tests drive the controller without a socket. */
interface RpcPeer {
    val state: StateFlow<ConnectionState>
    val events: SharedFlow<RpcFrame.Event>
    val serverRequests: SharedFlow<RpcFrame.ServerRequest>
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonElement
    suspend fun answer(requestId: String, result: JsonObject)
}

class AnswerRefusedException(requestId: String) : Exception("Request $requestId is not open")

/**
 * One chat session: loads or creates it, keeps [state] up to date from the connection's events
 * and server requests, and sends prompts and answers. It subscribes on construction because the
 * connection does not replay events or requests to late collectors.
 */
class ChatController(private val peer: RpcPeer, scope: CoroutineScope, private val clock: () -> Instant = Instant::now) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    // Live events that arrive while a resync is replaying are held back so the replay, which
    // holds older sequence numbers, is not dropped as stale.
    private val bufferLock = Any()
    private var heldEvents: MutableList<RpcFrame.Event>? = null

    private val collectors: List<Job>
    private var resync: Job? = null

    init {
        collectors = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { peer.events.collect(::onEvent) },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                peer.serverRequests.collect { request ->
                    if (_state.value.liveSessionId != null) feed(ChatInput.Request(request, clock()))
                }
            },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var dropped = false
                peer.state.collect { connection ->
                    if (connection == ConnectionState.Connecting) {
                        dropped = true
                        resync?.cancel()
                    } else if (connection == ConnectionState.Ready && dropped) {
                        dropped = false
                        resync = scope.launch { resyncAfterDrop() }
                    }
                }
            },
        )
    }

    suspend fun open(storedSessionId: String) {
        adopt(peer.call("session.resume", buildJsonObject { put("session_id", storedSessionId) }).jsonObject, storedSessionId)
    }

    suspend fun startNew() {
        adopt(peer.call("session.create").jsonObject, null)
    }

    suspend fun send(text: String) {
        val live = requireLiveSession()
        feed(ChatInput.UserSent(text))
        peer.call("prompt.submit", buildJsonObject {
            put("session_id", live)
            put("text", text)
        })
    }

    suspend fun answerApproval(requestId: String, choice: ApprovalChoice) {
        val open = _state.value.items.any {
            it is ChatItem.Approval && it.requestId == requestId && it.status == CardStatus.Open
        }
        if (!open) throw AnswerRefusedException(requestId)
        peer.answer(requestId, buildJsonObject { put("choice", choice.wire) })
        feed(ChatInput.Answered(requestId, choice.label))
    }

    suspend fun answerClarify(requestId: String, answers: Map<String, List<String>>) {
        val card = _state.value.items.firstOrNull {
            it is ChatItem.Clarify && it.requestId == requestId && it.status == CardStatus.Open
        } as ChatItem.Clarify? ?: throw AnswerRefusedException(requestId)
        val multiSelect = card.questions.associate { it.qid to it.multiSelect }
        peer.answer(requestId, buildJsonObject {
            put("answers", buildJsonObject {
                for ((qid, values) in answers) {
                    val multi = multiSelect[qid] ?: throw IllegalArgumentException("Unknown question $qid")
                    if (multi) put(qid, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    else put(qid, values.firstOrNull().orEmpty())
                }
            })
        })
        feed(ChatInput.Answered(requestId, "Answered"))
    }

    suspend fun interrupt() {
        peer.call("session.interrupt", buildJsonObject { put("session_id", requireLiveSession()) })
    }

    fun close() {
        resync?.cancel()
        collectors.forEach { it.cancel() }
    }

    private fun requireLiveSession(): String =
        checkNotNull(_state.value.liveSessionId) { "No chat session is open" }

    private fun feed(input: ChatInput) {
        _state.update { reduce(it, input) }
    }

    private fun onEvent(event: RpcFrame.Event) {
        synchronized(bufferLock) {
            heldEvents?.let { it += event; return }
        }
        if (_state.value.liveSessionId != null) feed(ChatInput.Event(event))
    }

    /** Replaces the chat with the session in a `session.resume` / `session.create` result. */
    private fun adopt(result: JsonObject, requestedStoredId: String?) {
        val messages = (result["messages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val running = (result["running"] as? JsonPrimitive)?.booleanOrNull ?: false
        _state.value = reduce(
            ChatState(
                liveSessionId = result.string("session_id"),
                storedSessionId = result.string("stored_session_id") ?: requestedStoredId,
            ),
            ChatInput.Transcript(messages, running),
        )
        feedOpenRequests(result)
    }

    // Only open_requests are replayed: they carry the srq id an answer must target.
    // session.resume's pending_approval cannot be answered by a response frame.
    private fun feedOpenRequests(result: JsonObject) {
        for (element in (result["open_requests"] as? JsonArray).orEmpty()) {
            val request = element as? JsonObject ?: continue
            feed(ChatInput.Request(
                RpcFrame.ServerRequest(
                    id = request.string("id") ?: continue,
                    method = request.string("method") ?: continue,
                    params = request["params"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
                clock(),
            ))
        }
    }

    /** Fetches what was missed while the socket was down; falls back to a full resume when the server's replay window has passed. */
    private suspend fun resyncAfterDrop() {
        val live = _state.value.liveSessionId ?: return
        val stored = _state.value.storedSessionId
        synchronized(bufferLock) { heldEvents = mutableListOf() }
        try {
            val result = try {
                peer.call("session.events.since", buildJsonObject {
                    put("session_id", live)
                    put("last_seen", _state.value.lastSeq)
                }).jsonObject
            } catch (e: IOException) {
                return // dropped again; the next return to Ready resyncs
            } catch (e: RpcException) {
                return
            }
            if ((result["truncated"] as? JsonPrimitive)?.booleanOrNull == true && stored != null) {
                synchronized(bufferLock) { heldEvents = null }
                open(stored)
                return
            }
            for (element in (result["events"] as? JsonArray).orEmpty()) {
                (element as? JsonObject)?.let(::eventFrom)?.let { feed(ChatInput.Event(it)) }
            }
            feedOpenRequests(result)
        } finally {
            val held = synchronized(bufferLock) { heldEvents.also { heldEvents = null } }
            held?.forEach { feed(ChatInput.Event(it)) }
        }
    }

    /** An `events[]` entry is the `params` object of an event frame. */
    private fun eventFrom(params: JsonObject): RpcFrame.Event? = RpcFrame.Event(
        type = params.string("type") ?: return null,
        sessionId = params.string("session_id"),
        seq = (params["seq"] as? JsonPrimitive)?.longOrNull,
        payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap()),
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private val ApprovalChoice.label: String
        get() = when (this) {
            ApprovalChoice.ONCE -> "Allowed once"
            ApprovalChoice.SESSION -> "Allowed for this session"
            ApprovalChoice.ALWAYS -> "Always allowed"
            ApprovalChoice.DENY -> "Denied"
        }
}
