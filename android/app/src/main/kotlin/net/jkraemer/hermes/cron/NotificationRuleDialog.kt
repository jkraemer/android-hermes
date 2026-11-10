package net.jkraemer.hermes.cron

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.jkraemer.hermes.core.push.NotificationRule
import net.jkraemer.hermes.core.push.validPattern

private enum class Choice(val label: String) {
    Off("Off"),
    Always("Every report"),
    Match("Only when the report matches"),
}

/** Lets the user choose when the reports of the job called [jobName] are announced; [onSave] gets the new rule. */
@Composable
fun NotificationRuleDialog(jobName: String, rule: NotificationRule, onDismiss: () -> Unit, onSave: (NotificationRule) -> Unit) {
    var choice by remember {
        mutableStateOf(
            when (rule) {
                NotificationRule.Off -> Choice.Off
                NotificationRule.Always -> Choice.Always
                is NotificationRule.Match -> Choice.Match
            },
        )
    }
    var pattern by remember { mutableStateOf((rule as? NotificationRule.Match)?.pattern.orEmpty()) }
    val invalid = choice == Choice.Match && !validPattern(pattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifications for $jobName") },
        text = {
            Column(Modifier.selectableGroup()) {
                Choice.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(choice == option, onClick = { choice = option }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = spacedBy(8.dp),
                    ) {
                        RadioButton(choice == option, onClick = null)
                        Text(option.label)
                    }
                }
                if (choice == Choice.Match) {
                    OutlinedTextField(
                        pattern,
                        { pattern = it },
                        Modifier.fillMaxWidth().testTag("rule-field"),
                        label = { Text("Regular expression") },
                        isError = invalid,
                        singleLine = true,
                    )
                    if (invalid) Text("Not a valid regular expression", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                {
                    onSave(
                        when (choice) {
                            Choice.Off -> NotificationRule.Off
                            Choice.Always -> NotificationRule.Always
                            Choice.Match -> NotificationRule.Match(pattern)
                        },
                    )
                },
                enabled = !invalid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
