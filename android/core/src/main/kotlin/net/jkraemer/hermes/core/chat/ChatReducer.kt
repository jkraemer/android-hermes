package net.jkraemer.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import net.jkraemer.hermes.core.rpc.RpcFrame
import java.time.Instant

fun reduce(state: ChatState, input: ChatInput): ChatState = when (input) {
    is ChatInput.Transcript -> state.copy(items = transcriptItems(input.messages), running = input.running)
    is ChatInput.UserSent -> state.copy(items = state.items + ChatItem.User(input.text), running = true)
    is ChatInput.Event -> reduceEvent(state, input.event)
    is ChatInput.Request -> reduceRequest(state, input.request, input.receivedAt)
    is ChatInput.Answered -> state.updateCard(input.requestId) { CardStatus.Answered(input.label) }
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun transcriptItems(messages: List<JsonObject>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    for (message in messages) {
        val text = message["text"].string() ?: message["content"].string() ?: ""
        when (message["role"].string()) {
            "user" -> items += ChatItem.User(text)
            "assistant" -> items += ChatItem.Assistant(text, streaming = false)
            "tool" -> {
                val call = ToolCall(
                    toolId = message["tool_call_id"].string() ?: "",
                    name = message["name"].string() ?: "",
                    summary = null,
                    done = true,
                    failed = false,
                )
                val last = items.lastOrNull()
                if (last is ChatItem.Tools) items[items.lastIndex] = ChatItem.Tools(last.calls + call)
                else items += ChatItem.Tools(listOf(call))
            }
        }
    }
    return items
}

private fun reduceEvent(state: ChatState, event: RpcFrame.Event): ChatState {
    if (state.liveSessionId != null && event.sessionId != null && event.sessionId != state.liveSessionId) return state
    val seq = event.seq
    if (seq != null && seq <= state.lastSeq) return state
    val applied = applyEvent(state, event)
    return if (seq != null) applied.copy(lastSeq = seq) else applied
}

private fun applyEvent(state: ChatState, event: RpcFrame.Event): ChatState {
    val payload = event.payload
    return when (event.type) {
        "message.start" -> state.copy(items = state.items + ChatItem.Assistant("", streaming = true))
        "message.delta" -> appendDelta(state, payload["text"].string() ?: "")
        "message.complete" -> completeMessage(state, payload)
        "tool.start" -> startTool(state, payload)
        "tool.complete" -> completeTool(state, payload)
        "request.cancel" -> {
            val id = payload["id"].string() ?: return state
            state.updateCard(id) { cancelledStatus(payload["reason"].string()) }
        }
        "approval.cancelled" -> {
            val ids = (payload["request_ids"] as? JsonArray)?.mapNotNull { it.string() }.orEmpty()
            val status = cancelledStatus(payload["reason"].string())
            state.copy(items = state.items.map { item ->
                if (item is ChatItem.Approval && item.approvalId in ids && item.status == CardStatus.Open) item.copy(status = status)
                else item
            })
        }
        else -> state
    }
}

private fun cancelledStatus(reason: String?): CardStatus =
    if (reason == "timeout") CardStatus.Expired else CardStatus.Cancelled(reason ?: "cancelled")

private fun appendDelta(state: ChatState, text: String): ChatState {
    val last = state.items.lastOrNull()
    val items = if (last is ChatItem.Assistant && last.streaming) {
        state.items.dropLast(1) + last.copy(markdown = last.markdown + text)
    } else {
        state.items + ChatItem.Assistant(text, streaming = true)
    }
    return state.copy(items = items)
}

private fun completeMessage(state: ChatState, payload: JsonObject): ChatState {
    val text = payload["text"].string()
    val index = state.items.indexOfLast { it is ChatItem.Assistant && it.streaming }
    var items = state.items
    if (index >= 0) {
        val streaming = items[index] as ChatItem.Assistant
        items = items.toMutableList().also { it[index] = ChatItem.Assistant(text ?: streaming.markdown, streaming = false) }
    } else if (text != null) {
        items = items + ChatItem.Assistant(text, streaming = false)
    }
    payload["error"].string()?.let { items = items + ChatItem.Error(it) }
    return state.copy(items = items, running = false)
}

private fun startTool(state: ChatState, payload: JsonObject): ChatState {
    val call = ToolCall(
        toolId = payload["tool_id"].string() ?: return state,
        name = payload["name"].string() ?: "",
        summary = null,
        done = false,
        failed = false,
    )
    val last = state.items.lastOrNull()
    val items = if (last is ChatItem.Tools) state.items.dropLast(1) + ChatItem.Tools(last.calls + call)
    else state.items + ChatItem.Tools(listOf(call))
    return state.copy(items = items)
}

/** The ToolCompletePayload schema has no error field; Hermes may still flag failures via `error` or `status`. */
private fun isFailure(payload: JsonObject): Boolean {
    val error = payload["error"]
    val flagged = when {
        error == null || error is JsonNull -> false
        error is JsonPrimitive && !error.isString -> error.booleanOrNull == true
        error is JsonPrimitive -> error.content.isNotEmpty()
        else -> true
    }
    return flagged || payload["status"].string() == "error"
}

private fun completeTool(state: ChatState, payload: JsonObject): ChatState {
    val toolId = payload["tool_id"].string() ?: return state
    val summary = payload["summary"].string()
    val failed = isFailure(payload)
    return state.copy(items = state.items.map { item ->
        if (item is ChatItem.Tools && item.calls.any { it.toolId == toolId }) {
            ChatItem.Tools(item.calls.map {
                if (it.toolId == toolId) it.copy(summary = summary, done = true, failed = failed) else it
            })
        } else item
    })
}

private fun reduceRequest(state: ChatState, request: RpcFrame.ServerRequest, receivedAt: Instant): ChatState {
    val params = request.params
    val sessionId = params["session_id"].string()
    if (state.liveSessionId != null && sessionId != null && sessionId != state.liveSessionId) return state
    if (state.items.any { (it is ChatItem.Approval && it.requestId == request.id) || (it is ChatItem.Clarify && it.requestId == request.id) }) return state
    val item = when (request.method) {
        "approval" -> approvalItem(request.id, params, receivedAt)
        "clarify" -> clarifyItem(request.id, params)
        else -> return state
    }
    return state.copy(items = state.items + item)
}

private fun approvalItem(requestId: String, params: JsonObject, receivedAt: Instant): ChatItem.Approval {
    val offered = (params["choices"] as? JsonArray)?.mapNotNull { choice ->
        ApprovalChoice.entries.firstOrNull { it.wire == choice.string() }
    }
    val choices = offered ?: ApprovalChoice.entries.filter { choice ->
        val noSession = params["smart_denied"].boolean() == true || params["allow_session"].boolean() == false
        when (choice) {
            ApprovalChoice.SESSION -> !noSession
            ApprovalChoice.ALWAYS -> !noSession && params["allow_permanent"].boolean() != false
            else -> true
        }
    }
    val timeoutSeconds = (params["timeout_s"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: APPROVAL_TIMEOUT_SECONDS
    return ChatItem.Approval(
        requestId = requestId,
        approvalId = params["request_id"].string(),
        command = params["command"].string() ?: "",
        description = params["description"].string()?.takeIf { it.isNotEmpty() },
        choices = choices,
        deadline = receivedAt.plusSeconds(timeoutSeconds),
        status = CardStatus.Open,
    )
}

private fun clarifyItem(requestId: String, params: JsonObject): ChatItem.Clarify {
    val questions = (params["questions"] as? JsonArray).orEmpty().mapNotNull { element ->
        val question = element as? JsonObject ?: return@mapNotNull null
        ClarifyQuestion(
            qid = question["qid"].string() ?: return@mapNotNull null,
            question = question["question"].string() ?: "",
            choices = (question["choices"] as? JsonArray)?.mapNotNull { it.string() }.orEmpty(),
            multiSelect = question["multi_select"].boolean() ?: false,
        )
    }
    return ChatItem.Clarify(requestId, questions, CardStatus.Open)
}

/** Updates the status of the open card for server request [requestId]; answered or closed cards are kept. */
private fun ChatState.updateCard(requestId: String, status: () -> CardStatus): ChatState =
    copy(items = items.map { item ->
        when {
            item is ChatItem.Approval && item.requestId == requestId && item.status == CardStatus.Open -> item.copy(status = status())
            item is ChatItem.Clarify && item.requestId == requestId && item.status == CardStatus.Open -> item.copy(status = status())
            else -> item
        }
    })
