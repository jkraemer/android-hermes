package net.jkraemer.hermes.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.jkraemer.hermes.core.chat.ApprovalChoice
import net.jkraemer.hermes.core.chat.CardStatus
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.chat.ChatState
import net.jkraemer.hermes.core.chat.RpcPeer
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.settings.SeenStore
import java.time.Instant

const val NEW_CHAT_TITLE = "New chat"

/**
 * One opened chat. [storedSessionId] null starts a new one. Opens the session once the
 * connection is ready (and again after a failed open, when it next becomes ready), and keeps
 * [needsAnswer] and [seenStore] current for the session while it lives.
 */
class ChatViewModel(
    private val peer: RpcPeer,
    storedSessionId: String?,
    initialTitle: String?,
    private val needsAnswer: MutableStateFlow<Set<String>>,
    private val seenStore: SeenStore,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {
    private val controller = ChatController(peer, viewModelScope, clock)

    val chat: StateFlow<ChatState> = controller.state
    val connection: StateFlow<ConnectionState> = peer.state

    private val _title = MutableStateFlow(initialTitle ?: NEW_CHAT_TITLE)
    val title: StateFlow<String> = _title.asStateFlow()

    /** A failure to show once; cleared by [messageShown]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The stored id this chat has needsAnswer registered under. */
    private var registeredId: String? = null

    init {
        viewModelScope.launch { openWhenReady(storedSessionId) }
        viewModelScope.launch { chat.collect(::onChatChanged) }
        viewModelScope.launch {
            chat.map { it.running }.distinctUntilChanged().collect { running ->
                // A reply finished: the session has changed since it was last seen.
                if (!running) markOpened()
            }
        }
        viewModelScope.launch { peer.events.collect { event -> if (event.type in TITLE_EVENTS) onTitleEvent(event.type, event.payload) } }
    }

    private suspend fun openWhenReady(storedSessionId: String?) {
        var opened = false
        peer.state.collectLatest { connection ->
            if (connection != ConnectionState.Ready || opened) return@collectLatest
            guarded("Couldn't open the chat") {
                if (storedSessionId != null) controller.open(storedSessionId) else controller.startNew()
                opened = true
            }
        }
    }

    private fun onChatChanged(state: ChatState) {
        val needsAnswerNow = state.items.any { it is ChatItem.Approval && it.status == CardStatus.Open || it is ChatItem.Clarify && it.status == CardStatus.Open }
        val id = state.storedSessionId
        if (id != registeredId) {
            registeredId?.let { old -> needsAnswer.update { it - old } }
            registeredId = id
            if (id != null) seenStore.markOpened(id, clock())
        }
        id?.let { stored -> needsAnswer.update { if (needsAnswerNow) it + stored else it - stored } }
    }

    private fun markOpened() {
        chat.value.storedSessionId?.let { seenStore.markOpened(it, clock()) }
    }

    /** `session.title` names the session by its stored id; `session.info` carries it as `title` next to `stored_session_id`. */
    private fun onTitleEvent(type: String, payload: JsonObject) {
        val state = chat.value
        val sessionKey = payload.string(if (type == "session.title") "session_id" else "stored_session_id")
        val title = payload.string("title")?.takeIf { it.isNotBlank() }
        if (title != null && sessionKey != null && sessionKey == state.storedSessionId) _title.value = title
    }

    fun send(text: String) = launchGuarded("Couldn't send") { controller.send(text) }

    fun answerApproval(requestId: String, choice: ApprovalChoice) =
        launchGuarded("Couldn't answer") { controller.answerApproval(requestId, choice) }

    fun answerClarify(requestId: String, answers: Map<String, List<String>>) =
        launchGuarded("Couldn't answer") { controller.answerClarify(requestId, answers) }

    fun interrupt() = launchGuarded("Couldn't stop") { controller.interrupt() }

    fun messageShown() {
        _message.value = null
    }

    private fun launchGuarded(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch { guarded(failure, block) }
    }

    // The controller reports its failures by throwing; none of them may take the app down.
    private suspend fun guarded(failure: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = e.message?.let { "$failure: $it" } ?: failure
        }
    }

    /** Stops the controller and takes the chat out of [needsAnswer]; safe to call again. */
    fun close() {
        registeredId?.let { id -> needsAnswer.update { it - id } }
        registeredId = null
        controller.close()
    }

    override fun onCleared() = close()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        val TITLE_EVENTS = setOf("session.title", "session.info")
    }
}
