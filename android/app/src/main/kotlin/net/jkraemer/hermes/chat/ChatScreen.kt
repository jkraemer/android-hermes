package net.jkraemer.hermes.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.State
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.chat.ToolCall
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.sessions.ScheduleIcon
import net.jkraemer.hermes.sessions.pathIcon
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [cron] is whether this chat is a cron run, whose first message is the job's prompt and is shown collapsed.
 * [onOpenMenu] opens the navigation drawer, [onNewChat] starts another chat.
 * [onStoredSessionId] reports the stored id a new chat got, so the app can track the chat under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    cron: Boolean,
    onOpenMenu: () -> Unit,
    onNewChat: () -> Unit,
    onRetryConnect: () -> Unit,
    onStoredSessionId: (String) -> Unit,
    clock: () -> Instant = Instant::now,
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val openFailed by viewModel.openFailed.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val ready = connection == ConnectionState.Ready

    val started by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(started.isAtLeast(Lifecycle.State.STARTED)) { viewModel.setVisible(started.isAtLeast(Lifecycle.State.STARTED)) }
    LaunchedEffect(chat.storedSessionId) { chat.storedSessionId?.let(onStoredSessionId) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        modifier = Modifier.testTag("chat-screen").imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onOpenMenu) { Icon(Icons.Filled.Menu, contentDescription = "Menu") } },
                actions = { IconButton(onNewChat) { Icon(Icons.Filled.Add, contentDescription = "New chat") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The composer floats over the end of the chat, which scrolls underneath it.
        val density = LocalDensity.current
        var composerHeight by remember { mutableStateOf(0.dp) }
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (!ready) ReconnectingBanner(onRetryConnect)
                else if (openFailed && chat.liveSessionId == null) OpenFailedBanner(viewModel::retryOpen)
                Messages(chat.items, cron, viewModel, clock, composerHeight, Modifier.weight(1f))
            }
            Composer(
                running = chat.running,
                canSend = ready && chat.liveSessionId != null,
                onSend = viewModel::send,
                onStop = viewModel::interrupt,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

@Composable
private fun ReconnectingBanner(onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reconnecting…", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun OpenFailedBanner(onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Couldn't open this chat", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onRetry) { Text("Retry") }
        }
    }
}

/** [composerHeight] is how much of the list's bottom the composer covers; the last message ends above it. */
@Composable
private fun Messages(chatItems: List<ChatItem>, cron: Boolean, viewModel: ChatViewModel, clock: () -> Instant, composerHeight: Dp, modifier: Modifier) {
    val listState = rememberLazyListState()
    val parsedReplies by viewModel.parsedReplies.collectAsStateWithLifecycle()
    val following = remember { mutableStateOf(true) }
    FollowTheEnd(listState, following)
    val awayFromTheEnd by remember { derivedStateOf { !following.value && listState.canScrollForward } }
    val jobPromptIndex = if (cron) chatItems.indexOfFirst { it is ChatItem.User } else -1
    Box(modifier.fillMaxWidth()) {
        MessageList(chatItems, jobPromptIndex, parsedReplies, viewModel, clock, listState, composerHeight)
        ScrollToEndButton(
            awayFromTheEnd,
            onClick = { following.value = true },
            Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight),
        )
    }
}

@Composable
private fun MessageList(
    chatItems: List<ChatItem>,
    jobPromptIndex: Int,
    parsedReplies: Map<String, State.Success>,
    viewModel: ChatViewModel,
    clock: () -> Instant,
    listState: LazyListState,
    composerHeight: Dp,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("messages"),
        listState,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = composerHeight + 8.dp),
        verticalArrangement = spacedBy(16.dp),
    ) {
        // Items are only ever appended to a chat, so an item keeps its index.
        items(chatItems.size, key = { it }, contentType = { chatItems[it]::class }) { index ->
            when (val item = chatItems[index]) {
                is ChatItem.User -> if (index == jobPromptIndex) JobPrompt(item.text) else UserBubble(item.text)
                is ChatItem.Assistant -> Reply(item, parsedReplies[item.markdown])
                is ChatItem.Tools -> ToolLine(item.calls)
                is ChatItem.Approval -> ApprovalCard(item, clock, onChoose = { viewModel.answerApproval(item.requestId, it) })
                is ChatItem.Clarify -> ClarifyCard(item, onSend = { viewModel.answerClarify(item.requestId, it) })
                is ChatItem.Error -> Text(item.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A round down arrow, shown while the chat is away from its end; a tap goes back to the end and follows it again. */
@Composable
private fun ScrollToEndButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        Surface(
            onClick,
            Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(ArrowDownwardIcon, contentDescription = "Scroll to end", Modifier.size(20.dp)) }
        }
    }
}

/**
 * A finished reply renders from its [parsed] Markdown, so it is laid out at its full height in the first frame it
 * is shown. Any other reply, streaming or finished before its parse is ready, is parsed by the renderer; one call
 * serves both, so a reply that has just finished streaming stays as it was, and while the renderer parses it shows
 * the plain text rather than nothing.
 */
@Composable
private fun Reply(reply: ChatItem.Assistant, parsed: State.Success?) {
    val modifier = Modifier.fillMaxWidth()
    if (parsed != null) Markdown(parsed, modifier = modifier)
    else Markdown(reply.markdown, modifier = modifier, loading = { Text(reply.markdown, it, style = MaterialTheme.typography.bodyLarge) })
}

/**
 * Keeps the end of the chat in view while it is [following]: whenever the list could scroll further, because an item
 * was added, a reply grew, a reply was laid out anew once its Markdown was parsed, or the list got less room. Following
 * the layout rather than the items matters when much arrives at once, as after a reconnect: the reply's new text is
 * laid out a few frames after it arrives. Scrolling away from the end stops following; scrolling back resumes it.
 */
@Composable
private fun FollowTheEnd(listState: LazyListState, following: MutableState<Boolean>) {
    LaunchedEffect(listState) {
        // Scrolling to the end happens in here, so this never sees its own scrolls in progress, only the user's.
        snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, following.value) }.collect { (scrolling, canScrollForward, follow) ->
            if (scrolling) following.value = !canScrollForward
            else if (follow && canScrollForward) scrollToEnd(listState)
        }
    }
}

private suspend fun scrollToEnd(listState: LazyListState) {
    val last = listState.layoutInfo.totalItemsCount - 1
    if (last < 0) return
    try {
        listState.scrollToItem(last)
        listState.scrollBy(Float.MAX_VALUE)
    } catch (e: CancellationException) {
        // A drag that starts meanwhile takes the list over and cancels this scroll, but must not end the following.
        currentCoroutineContext().ensureActive()
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The prompt a cron job ran with: one muted line, and a tap shows the whole text. */
@Composable
private fun JobPrompt(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("job-prompt"), verticalArrangement = spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(8.dp)) {
            Icon(ScheduleIcon, contentDescription = null, Modifier.size(16.dp), tint = muted)
            Text("Scheduled job prompt", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = muted)
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                Modifier.size(20.dp),
                tint = muted,
            )
        }
        if (expanded) Text(text, style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

private fun ToolCall.line() = listOfNotNull(name, summary?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** One line for the consecutive calls; a tap lists each call on its own line. */
@Composable
private fun ToolLine(calls: List<ToolCall>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("tool-line"), verticalArrangement = spacedBy(4.dp)) {
        if (expanded) {
            for (call in calls) {
                if (call.failed) Text("${call.line()} · failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else Text(call.line(), color = muted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val more = calls.size - 1
            val suffix = when (more) {
                0 -> ""
                1 -> " · 1 more tool"
                else -> " · $more more tools"
            }
            Text(calls.first().line() + suffix, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The message field with send, or stop while a turn runs. Empty and unfocused it is one pill; focused or holding text
 * it is a card with the field above a row of actions, whose start [leading] fills.
 */
@Composable
private fun Composer(
    running: Boolean,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || text.isNotEmpty()
    val sendOrStop = @Composable {
        if (running) {
            FilledIconButton(onStop, Modifier.semantics { contentDescription = "Stop" }) {
                Box(Modifier.size(14.dp).background(LocalContentColor.current, RoundedCornerShape(2.dp)))
            }
        } else {
            FilledIconButton(
                onClick = {
                    onSend(text.trim())
                    text = ""
                },
                enabled = canSend && text.isNotBlank(),
            ) { Icon(ArrowUpwardIcon, contentDescription = "Send") }
        }
    }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("composer-box"),
        shape = if (expanded) RoundedCornerShape(24.dp) else CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // The field has the same place in both shapes, so it keeps its focus as the composer expands.
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MessageField(
                    text,
                    onTextChange = { text = it },
                    Modifier.weight(1f).padding(vertical = if (expanded) 8.dp else 0.dp).onFocusChanged { focused = it.isFocused },
                )
                if (!expanded) sendOrStop()
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    leading()
                    Spacer(Modifier.weight(1f))
                    sendOrStop()
                }
            }
        }
    }
}

/** Grows with its text up to six lines, then scrolls. */
@Composable
private fun MessageField(text: String, onTextChange: (String) -> Unit, modifier: Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    BasicTextField(
        text,
        onTextChange,
        modifier.testTag("composer"),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 6,
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("Message Hermes", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            }
        },
    )
}

// The Material "arrow upward" and "arrow downward" icons, which material-icons-core does not have.
private val ArrowUpwardIcon = pathIcon("ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
private val ArrowDownwardIcon = pathIcon("ArrowDownward", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
