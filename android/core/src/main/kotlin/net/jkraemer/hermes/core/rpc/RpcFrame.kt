package net.jkraemer.hermes.core.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class RpcError(val code: Int, val message: String)

class RpcException(val error: RpcError) : Exception(error.message)

sealed interface RpcFrame {
    data class Response(val id: Long, val result: JsonElement?, val error: RpcError?) : RpcFrame

    /** [payload] is an empty object when the server sent none. */
    data class Event(val type: String, val sessionId: String?, val seq: Long?, val payload: JsonObject) : RpcFrame

    data class ServerRequest(val id: String, val method: String, val params: JsonObject) : RpcFrame
}

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Returns null for anything that is not a frame this client understands; never throws. */
fun parseFrame(line: String): RpcFrame? {
    val root = try {
        Json.parseToJsonElement(line) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    val id = root["id"] as? JsonPrimitive
    val method = root["method"].asString()
    val params = root["params"] as? JsonObject
    return when {
        method == "event" -> {
            if (params == null) return null
            val type = params["type"].asString() ?: return null
            RpcFrame.Event(
                type = type,
                // Hermes sends "" for global broadcasts such as approval.cancelled.
                sessionId = params["session_id"].asString()?.takeIf { it.isNotEmpty() },
                seq = (params["seq"] as? JsonPrimitive)?.longOrNull,
                payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
        method != null -> {
            val requestId = id?.takeIf { it.isString }?.content ?: return null
            RpcFrame.ServerRequest(requestId, method, params ?: JsonObject(emptyMap()))
        }
        id != null && !id.isString -> {
            val responseId = id.longOrNull ?: return null
            val error = (root["error"] as? JsonObject)?.let {
                RpcError(
                    (it["code"] as? JsonPrimitive)?.intOrNull ?: return null,
                    it["message"].asString().orEmpty(),
                )
            }
            RpcFrame.Response(responseId, root["result"], error)
        }
        else -> null
    }
}
