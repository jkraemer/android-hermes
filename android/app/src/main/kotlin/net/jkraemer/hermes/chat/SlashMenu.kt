package net.jkraemer.hermes.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jkraemer.hermes.core.commands.SlashEntry

/** The commands to pick from, in [entries]' order under a heading for each group; a tap hands the entry to [onPick]. */
@Composable
internal fun SlashMenu(entries: List<SlashEntry>, onPick: (SlashEntry) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
    ) {
        LazyColumn(Modifier.heightIn(max = 320.dp).testTag("slash-menu")) {
            val firstOfGroup = entries.indices.filter { it == 0 || entries[it].group != entries[it - 1].group }.toSet()
            items(entries.size, key = { entries[it].name }) { index ->
                val entry = entries[index]
                Column {
                    if (index in firstOfGroup) {
                        Text(
                            entry.group,
                            Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Asks whether to switch to a model Hermes warned about; [pending] says why. */
@Composable
fun ModelConfirmDialog(pending: PendingModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onConfirm) { Text("Switch") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        text = { Text(pending.message) },
    )
}
