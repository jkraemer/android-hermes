package net.jkraemer.hermes.cron

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.model.State
import net.jkraemer.hermes.chat.Composer
import net.jkraemer.hermes.chat.Reply
import net.jkraemer.hermes.core.cron.ShownRun
import net.jkraemer.hermes.core.push.NotificationRule
import net.jkraemer.hermes.sessions.formatRunTime
import java.time.Clock

/**
 * What a cron job called [name] reported, one entry per run, the newest at the bottom above the composer.
 * [onOpenMenu] opens the navigation drawer, [onOpenRun] gets the session of a run to show whole, and [onReply]
 * the newest reported run and the text the user answered it with. [rule] says when the job's reports are announced
 * and [onSaveRule] gets the one the user chose instead. [clock] decides which day is today.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JobFeedScreen(
    viewModel: JobFeedViewModel,
    name: String,
    onOpenMenu: () -> Unit,
    onOpenRun: (sessionId: String) -> Unit,
    onReply: (sessionId: String, text: String) -> Unit,
    rule: NotificationRule,
    onSaveRule: (NotificationRule) -> Unit,
    clock: Clock = Clock.systemDefaultZone(),
    keyboardVisible: Boolean = WindowInsets.isImeVisible,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val parsedReplies by viewModel.parsedReplies.collectAsStateWithLifecycle()
    val newest = state.entries.lastOrNull()

    // Saved with the screen, so a recreated screen (a rotation) does not count as shown again, while one that
    // was left for a run's chat and is back does.
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!shown) {
            shown = true
            viewModel.shown()
        }
    }

    var editingRule by rememberSaveable { mutableStateOf(false) }
    if (editingRule) {
        NotificationRuleDialog(name, rule, onDismiss = { editingRule = false }, onSave = {
            onSaveRule(it)
            editingRule = false
        })
    }

    Scaffold(
        modifier = Modifier.testTag("job-feed-screen").imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onOpenMenu) { Icon(Icons.Filled.Menu, contentDescription = "Menu") } },
                actions = { IconButton({ editingRule = true }) { Icon(Icons.Filled.Notifications, contentDescription = "Notifications") } },
            )
        },
    ) { padding ->
        // The composer floats over the end of the feed, as in a chat.
        val density = LocalDensity.current
        var composerHeight by remember { mutableStateOf(0.dp) }
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.entries.isNotEmpty() -> Entries(state.entries, parsedReplies, clock, composerHeight, onOpenRun)
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("feed-loading"))
                state.failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load the runs", color = MaterialTheme.colorScheme.error)
                    TextButton(viewModel::refresh) { Text("Retry") }
                }
                else -> Text(
                    "Nothing reported yet",
                    Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Composer(
                running = false,
                canSend = newest != null,
                onSend = { text -> newest?.let { onReply(it.sessionId, text) } },
                onStop = {},
                keyboardVisible = keyboardVisible,
                prefill = null,
                onPrefillShown = {},
                commands = null,
                slashMenu = false,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { composerHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

/** The entries from the bottom up, so the feed opens at the newest and older ones arriving late land above it. */
@Composable
private fun Entries(
    entries: List<ShownRun>,
    parsedReplies: Map<String, State.Success>,
    clock: Clock,
    composerHeight: Dp,
    onOpenRun: (String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        Modifier.fillMaxSize().testTag("job-feed"),
        reverseLayout = true,
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = composerHeight + 8.dp),
        verticalArrangement = spacedBy(24.dp),
    ) {
        items(entries.asReversed(), key = { it.sessionId }) { entry ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = spacedBy(4.dp)) {
                Text(
                    formatRunTime(entry.time, clock.instant(), clock.zone, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Reply(entry.reply, parsedReplies[entry.reply])
                TextButton({ onOpenRun(entry.sessionId) }) { Text("Show the whole run") }
            }
        }
    }
}
