package net.jkraemer.hermes.core.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

internal class FakePeer : RpcPeer {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
    override val events = MutableSharedFlow<RpcFrame.Event>(extraBufferCapacity = 8192)
    override val serverRequests = MutableSharedFlow<RpcFrame.ServerRequest>(extraBufferCapacity = 64)

    val calls: MutableList<Pair<String, JsonObject>> = CopyOnWriteArrayList()

    /** The `config.get` calls, kept apart because the controller makes them on every Ready. */
    val configCalls: MutableList<JsonObject> = CopyOnWriteArrayList()
    val answers = mutableListOf<Pair<String, JsonObject>>()
    private val handlers = ConcurrentHashMap<String, suspend (JsonObject) -> JsonElement>()

    init {
        // What an idle live session answers; tests override the ones they care about.
        on("session.events.since", """{"events":[],"latest_seq":0,"truncated":false,"epoch":"e1","open_requests":[]}""")
        on("session.activate", activateResult())
    }

    fun on(method: String, handler: suspend (JsonObject) -> JsonElement) {
        handlers[method] = handler
    }

    fun on(method: String, result: String) = on(method) { Json.parseToJsonElement(result) }

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        if (method == "config.get") configCalls += params else calls += method to params
        return (handlers[method] ?: error("unscripted call $method"))(params)
    }

    override suspend fun answer(requestId: String, result: JsonObject) {
        answers += requestId to result
    }

    fun event(type: String, payload: String = "{}", seq: Long? = null, session: String = "live1") =
        events.tryEmit(RpcFrame.Event(type, session, seq, obj(payload)))
}

/** Hermes leaves `open_requests` out of a `session.activate` result when no request is open. */
internal fun activateResult(openRequests: String? = null) =
    """{"session_id":"live1","messages":[],"running":false${openRequests?.let { ""","open_requests":$it""" }.orEmpty()}}"""
