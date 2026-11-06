package net.jkraemer.hermes.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Clock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jkraemer.hermes.core.sessions.SearchHit
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.sessions.Snippet
import net.jkraemer.hermes.core.timeline.Bucket
import net.jkraemer.hermes.core.timeline.TimelineItem

/** Rows within this distance of the end of the list trigger loading the next page. */
private const val PREFETCH_DISTANCE = 5

private val BUTTON_SIZE = 56.dp

/** Space below the last row so it can scroll clear of the round buttons or the search field: their height plus their margin. */
private val BUTTON_CLEARANCE = BUTTON_SIZE + 16.dp + 16.dp

private val Bucket.title: String
    get() = when (this) {
        Bucket.NEEDS_ANSWER -> "Needs answer"
        Bucket.PINNED -> "Pinned"
        Bucket.TODAY -> "Today"
        Bucket.YESTERDAY -> "Yesterday"
        Bucket.OLDER -> "Older"
    }

/**
 * [onOpenMenu] opens the navigation drawer, [onOpenChat] gets the stored session id, its title and whether
 * it is a cron run, or nulls for a new chat. [clock] decides how recent "ago" is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    host: String,
    onOpenMenu: () -> Unit,
    onOpenChat: (id: String?, title: String?, cron: Boolean) -> Unit,
    onNotLoggedIn: () -> Unit,
    onRetryConnect: () -> Unit,
    clock: Clock = Clock.systemDefaultZone(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val locale = LocalConfiguration.current.locales[0]

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
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Recents") },
                navigationIcon = { IconButton(onOpenMenu) { Icon(Icons.Filled.Menu, contentDescription = "Menu") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
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
                        contentPadding = PaddingValues(bottom = BUTTON_CLEARANCE),
                    ) {
                        val results = state.results
                        if (results != null) {
                            items(results, key = { it.session.id }) { hit ->
                                SearchResultRow(hit, onOpen = { onOpenChat(hit.session.id, hit.session.title, hit.session.isCron) })
                            }
                        } else {
                            items(
                                state.items,
                                key = { if (it is TimelineItem.Row) it.session.id else (it as TimelineItem.Header).bucket },
                            ) { item ->
                                when (item) {
                                    is TimelineItem.Header -> BucketHeader(item.bucket)
                                    is TimelineItem.Row -> SessionRow(
                                        item,
                                        time = formatSessionTime(item.session.lastActive, clock.instant(), clock.zone, locale),
                                        onOpen = { onOpenChat(item.session.id, item.session.title, item.session.isCron) },
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
            if (state.searchOpen) {
                SearchBar(state.query, viewModel::search, onClose = viewModel::closeSearch, Modifier.align(Alignment.BottomCenter))
            } else {
                FilledTonalIconButton(
                    viewModel::openSearch,
                    Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp).size(BUTTON_SIZE),
                ) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                FilledIconButton(
                    { onOpenChat(null, null, false) },
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).size(BUTTON_SIZE),
                ) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
            }
        }
    }
}

/** The search field above the keyboard, with the round button that closes search beside it. */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            query, onQueryChange,
            Modifier.weight(1f).focusRequester(focusRequester).testTag("search"),
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton({ onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
            },
            singleLine = true,
            shape = CircleShape,
        )
        FilledTonalIconButton(
            {
                keyboard?.hide()
                onClose()
            },
            Modifier.size(BUTTON_SIZE),
        ) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
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
    time: String,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onArchive: () -> Unit,
) {
    val session = row.session
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindIcon(session)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.needsAnswer) Tag("approval", MaterialTheme.colorScheme.tertiaryContainer)
                    Text(
                        session.displayTitle, Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    time,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.unread) {
                Spacer(Modifier.width(12.dp))
                UnreadDot()
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

/** A search hit: the session, and the text around the match with the match in bold. */
@Composable
private fun SearchResultRow(hit: SearchHit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindIcon(hit.session)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.session.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hit.snippet?.let {
                Text(
                    it.annotated(),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Snippet.annotated(): AnnotatedString = buildAnnotatedString {
    append(text)
    matches.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.first, it.last + 1) }
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
