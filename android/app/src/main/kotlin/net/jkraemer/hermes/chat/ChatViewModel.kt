package net.jkraemer.hermes.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
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
import net.jkraemer.hermes.core.commands.CommandCatalog
import net.jkraemer.hermes.core.commands.CommandOutcome
import net.jkraemer.hermes.core.commands.commandCatalog
import net.jkraemer.hermes.core.commands.parseSlashCommand
import net.jkraemer.hermes.core.commands.runCommand
import net.jkraemer.hermes.core.commands.showModelSwitch
import net.jkraemer.hermes.core.options.ModelCatalog
import net.jkraemer.hermes.core.options.ModelRef
import net.jkraemer.hermes.core.options.ModelSwitch
import net.jkraemer.hermes.core.options.modelCatalog
import net.jkraemer.hermes.core.options.runModelCommand
import net.jkraemer.hermes.core.options.setFast
import net.jkraemer.hermes.core.options.setReasoning
import net.jkraemer.hermes.core.options.setYolo
import net.jkraemer.hermes.core.options.switchModel
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.userMessage
import net.jkraemer.hermes.settings.NeedsAnswerStore
import net.jkraemer.hermes.settings.SeenStore
import java.time.Instant

const val NEW_CHAT_TITLE = "New chat"

/** What the screen does after a command ran that the chat itself cannot do. */
sealed interface ChatEffect {
    data object OpenOptions : ChatEffect
    data object NewChat : ChatEffect
    data class Prefill(val text: String) : ChatEffect
}

/**
 * A model switch waiting for the user's go-ahead: [message] says why, [retry] makes the switch with it. A switch
 * typed as `/model` is reported in the chat ([inChat]); one from the options sheet, in the sheet's messages.
 */
data class PendingModel(val message: String, val inChat: Boolean = true, val retry: suspend () -> ModelSwitch)

/**
 * One opened chat. [storedSessionId] null starts a new one. Opens the session once the
 * connection is ready (and again after a failed open, when it next becomes ready), and keeps
 * [needsAnswer] and [seenStore] current for the session while it lives. A chat closed with open cards hands them to
 * [pushed], the requests waiting for an answer, so the session stays in "Needs answer" until they expire or a push
 * resolves them; an open chat that shows no open card clears its session from [pushed]. [visibleSession] holds the
 * stored id of the chat on screen.
 * [pendingMessage], if any, is sent once the session is open. The first message sent makes the chat [follow] its stored
 * session, again if the stored id changes.
 */
class ChatViewModel(
    private val peer: RpcPeer,
    private val storedSessionId: String?,
    initialTitle: String?,
    private val needsAnswer: MutableStateFlow<Set<String>>,
    private val seenStore: SeenStore,
    private val pushed: NeedsAnswerStore? = null,
    private val visibleSession: MutableStateFlow<String?>? = null,
    private val log: HermesLog = HermesLog.None,
    private val pendingMessage: String? = null,
    /** The models the user keeps at hand for the options sheet. */
    val favorites: StateFlow<List<ModelRef>> = MutableStateFlow(emptyList()),
    /** Tells the server the session is an app chat, so its replies are pushed. */
    private val follow: suspend (String) -> Unit = {},
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

    private val _commands = MutableStateFlow<CommandCatalog?>(null)

    /** What Hermes offers as slash commands; null until loaded, and after a failed load until the next try. */
    val commands: StateFlow<CommandCatalog?> = _commands.asStateFlow()

    private val _effect = MutableStateFlow<ChatEffect?>(null)

    /** What a command asks the screen to do; cleared by [effectHandled]. */
    val effect: StateFlow<ChatEffect?> = _effect.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingModel?>(null)

    /** A model switch the user has yet to confirm. */
    val pendingConfirmation: StateFlow<PendingModel?> = _pendingConfirmation.asStateFlow()

    private val _modelCatalog = MutableStateFlow<ModelCatalog?>(null)

    /** The models Hermes offers this session; null until the options sheet has loaded them. */
    val modelCatalog: StateFlow<ModelCatalog?> = _modelCatalog.asStateFlow()

    private val _modelCatalogFailed = MutableStateFlow(false)

    /** Whether the last attempt to load [modelCatalog] failed. */
    val modelCatalogFailed: StateFlow<Boolean> = _modelCatalogFailed.asStateFlow()

    private val _switchDeferred = MutableStateFlow(false)

    /** Whether the last model switch from the options sheet waits for the running turn to end; Hermes applies it when the next turn starts. */
    val switchDeferred: StateFlow<Boolean> = _switchDeferred.asStateFlow()

    /** The catalogue load in flight or last finished; commands wait for it rather than start another. */
    private var catalogLoad: Deferred<Unit>? = null

    private val markdown = ParsedMarkdown(scope)

    /** Each finished reply's parsed Markdown, by its text, kept for the life of the chat. */
    val parsedReplies: StateFlow<Map<String, State.Success>> = markdown.parsed

    /** Whether [pendingMessage] has been sent. */
    private var pendingSent = false

    private var opened = false

    /** The stored id this chat has needsAnswer registered under. */
    private var registeredId: String? = null

    /** The stored id [follow] last succeeded or is running for. */
    private var followedId: String? = null

    init {
        scope.launch { openWhenReady() }
        scope.launch { chat.collect(::onChatChanged) }
        scope.launch { chat.collect { parseFinishedReplies(it.items) } }
        scope.launch { chat.map { it.running }.distinctUntilChanged().collect { running -> if (running) _switchDeferred.value = false } }
        scope.launch { markSeenWhileVisible() }
        scope.launch { publishVisibleSession() }
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

    private suspend fun publishVisibleSession() {
        combine(chat.map { it.storedSessionId }, visible, ::Pair).collect { (id, isVisible) -> setVisibleSession(id.takeIf { isVisible }) }
    }

    private var shownSession: String? = null

    /** Leaves [visibleSession] alone when another chat has taken it over, as happens while the screen changes. */
    @Synchronized
    private fun setVisibleSession(id: String?) {
        val mine = shownSession
        shownSession = id
        visibleSession?.update { current -> if (id != null || current == mine) id else current }
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
            clearPushedWhenSettled(chat.value)
        }
        _openFailed.value = !opened
        if (opened) loadCatalog()
        if (opened && !pendingSent && pendingMessage != null) {
            pendingSent = true
            send(pendingMessage)
        }
    }

    fun retryOpen() {
        scope.launch { openChat() }
    }

    private fun onChatChanged(state: ChatState) {
        val needsAnswerNow = state.items.any { it.isOpenCard() }
        val id = state.storedSessionId
        if (id != registeredId) {
            registeredId?.let { old -> needsAnswer.update { it - old } }
            registeredId = id
        }
        id?.let { stored -> needsAnswer.update { if (needsAnswerNow) it + stored else it - stored } }
        clearPushedWhenSettled(state)
    }

    /** Only once the open is complete: before that the transcript is shown without the requests still to be fed in. */
    private fun clearPushedWhenSettled(state: ChatState) {
        val id = state.storedSessionId
        if (opened && id != null && state.items.none { it.isOpenCard() }) pushed?.clearSession(id)
    }

    private fun ChatItem.isOpenCard() = this is ChatItem.Approval && status == CardStatus.Open || this is ChatItem.Clarify && status == CardStatus.Open

    /** A streaming reply changes with every delta, so only finished ones are parsed. */
    private fun parseFinishedReplies(items: List<ChatItem>) {
        for (item in items) if (item is ChatItem.Assistant && !item.streaming) markdown.parse(item.markdown)
    }

    /** `session.title` names the session by its stored id; `session.info` carries it as `title` next to `stored_session_id`. */
    private fun onTitleEvent(type: String, payload: JsonObject) {
        val state = chat.value
        val sessionKey = payload.string(if (type == "session.title") "session_id" else "stored_session_id")
        val title = payload.string("title")?.takeIf { it.isNotBlank() }
        if (title != null && sessionKey != null && sessionKey == state.storedSessionId) _title.value = title
    }

    /** Starts loading the catalogue unless a load is in flight; a failed one leaves [commands] as it was. */
    private fun loadCatalog(): Deferred<Unit> {
        catalogLoad?.takeIf { it.isActive }?.let { return it }
        return scope.async {
            try {
                _commands.value = peer.commandCatalog(chat.value.liveSessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }.also { catalogLoad = it }
    }

    /** The user opened the slash menu: a catalogue that failed to load is tried again. */
    fun menuOpened() {
        if (_commands.value == null) loadCatalog()
    }

    /** A command is never sent to the model as text, so text shaped like one waits for the catalogue that says whether it is. */
    fun send(text: String) = launchGuarded("Couldn't send") {
        scope.launch { followStoredSession() }
        val command = parseSlashCommand(text)
        if (command == null) {
            controller.send(text)
            return@launchGuarded
        }
        val catalog = _commands.value ?: loadCatalog().let { it.await(); _commands.value }
        if (catalog == null) {
            _message.value = "Couldn't load commands"
        } else if (catalog.canonical(command.name) == null) {
            controller.send(text)
        } else {
            handle(controller.runCommand(command, catalog))
        }
    }

    /** Waits for the stored id rather than the send, so following never delays it; a failure is logged and tried again with the next message. */
    private suspend fun followStoredSession() {
        val id = chat.first { it.storedSessionId != null }.storedSessionId ?: return
        if (id == followedId) return
        followedId = id
        try {
            follow(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            followedId = null
            log.log("Couldn't follow the session: ${e::class.simpleName}")
        }
    }

    private fun handle(outcome: CommandOutcome) {
        when (outcome) {
            CommandOutcome.Done -> Unit
            CommandOutcome.OpenOptions -> _effect.value = ChatEffect.OpenOptions
            CommandOutcome.NewChat -> _effect.value = ChatEffect.NewChat
            is CommandOutcome.Prefill -> _effect.value = ChatEffect.Prefill(outcome.text)
            is CommandOutcome.ConfirmModel ->
                _pendingConfirmation.value = PendingModel(outcome.message) { controller.runModelCommand(outcome.args, confirmed = true) }
        }
    }

    /** The options sheet opened: asks Hermes what it offers this session, keeping what it last offered meanwhile. */
    fun loadModelCatalog() {
        _switchDeferred.value = false
        _modelCatalogFailed.value = false
        scope.launch {
            try {
                _modelCatalog.value = peer.modelCatalog(chat.value.liveSessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _modelCatalogFailed.value = true
            }
        }
    }

    fun switchModel(ref: ModelRef) = launchGuarded("Couldn't change the model") {
        _switchDeferred.value = false
        reportSwitch(controller.switchModel(ref)) { controller.switchModel(ref, confirmed = true) }
    }

    fun setReasoning(level: String) = launchGuarded("Couldn't change reasoning") { controller.setReasoning(level) }

    fun setFast(on: Boolean) = launchGuarded("Couldn't change fast mode") { controller.setFast(on) }

    fun setYolo(on: Boolean) = launchGuarded("Couldn't change YOLO") { controller.setYolo(on) }

    /** Tells the options sheet what became of a switch; a switch that needs the user's go-ahead waits for it, and [confirmed] makes it then. */
    private fun reportSwitch(switch: ModelSwitch, confirmed: suspend () -> ModelSwitch) {
        when (switch) {
            is ModelSwitch.Applied -> switch.warning?.let { _message.value = it }
            is ModelSwitch.Deferred -> {
                _switchDeferred.value = true
                switch.warning?.let { _message.value = it }
            }
            is ModelSwitch.NeedsConfirmation ->
                _pendingConfirmation.value = PendingModel(switch.message, inChat = false, confirmed)
        }
    }

    fun effectHandled() {
        _effect.value = null
    }

    /** Makes the switch the user confirmed and tells the chat what it did, as the first attempt would have. */
    fun confirmModel() {
        val pending = _pendingConfirmation.getAndUpdate { null } ?: return
        launchGuarded(if (pending.inChat) "Couldn't switch the model" else "Couldn't change the model") {
            val switch = pending.retry()
            if (pending.inChat) controller.showModelSwitch(switch) else reportSwitch(switch, pending.retry)
        }
    }

    fun dismissConfirmation() {
        _pendingConfirmation.value = null
    }

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
            _message.value = e.userMessage()?.let { "$failure: $it" } ?: failure
        }
    }

    /** Whether [close] has stopped this chat. */
    val isClosed: Boolean get() = !work.isActive

    /**
     * Whether the session exists on the server: one that was opened by id does, a new one only once
     * something was said in it (Hermes keeps an empty draft out of its session list).
     */
    val hasStoredSession: Boolean get() = storedSessionId != null || chat.value.items.isNotEmpty()

    /** Stops the controller, handing its open cards to [pushed]; safe to call again. */
    fun close() {
        work.cancel()
        handOpenCardsToPushedStore()
        setVisibleSession(null)
        registeredId = null
        controller.close()
    }

    private fun handOpenCardsToPushedStore() {
        val id = registeredId
        if (pushed == null || id == null) return
        for (item in chat.value.items) {
            when {
                item is ChatItem.Approval && item.status == CardStatus.Open -> pushed.add(id, item.requestId, item.deadline)
                item is ChatItem.Clarify && item.status == CardStatus.Open -> pushed.add(id, item.requestId, clock() + NeedsAnswerStore.CLARIFY_TIMEOUT)
            }
        }
        needsAnswer.update { it - id }
    }

    override fun onCleared() = close()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        val TITLE_EVENTS = setOf("session.title", "session.info")
    }
}
