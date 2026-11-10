package net.jkraemer.hermes.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import net.jkraemer.hermes.core.chat.RunWork
import net.jkraemer.hermes.core.chat.cronRunWork
import net.jkraemer.hermes.core.chat.ToolCall
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.sessions.ScheduleIcon
import net.jkraemer.hermes.sessions.pathIcon
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [cron] is whether this chat is a cron run, whose work before its final reply is shown collapsed.
 * [onOpenMenu] opens the navigation drawer, [onNewChat] starts another chat.
 * [onStoredSessionId] reports the stored id a new chat got, so the app can track the chat under it.
 * [onOpenFavorites] leaves for choosing the favorite models the options sheet lists. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    cron: Boolean,
    onOpenMenu: () -> Unit,
    onNewChat: () -> Unit,
    onRetryConnect: () -> Unit,
    onStoredSessionId: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    clock: () -> Instant = Instant::now,
    keyboardVisible: Boolean = WindowInsets.isImeVisible,
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val openFailed by viewModel.openFailed.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    val effect by viewModel.effect.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val sheetSnackbar = remember { SnackbarHostState() }
    val ready = connection == ConnectionState.Ready
    var optionsOpen by rememberSaveable { mutableStateOf(false) }

    val started by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(started.isAtLeast(Lifecycle.State.STARTED)) { viewModel.setVisible(started.isAtLeast(Lifecycle.State.STARTED)) }
    LaunchedEffect(chat.storedSessionId) { chat.storedSessionId?.let(onStoredSessionId) }
    // The options sheet covers the screen's snackbar, so while it is open it shows the messages itself.
    LaunchedEffect(message) {
        message?.let {
            (if (optionsOpen) sheetSnackbar else snackbar).showSnackbar(it)
            viewModel.messageShown()
        }
    }

    LaunchedEffect(effect) {
        when (effect) {
            ChatEffect.NewChat -> {
                viewModel.effectHandled()
                onNewChat()
            }
            ChatEffect.OpenOptions -> {
                viewModel.effectHandled()
                optionsOpen = true
            }
            // The composer takes the text and acknowledges it.
            is ChatEffect.Prefill, null -> Unit
        }
    }
    if (optionsOpen) SessionOptionsSheet(viewModel, sheetSnackbar, onOpenFavorites, onDismiss = { optionsOpen = false })
    pendingConfirmation?.let { ModelConfirmDialog(it, onConfirm = viewModel::confirmModel, onDismiss = viewModel::dismissConfirmation) }

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
                keyboardVisible = keyboardVisible,
                prefill = (effect as? ChatEffect.Prefill)?.text,
                onPrefillShown = viewModel::effectHandled,
                commands = commands,
                slashMenu = true,
                onMenuOpened = viewModel::menuOpened,
                leading = { ModelChip(chat.options, enabled = ready, onClick = { optionsOpen = true }) },
                onBoxHeightChanged = { composerHeight = with(density) { it.toDp() } },
                modifier = Modifier.align(Alignment.BottomCenter),
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
    // Saved with the list's position, so a rotation keeps a chat scrolled away from its end where it was.
    val following = rememberSaveable { mutableStateOf(true) }
    val glide = remember { mutableStateOf(false) }
    FollowTheEnd(listState, following, glide)
    val awayFromTheEnd by remember { derivedStateOf { !following.value && listState.canScrollForward } }
    val work = if (cron) cronRunWork(chatItems) else null
    var workShown by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        MessageList(
            chatItems, work, workShown,
            onToggleWork = {
                workShown = !workShown
                // The work opens above the reply; following the end would scroll it straight out of view.
                if (workShown) following.value = false
            },
            parsedReplies, viewModel, clock, listState, composerHeight,
        )
        ScrollToEndButton(
            awayFromTheEnd,
            onClick = {
                glide.value = true
                following.value = true
            },
            Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight),
        )
    }
}

/** A cron run's [work], if any, is one line before its final reply, which [workShown] opens. */
@Composable
private fun MessageList(
    chatItems: List<ChatItem>,
    work: RunWork?,
    workShown: Boolean,
    onToggleWork: () -> Unit,
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
        if (work != null) item(key = "work") { WorkLine(work.steps, workShown, onToggleWork) }
        val first = if (work != null && !workShown) work.end else 0
        // Items are only ever appended to a chat, so an item keeps its index.
        items(chatItems.size - first, key = { first + it }, contentType = { chatItems[first + it]::class }) { offset ->
            val index = first + offset
            when (val item = chatItems[index]) {
                is ChatItem.User -> if (work != null && index == 0) JobPrompt(item.text) else UserBubble(item.text)
                is ChatItem.Assistant -> Reply(item.markdown, parsedReplies[item.markdown])
                is ChatItem.Tools -> ToolLine(item.calls)
                is ChatItem.Approval -> ApprovalCard(item, clock, onChoose = { viewModel.answerApproval(item.requestId, it) })
                is ChatItem.Clarify -> ClarifyCard(item, onSend = { viewModel.answerClarify(item.requestId, it) })
                is ChatItem.Error -> Text(item.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                is ChatItem.Notice -> Text(item.text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
internal fun Reply(markdown: String, parsed: State.Success?) {
    val modifier = Modifier.fillMaxWidth()
    if (parsed != null) Markdown(parsed, modifier = modifier)
    else Markdown(markdown, modifier = modifier, loading = { Text(markdown, it, style = MaterialTheme.typography.bodyLarge) })
}

/**
 * Keeps the end of the chat in view while it is [following]: whenever the list could scroll further, because an item
 * was added, a reply grew, a reply was laid out anew once its Markdown was parsed, or the list got less room. Following
 * the layout rather than the items matters when much arrives at once, as after a reconnect: the reply's new text is
 * laid out a few frames after it arrives. Scrolling away from the end stops following; scrolling back resumes it.
 */
@Composable
private fun FollowTheEnd(listState: LazyListState, following: MutableState<Boolean>, glide: MutableState<Boolean>) {
    LaunchedEffect(listState) {
        // Scrolling to the end happens in here, so this never sees its own scrolls in progress, only the user's.
        snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, following.value) }.collect { (scrolling, canScrollForward, follow) ->
            if (scrolling) following.value = !canScrollForward
            else if (follow && canScrollForward) {
                if (glide.value) {
                    glide.value = false
                    glideToEnd(listState)
                } else {
                    scrollToEnd(listState)
                }
            }
        }
    }
}

/**
 * How the scroll-to-end button gets there: a fast, visible scroll rather than a jump. However far away the end is,
 * the list first snaps to one screen above it, so the glide always covers at most one screen.
 */
private suspend fun glideToEnd(listState: LazyListState) {
    val layout = listState.layoutInfo
    val last = layout.totalItemsCount - 1
    if (last < 0) return
    try {
        val lastShown = layout.visibleItemsInfo.lastOrNull()
        val distance = if (lastShown?.index == last) {
            (lastShown.offset + lastShown.size + layout.afterContentPadding - layout.viewportEndOffset).toFloat()
        } else {
            listState.scrollToItem(last)
            listState.scrollBy(Float.MAX_VALUE)
            -listState.scrollBy(-(layout.viewportEndOffset - layout.viewportStartOffset).toFloat())
        }
        listState.animateScrollBy(distance, tween(GLIDE_MILLIS, easing = FastOutSlowInEasing))
        listState.scrollBy(Float.MAX_VALUE)
    } catch (e: CancellationException) {
        // A drag that starts meanwhile takes the list over; the drag then decides whether it still follows.
        currentCoroutineContext().ensureActive()
    }
}

private const val GLIDE_MILLIS = 300

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

/** The line a cron run's work before its final reply folds into; a tap shows the work or hides it again. */
@Composable
private fun WorkLine(steps: Int, shown: Boolean, onToggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("work-line"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(8.dp),
    ) {
        Icon(ScheduleIcon, contentDescription = null, Modifier.size(16.dp).testTag("work-icon"), tint = muted)
        Text(if (steps == 1) "Show work (1 step)" else "Show work ($steps steps)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = muted)
        Icon(
            if (shown) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (shown) "Collapse" else "Expand",
            Modifier.size(20.dp),
            tint = muted,
        )
    }
}

/** The prompt a cron job ran with, in the run's work: muted and small, not a user bubble. */
@Composable
private fun JobPrompt(text: String) {
    Text(text, Modifier.fillMaxWidth().testTag("job-prompt"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// The Material "arrow downward" icon, which material-icons-core does not have.
private val ArrowDownwardIcon = pathIcon("ArrowDownward", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
