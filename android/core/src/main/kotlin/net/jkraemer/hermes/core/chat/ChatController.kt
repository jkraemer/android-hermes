package net.jkraemer.hermes.core.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.core.options.SessionOptions
import net.jkraemer.hermes.core.options.sessionOptionsFrom
import net.jkraemer.hermes.core.rpc.RpcException
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcFrame
import net.jkraemer.hermes.core.rpc.userMessage
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
class ChatController(
    private val peer: RpcPeer,
    scope: CoroutineScope,
    private val log: HermesLog = HermesLog.None,
    private val clock: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    // Every state change happens under [lock]. While a resync is replaying, live events are held
    // back so the replay, which holds older sequence numbers, is not dropped as stale.
    private val lock = Any()
    private var heldEvents: MutableList<RpcFrame.Event>? = null

    /** The server process' replay numbering; a different value means the server restarted. */
    private var epoch: String? = null

    /** How long Hermes waits for an approval, as the server's config says; read on every Ready. */
    @Volatile private var approvalTimeoutSeconds = APPROVAL_TIMEOUT_SECONDS

    private val collectors: List<Job>
    private var resync: Job? = null

    init {
        collectors = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { peer.events.collect(::onEvent) },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                peer.serverRequests.collect { request ->
                    synchronized(lock) {
                        if (_state.value.liveSessionId != null) feed(requestInput(request))
                    }
                }
            },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var dropped = false
                peer.state.collect { connection ->
                    if (connection == ConnectionState.Connecting) {
                        dropped = true
                        resync?.cancel()
                    } else if (connection == ConnectionState.Ready) {
                        scope.launch { loadApprovalTimeout() }
                        if (dropped) {
                            dropped = false
                            // A cancelled resync must have released the hold before the next one takes it.
                            val previous = resync
                            resync = scope.launch {
                                previous?.cancelAndJoin()
                                resyncAfterDrop()
                            }
                        }
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

    suspend fun send(text: String) = submit(text)

    /** Submits [text] to the model; the chat shows [shownAs] in a row of [displayKind] instead when it differs. */
    internal suspend fun submit(text: String, shownAs: String = text, displayKind: String? = null) {
        val live = requireLiveSession()
        feed(ChatInput.UserSent(shownAs, displayKind))
        try {
            peer.call("prompt.submit", buildJsonObject {
                put("session_id", live)
                put("text", text)
            })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            feed(ChatInput.Event(RpcFrame.Event("error", live, null, buildJsonObject { put("message", e.userMessage() ?: "Sending failed") })))
            throw e
        }
    }

    suspend fun answerApproval(requestId: String, choice: ApprovalChoice) {
        // Past its deadline Hermes has already denied the request; a late answer would only be misreported.
        val open = _state.value.items.any {
            it is ChatItem.Approval && it.requestId == requestId && it.status == CardStatus.Open && clock().isBefore(it.deadline)
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

    internal fun liveSessionId(): String = requireLiveSession()

    internal suspend fun call(method: String, params: JsonObject, timeoutMs: Long = 30_000): JsonObject =
        peer.call(method, params, timeoutMs).jsonObject

    /** A `config.set` call; the result is Hermes' key/value answer. */
    internal suspend fun configSet(params: JsonObject, timeoutMs: Long = 30_000): JsonObject =
        peer.call("config.set", params, timeoutMs).jsonObject

    private fun requestInput(request: RpcFrame.ServerRequest) =
        ChatInput.Request(request, clock(), approvalTimeoutSeconds)

    /**
     * Hermes does not send the approval timeout with the request; `approvals.timeout` of its config
     * is what it waits for. Only that value is read, the rest of the config is dropped. Anything
     * unusable, a failed call included, means the default.
     */
    private suspend fun loadApprovalTimeout() {
        val config = try {
            peer.call("config.get", buildJsonObject { put("key", "full") }).jsonObject["config"]
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val seconds = (((config as? JsonObject)?.get("approvals") as? JsonObject)?.get("timeout") as? JsonPrimitive)
            ?.takeIf { !it.isString }?.doubleOrNull
        approvalTimeoutSeconds = approvalTimeoutOrNull(seconds) ?: APPROVAL_TIMEOUT_SECONDS
    }

    private fun requireLiveSession(): String =
        checkNotNull(_state.value.liveSessionId) { "No chat session is open" }

    internal fun feed(input: ChatInput) {
        synchronized(lock) { _state.value = reduce(_state.value, input) }
    }

    private fun onEvent(event: RpcFrame.Event) {
        synchronized(lock) {
            heldEvents?.let { it += event; return }
            if (_state.value.liveSessionId != null) feed(ChatInput.Event(event))
        }
    }

    /**
     * Replaces the chat with the session in a `session.resume` / `session.create` result and
     * anchors the replay position to the server's current one: a resumed live session keeps its
     * event ring, whose old events the transcript already contains.
     */
    private suspend fun adopt(result: JsonObject, requestedStoredId: String?) {
        val messages = (result["messages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val running = (result["running"] as? JsonPrimitive)?.booleanOrNull ?: false
        val live = result.string("session_id")
        synchronized(lock) {
            _state.value = reduce(
                ChatState(
                    liveSessionId = live,
                    storedSessionId = result.string("stored_session_id") ?: requestedStoredId,
                    options = optionsIn(result),
                ),
                ChatInput.Transcript(messages, running),
            )
            epoch = null
        }
        // Not settled: the chat was rebuilt from this result, and a request that arrived since must keep its card.
        feedOpenRequests(result)
        if (live == null) return
        val position = peer.call("session.events.since", sinceParams(live, Long.MAX_VALUE)).jsonObject
        synchronized(lock) {
            epoch = position.string("epoch")
            val latest = (position["latest_seq"] as? JsonPrimitive)?.longOrNull ?: 0
            _state.value = _state.value.let { it.copy(lastSeq = maxOf(it.lastSeq, latest)) }
        }
    }

    private fun optionsIn(result: JsonObject): SessionOptions? =
        (result["info"] as? JsonObject)?.let(::sessionOptionsFrom)

    private fun sinceParams(live: String, lastSeen: Long) = buildJsonObject {
        put("session_id", live)
        put("last_seen", lastSeen)
    }

    /** The status of each card the chat shows, by request id; read before a call whose reply settles them. */
    private fun cardStatuses(): Map<String, CardStatus> = _state.value.items.mapNotNull { item ->
        when (item) {
            is ChatItem.Approval -> item.requestId to item.status
            is ChatItem.Clarify -> item.requestId to item.status
            else -> null
        }
    }.toMap()

    /**
     * Adds a card for each request in the result's `open_requests` and returns their ids, or null
     * when the result has no such list. Only open_requests are replayed: they carry the srq id an
     * answer must target. session.resume's pending_approval cannot be answered by a response frame.
     */
    private fun feedOpenRequests(result: JsonObject): Set<String>? {
        val open = result["open_requests"] as? JsonArray ?: return null
        val ids = mutableSetOf<String>()
        for (element in open) {
            val request = element as? JsonObject ?: continue
            val id = request.string("id") ?: continue
            ids += id
            feed(requestInput(
                RpcFrame.ServerRequest(
                    id = id,
                    method = request.string("method") ?: continue,
                    params = request["params"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
            ))
        }
        return ids
    }

    /**
     * The result's `open_requests` is complete, so the cards in [cardsAtSend], those the chat showed
     * when the call was sent, are settled against it.
     */
    private fun settleOpenRequests(result: JsonObject, cardsAtSend: Map<String, CardStatus>) {
        val ids = feedOpenRequests(result) ?: return
        feed(ChatInput.OpenRequests(ids, cardsAtSend))
    }

    /**
     * After a dropped socket Hermes has detached the session and will reap it after a short grace
     * period; only `session.activate` reattaches it. Then the events missed meanwhile are replayed.
     * Anything that shows the server lost track of the session (activate refused, replay window
     * passed, server restarted) falls back to a full `session.resume`.
     */
    private suspend fun resyncAfterDrop() {
        val live: String
        val stored: String?
        synchronized(lock) {
            live = _state.value.liveSessionId ?: return
            stored = _state.value.storedSessionId
            heldEvents = mutableListOf()
        }
        var complete = false
        try {
            try {
                complete = reattach(live, stored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Dropped again or an unusable answer; the next return to Ready resyncs from the same position.
                log.log("resync: incomplete (${e.javaClass.simpleName})")
            }
        } finally {
            // An unfinished resync discards what it held: applying it would move lastSeq past the gap.
            synchronized(lock) {
                val held = heldEvents.also { heldEvents = null }
                if (complete) held?.forEach { feed(ChatInput.Event(it)) }
            }
        }
    }

    /** Returns true when the state is current again. */
    private suspend fun reattach(live: String, stored: String?): Boolean {
        val activated = try {
            peer.call("session.activate", buildJsonObject {
                put("session_id", live)
                put("omit_messages", true)
            }).jsonObject
        } catch (e: RpcException) {
            log.log("resync: session.activate refused (${e.error.code} ${e.error.message}), reopening")
            return reopen(stored)
        }
        log.log("resync: session.activate ok")
        // Only adds cards: a request Hermes cancelled while the app was away is settled by its
        // cancel event in the replay, which carries the reason.
        feedOpenRequests(activated)
        // A session whose agent is not built answers with the profile's default route, not the picks Hermes still holds.
        val lazy = (activated["info"] as? JsonObject)?.get("lazy") == JsonPrimitive(true)
        if (!lazy || _state.value.options == null) optionsIn(activated)?.let { feed(ChatInput.OptionsRead(it)) }
        val cardsAtSend = cardStatuses()
        val missed = peer.call("session.events.since", sinceParams(live, _state.value.lastSeq)).jsonObject
        val restarted = missed.string("epoch") != epoch
        val latest = (missed["latest_seq"] as? JsonPrimitive)?.longOrNull ?: 0
        val truncated = (missed["truncated"] as? JsonPrimitive)?.booleanOrNull == true
        val reopenReason = when {
            restarted -> "server restarted"
            truncated -> "replay window passed"
            latest < _state.value.lastSeq -> "server is behind the transcript"
            else -> null
        }
        if (reopenReason != null) {
            log.log("resync: $reopenReason, reopening")
            return reopen(stored)
        }
        val replayed = (missed["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::eventFrom) }
        replayed.forEach { feed(ChatInput.Event(it)) }
        log.log("resync: replayed ${replayed.size} events")
        settleOpenRequests(missed, cardsAtSend)
        return true
    }

    private suspend fun reopen(stored: String?): Boolean {
        if (stored == null) return false
        return try {
            open(stored)
            log.log("resync: reopened")
            true
        } catch (e: RpcException) {
            log.log("resync: reopen refused (${e.error.code} ${e.error.message})")
            false
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
