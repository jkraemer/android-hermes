package net.jkraemer.hermes.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.timeline.Bucket
import net.jkraemer.hermes.core.timeline.TimelineItem

/** Rows within this distance of the end of the list trigger loading the next page. */
private const val PREFETCH_DISTANCE = 5

/** Space below the last row so it can scroll clear of the "New chat" button: its height plus its margin. */
private val FAB_CLEARANCE = 56.dp + 16.dp + 16.dp

private val Bucket.title: String
    get() = when (this) {
        Bucket.NEEDS_ANSWER -> "Needs answer"
        Bucket.PINNED -> "Pinned"
        Bucket.TODAY -> "Today"
        Bucket.YESTERDAY -> "Yesterday"
        Bucket.OLDER -> "Older"
    }

/** [onOpenChat] gets the stored session id and its title, or nulls for a new chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    host: String,
    onOpenChat: (id: String?, title: String?) -> Unit,
    onNotLoggedIn: () -> Unit,
    onRetryConnect: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.needsLogin) {
        if (state.needsLogin) {
            onNotLoggedIn()
            viewModel.loginHandled()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - PREFETCH_DISTANCE
        }.collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }

    Scaffold(
        modifier = Modifier.testTag("sessions-screen"),
        topBar = { TopAppBar(title = { Text("Hermes") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onOpenChat(null, null) }) { Text("New chat") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                state.query, viewModel::search,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("search"),
                placeholder = { Text("Search sessions…") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            state.error?.let { error ->
                ErrorBanner(
                    if (error == SessionListError.UNREACHABLE) "Can't reach $host. Check your connection or VPN." else "Something went wrong",
                    onRetry = {
                        viewModel.refresh()
                        onRetryConnect()
                    },
                )
            }
            PullToRefreshBox(state.refreshing, onRefresh = viewModel::refresh, Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("session-list"), listState,
                    contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
                ) {
                    items(
                        state.items,
                        key = { if (it is TimelineItem.Row) it.session.id else (it as TimelineItem.Header).bucket },
                    ) { item ->
                        when (item) {
                            is TimelineItem.Header -> BucketHeader(item.bucket)
                            is TimelineItem.Row -> SessionRow(
                                item,
                                onShown = { viewModel.cronRowShown(item.session.id) },
                                onOpen = { onOpenChat(item.session.id, item.session.title) },
                                onRename = { viewModel.rename(item.session.id, it) },
                                onSetPinned = { viewModel.setPinned(item.session.id, it) },
                                onArchive = { viewModel.archive(item.session.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        Button(onRetry) { Text("Retry") }
    }
}

@Composable
private fun BucketHeader(bucket: Bucket) {
    Text(
        bucket.title,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    row: TimelineItem.Row,
    onShown: () -> Unit,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onArchive: () -> Unit,
) {
    val session = row.session
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val title = session.title ?: session.preview ?: "Untitled"
    if (session.isCron) LaunchedEffect(session.id) { onShown() }

    Box {
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.needsAnswer) Tag("approval", MaterialTheme.colorScheme.tertiaryContainer)
                if (session.isCron) Tag("cron", MaterialTheme.colorScheme.secondaryContainer)
                Text(
                    title, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (row.unread) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape).testTag("unread-dot"))
                }
            }
            // Without a title the preview is already shown as the title.
            if (session.title != null) session.preview?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SessionMenu(
            session, menuOpen, onDismiss = { menuOpen = false },
            onRename = { menuOpen = false; renaming = true },
            onSetPinned = { menuOpen = false; onSetPinned(it) },
            onArchive = { menuOpen = false; onArchive() },
        )
    }
    if (renaming) {
        RenameDialog(session.title.orEmpty(), onDismiss = { renaming = false }, onSave = {
            renaming = false
            onRename(it)
        })
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SessionMenu(
    session: SessionSummary,
    open: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onArchive: () -> Unit,
) {
    DropdownMenu(open, onDismiss) {
        DropdownMenuItem({ Text("Rename") }, onRename)
        DropdownMenuItem({ Text(if (session.pinned) "Unpin" else "Pin") }, { onSetPinned(!session.pinned) })
        DropdownMenuItem({ Text("Archive") }, onArchive)
    }
}

@Composable
private fun RenameDialog(currentTitle: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = { OutlinedTextField(title, { title = it }, Modifier.testTag("rename-field"), singleLine = true) },
        confirmButton = { TextButton({ onSave(title.trim()) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
