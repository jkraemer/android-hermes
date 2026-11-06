package net.jkraemer.hermes.core.chat

import kotlinx.serialization.json.JsonObject
import net.jkraemer.hermes.core.rpc.RpcFrame
import java.time.Instant

/** Used when neither the request nor the server's config says how long Hermes waits for an answer. */
const val APPROVAL_TIMEOUT_SECONDS = 300L

enum class ApprovalChoice(val wire: String) { ONCE("once"), SESSION("session"), ALWAYS("always"), DENY("deny") }

sealed interface CardStatus {
    data object Open : CardStatus
    data class Answered(val label: String) : CardStatus
    data object Expired : CardStatus
    data class Cancelled(val reason: String) : CardStatus
}

data class ToolCall(val toolId: String, val name: String, val summary: String?, val done: Boolean, val failed: Boolean)

data class ClarifyQuestion(val qid: String, val question: String, val choices: List<String>, val multiSelect: Boolean)

sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val markdown: String, val streaming: Boolean) : ChatItem

    /** Consecutive tool calls share one item. */
    data class Tools(val calls: List<ToolCall>) : ChatItem

    /**
     * [requestId] is the id of the server request (`srq-…`): the answer goes back as a response
     * to that id, and re-delivered requests are deduplicated on it. [approvalId] is Hermes' own
     * `params.request_id`, which is what `approval.cancelled` refers to.
     */
    data class Approval(
        val requestId: String,
        val approvalId: String?,
        val command: String,
        val description: String?,
        val choices: List<ApprovalChoice>,
        val deadline: Instant,
        val status: CardStatus,
    ) : ChatItem

    /** [requestId] is the id of the server request (`srq-…`) the answer is sent back to. */
    data class Clarify(val requestId: String, val questions: List<ClarifyQuestion>, val status: CardStatus) : ChatItem

    data class Error(val message: String) : ChatItem
}

data class ChatState(
    val liveSessionId: String? = null,
    val storedSessionId: String? = null,
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val lastSeq: Long = 0,
)

sealed interface ChatInput {
    /** The messages of a `session.resume` / `session.create` result. */
    data class Transcript(val messages: List<JsonObject>, val running: Boolean) : ChatInput
    data class UserSent(val text: String) : ChatInput
    data class Event(val event: RpcFrame.Event) : ChatInput
    data class Request(
        val request: RpcFrame.ServerRequest,
        val receivedAt: Instant,
        /** How long Hermes waits for an approval whose request does not say. */
        val approvalTimeoutSeconds: Long = APPROVAL_TIMEOUT_SECONDS,
    ) : ChatInput
    data class Answered(val requestId: String, val label: String) : ChatInput

    /** The ids of the server requests Hermes reports as still unanswered. */
    data class OpenRequests(val ids: Set<String>) : ChatInput
}
