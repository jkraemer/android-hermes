package net.jkraemer.hermes.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.chat.ClarifyQuestion

/** What the user picked for one question; "Other…" adds free text of their own. */
private class Answer(val multiSelect: Boolean) {
    var selected by mutableStateOf(emptyList<String>())
    var other by mutableStateOf<String?>(null)

    fun toggle(choice: String) {
        selected = when {
            choice in selected -> selected - choice
            multiSelect -> selected + choice
            else -> listOf(choice)
        }
        // A single-select question takes either a choice or the free text.
        if (!multiSelect && selected.isNotEmpty()) other = null
    }

    fun toggleOther() {
        other = if (other == null) "" else null
    }

    fun typeOther(text: String) {
        other = text
        if (!multiSelect) selected = emptyList()
    }

    fun values(): List<String> = selected + listOfNotNull(other?.takeIf { it.isNotBlank() })
}

@Composable
fun ClarifyCard(item: ChatItem.Clarify, onSend: (Map<String, List<String>>) -> Unit, modifier: Modifier = Modifier) {
    val answers = remember(item.requestId) { item.questions.associate { it.qid to Answer(it.multiSelect) } }
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val closed = item.status.closedLabel()
            for (question in item.questions) {
                Question(question, answers.getValue(question.qid), enabled = closed == null)
            }
            if (closed != null) {
                Text(closed, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Button(
                    onClick = { onSend(answers.mapValues { it.value.values() }) },
                    enabled = answers.values.all { it.values().isNotEmpty() },
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun Question(question: ClarifyQuestion, answer: Answer, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(question.question, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in question.choices) {
                FilterChip(choice in answer.selected, { answer.toggle(choice) }, { Text(choice) }, enabled = enabled)
            }
            FilterChip(answer.other != null, answer::toggleOther, { Text("Other…") }, enabled = enabled)
        }
        answer.other?.let {
            OutlinedTextField(
                it, answer::typeOther,
                Modifier.fillMaxWidth().testTag("other-${question.qid}"),
                enabled = enabled,
                singleLine = true,
            )
        }
    }
}
