package net.jkraemer.hermes.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.jkraemer.hermes.core.chat.RpcPeer
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcError
import net.jkraemer.hermes.core.rpc.RpcException
import net.jkraemer.hermes.core.rpc.RpcFrame
import java.util.concurrent.CopyOnWriteArrayList

internal fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

/** A Hermes connection with scripted answers; events and server requests are pushed by the test. */
internal class FakePeer : RpcPeer {
    override val state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
    override val events = MutableSharedFlow<RpcFrame.Event>(extraBufferCapacity = 64)
    override val serverRequests = MutableSharedFlow<RpcFrame.ServerRequest>(extraBufferCapacity = 64)

    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val answers = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    /** When set, opening a session waits for it. */
    var openGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var failing = emptySet<String>()
    /** Methods Hermes answers with an error response. */
    var refusing = emptySet<String>()
    var resume = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":false,"open_requests":[]}"""

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        calls += method to params
        if (method == "session.resume" || method == "session.create") openGate?.await()
        if (method in failing) throw java.io.IOException("socket closed")
        if (method in refusing) throw RpcException(RpcError(4007, "session not found"))
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
