package net.jkraemer.hermes.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jkraemer.hermes.core.cron.CronJobSummary
import net.jkraemer.hermes.core.timeline.DrawerEntries
import net.jkraemer.hermes.core.timeline.TimelineItem

/**
 * The navigation drawer: the app name with search and new chat, the cron jobs, the pinned sessions, the newest
 * others, a link to the full list, and Settings. [onOpenChat] gets the stored session id, its title and whether it is a cron
 * run; [onOpenJob] a cron job's id and name.
 */
@Composable
fun SessionsDrawerContent(
    entries: DrawerEntries,
    onSearch: () -> Unit,
    onNewChat: () -> Unit,
    onOpenChat: (id: String, title: String?, cron: Boolean) -> Unit,
    onOpenJob: (jobId: String, name: String) -> Unit,
    onViewAll: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hermes", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                IconButton(onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                IconButton(onNewChat) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
            }
        }
        if (entries.scheduled.isNotEmpty()) {
            item(key = "scheduled-heading") { DrawerHeading("Scheduled") }
            items(entries.scheduled, key = { "job-" + it.job.id }) { DrawerJob(it, onOpenJob) }
        }
        if (entries.pinned.isNotEmpty()) {
            item(key = "pinned-heading") { DrawerHeading("Pinned") }
            items(entries.pinned, key = { "pinned-" + it.session.id }) { DrawerEntry(it, onOpenChat) }
        }
        if (entries.recents.isNotEmpty()) {
            item(key = "recents-heading") { DrawerHeading("Recents") }
            items(entries.recents, key = { "recent-" + it.session.id }) { DrawerEntry(it, onOpenChat) }
        }
        item(key = "view-all") {
            Text(
                "View all",
                Modifier.fillMaxWidth().clickable(onClick = onViewAll).padding(horizontal = 24.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item(key = "settings") {
            Text(
                "Settings",
                Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(horizontal = 24.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun DrawerHeading(text: String) {
    Text(
        text,
        Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerJob(summary: CronJobSummary, onOpenJob: (jobId: String, name: String) -> Unit) {
    DrawerRow(summary.job.name, summary.unread, onClick = { onOpenJob(summary.job.id, summary.job.name) }) { JobIcon(size = 20.dp) }
}

@Composable
private fun DrawerEntry(row: TimelineItem.Row, onOpenChat: (id: String, title: String?, cron: Boolean) -> Unit) {
    DrawerRow(row.session.displayTitle, row.unread, onClick = { onOpenChat(row.session.id, row.session.title, row.session.isCron) }) {
        KindIcon(row.session, size = 20.dp)
    }
}

/** One line of the drawer: [icon], [title] and the unread dot. */
@Composable
private fun DrawerRow(title: String, unread: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (unread) {
            Spacer(Modifier.width(12.dp))
            UnreadDot()
        }
    }
}
