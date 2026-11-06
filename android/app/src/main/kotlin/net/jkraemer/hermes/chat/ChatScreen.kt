package net.jkraemer.hermes.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.mikepenz.markdown.m3.Markdown
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.chat.ToolCall
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.sessions.ScheduleIcon
import java.time.Instant

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
        bottomBar = {
            Composer(
                running = chat.running,
                canSend = ready && chat.liveSessionId != null,
                onSend = viewModel::send,
                onStop = viewModel::interrupt,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!ready) ReconnectingBanner(onRetryConnect)
            else if (openFailed && chat.liveSessionId == null) OpenFailedBanner(viewModel::retryOpen)
            Messages(chat.items, cron, viewModel, clock, Modifier.weight(1f))
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

@Composable
private fun Messages(chatItems: List<ChatItem>, cron: Boolean, viewModel: ChatViewModel, clock: () -> Instant, modifier: Modifier) {
    val listState = rememberLazyListState()
    FollowTheEnd(listState, chatItems)
    val jobPromptIndex = if (cron) chatItems.indexOfFirst { it is ChatItem.User } else -1
    LazyColumn(
        modifier.fillMaxWidth().testTag("messages"),
        listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = spacedBy(16.dp),
    ) {
        items(chatItems.size) { index ->
            when (val item = chatItems[index]) {
                is ChatItem.User -> if (index == jobPromptIndex) JobPrompt(item.text) else UserBubble(item.text)
                is ChatItem.Assistant -> Markdown(item.markdown, modifier = Modifier.fillMaxWidth())
                is ChatItem.Tools -> ToolLine(item.calls)
                is ChatItem.Approval -> ApprovalCard(item, clock, onChoose = { viewModel.answerApproval(item.requestId, it) })
                is ChatItem.Clarify -> ClarifyCard(item, onSend = { viewModel.answerClarify(item.requestId, it) })
                is ChatItem.Error -> Text(item.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Keeps the newest content in view while it grows, until the user scrolls away from the end; scrolling back to the end resumes it. */
@Composable
private fun FollowTheEnd(listState: LazyListState, items: List<ChatItem>) {
    var following by remember { mutableStateOf(true) }
    var scrollingItself by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }.collect { (scrolling, atEnd) ->
            if (scrolling && !scrollingItself) following = atEnd
        }
    }
    val last = items.lastOrNull()
    val growth = when (last) {
        is ChatItem.Assistant -> last.markdown.length
        is ChatItem.Tools -> last.calls.size
        else -> 0
    }
    LaunchedEffect(items.size, growth) {
        if (!following || items.isEmpty()) return@LaunchedEffect
        scrollingItself = true
        try {
            listState.scrollToItem(items.lastIndex)
            listState.scrollBy(Float.MAX_VALUE)
        } finally {
            scrollingItself = false
        }
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

@Composable
private fun Composer(running: Boolean, canSend: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it },
                Modifier.weight(1f).testTag("composer"),
                placeholder = { Text("Message Hermes") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
            )
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
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}
