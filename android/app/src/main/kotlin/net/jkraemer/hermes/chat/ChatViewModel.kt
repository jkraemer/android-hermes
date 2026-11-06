package net.jkraemer.hermes.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.jkraemer.hermes.core.HermesLog
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
    private val storedSessionId: String?,
    initialTitle: String?,
    private val needsAnswer: MutableStateFlow<Set<String>>,
    private val seenStore: SeenStore,
    log: HermesLog = HermesLog.None,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {
    // Everything this chat runs hangs off one job so close() can stop it all, the open in flight included.
    private val work = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val scope = viewModelScope + work
    private val controller = ChatController(peer, scope, log, clock)

    private val visible = MutableStateFlow(false)

    val chat: StateFlow<ChatState> = controller.state
    val connection: StateFlow<ConnectionState> = peer.state

    private val _title = MutableStateFlow(initialTitle ?: NEW_CHAT_TITLE)
    val title: StateFlow<String> = _title.asStateFlow()

    /** A failure to show once; cleared by [messageShown]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True while the last attempt to open or create the session failed and none is running. */
    private val _openFailed = MutableStateFlow(false)
    val openFailed: StateFlow<Boolean> = _openFailed.asStateFlow()

    /**
     * Each finished reply's parsed Markdown, by its text: parsed once, off the main thread, and kept for the
     * life of the chat, so a reply that scrolls back into view is laid out at its full height straight away.
     */
    private val _parsedReplies = MutableStateFlow<Map<String, State.Success>>(emptyMap())
    val parsedReplies: StateFlow<Map<String, State.Success>> = _parsedReplies.asStateFlow()

    /** The replies whose parse has been started. */
    private val parsing = mutableSetOf<String>()

    private var opened = false

    /** The stored id this chat has needsAnswer registered under. */
    private var registeredId: String? = null

    init {
        scope.launch { openWhenReady() }
        scope.launch { chat.collect(::onChatChanged) }
        scope.launch { chat.collect { parseFinishedReplies(it.items) } }
        scope.launch { markSeenWhileVisible() }
        scope.launch { peer.events.collect { event -> if (event.type in TITLE_EVENTS) onTitleEvent(event.type, event.payload) } }
    }

    /**
     * Marks the session seen when its chat opens, a reply completes, or the screen becomes visible
     * again; not when a reply starts, and never while the screen is not visible.
     */
    private suspend fun markSeenWhileVisible() {
        var previous: Triple<String?, Boolean, Boolean>? = null
        combine(chat.map { it.storedSessionId }, visible, chat.map { it.running }, ::Triple).collect { current ->
            val (id, isVisible, running) = current
            val replyStarted = previous != null && previous!!.first == id && previous!!.second == isVisible && running
            previous = current
            if (id != null && isVisible && !replyStarted) seenStore.markOpened(id, clock())
        }
    }

    private suspend fun openWhenReady() {
        peer.state.collectLatest { connection ->
            if (connection == ConnectionState.Ready && !opened) openChat()
        }
    }

    private suspend fun openChat() {
        _openFailed.value = false
        guarded("Couldn't open the chat") {
            if (storedSessionId != null) controller.open(storedSessionId) else controller.startNew()
            opened = true
        }
        _openFailed.value = !opened
    }

    fun retryOpen() {
        scope.launch { openChat() }
    }

    private fun onChatChanged(state: ChatState) {
        val needsAnswerNow = state.items.any { it is ChatItem.Approval && it.status == CardStatus.Open || it is ChatItem.Clarify && it.status == CardStatus.Open }
        val id = state.storedSessionId
        if (id != registeredId) {
            registeredId?.let { old -> needsAnswer.update { it - old } }
            registeredId = id
        }
        id?.let { stored -> needsAnswer.update { if (needsAnswerNow) it + stored else it - stored } }
    }

    /** A streaming reply changes with every delta, so only finished ones are parsed. */
    private fun parseFinishedReplies(items: List<ChatItem>) {
        for (item in items) {
            if (item !is ChatItem.Assistant || item.streaming || !parsing.add(item.markdown)) continue
            scope.launch {
                val parsed = parseMarkdownFlow(item.markdown).first { it !is State.Loading }
                if (parsed is State.Success) _parsedReplies.update { it + (item.markdown to parsed) }
            }
        }
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

    /** Whether the user can see the chat; replies completing out of sight are marked seen once it is. */
    fun setVisible(visible: Boolean) {
        this.visible.value = visible
    }

    fun messageShown() {
        _message.value = null
    }

    private fun launchGuarded(failure: String, block: suspend () -> Unit) {
        scope.launch { guarded(failure, block) }
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

    /** Whether [close] has stopped this chat. */
    val isClosed: Boolean get() = !work.isActive

    /**
     * Whether the session exists on the server: one that was opened by id does, a new one only once
     * something was said in it (Hermes keeps an empty draft out of its session list).
     */
    val hasStoredSession: Boolean get() = storedSessionId != null || chat.value.items.isNotEmpty()

    /** Stops the controller and takes the chat out of [needsAnswer]; safe to call again. */
    fun close() {
        work.cancel()
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
