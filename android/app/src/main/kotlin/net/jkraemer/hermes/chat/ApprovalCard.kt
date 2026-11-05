package net.jkraemer.hermes.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.jkraemer.hermes.core.chat.ApprovalChoice
import net.jkraemer.hermes.core.chat.CardStatus
import net.jkraemer.hermes.core.chat.ChatItem
import java.time.Duration
import java.time.Instant

private val ApprovalChoice.buttonLabel: String
    get() = when (this) {
        ApprovalChoice.ONCE -> "Allow once"
        ApprovalChoice.SESSION -> "This session"
        ApprovalChoice.ALWAYS -> "Always"
        ApprovalChoice.DENY -> "Deny"
    }

/** What a card that is no longer open shows in place of its actions; null while it is open. */
internal fun CardStatus.closedLabel(): String? = when (this) {
    CardStatus.Open -> null
    is CardStatus.Answered -> label
    CardStatus.Expired -> "Expired"
    is CardStatus.Cancelled -> "Cancelled: $reason"
}

/** [clock] drives the countdown to the auto-deny; Hermes itself closes the card when it runs out. */
@Composable
fun ApprovalCard(item: ChatItem.Approval, clock: () -> Instant, onChoose: (ApprovalChoice) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approval needed", style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    item.command,
                    Modifier.fillMaxWidth().padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            val closed = item.status.closedLabel()
            if (closed != null) {
                ClosedLabel(closed)
            } else {
                val remaining = secondsUntil(item.deadline, clock)
                // Hermes denies on timeout and confirms with its own event; until then the card only stops offering answers.
                if (remaining == 0L) ClosedLabel("Expired") else Countdown(remaining)
                if (remaining > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in item.choices) ChoiceButton(choice) { onChoose(choice) }
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(choice: ApprovalChoice, onClick: () -> Unit) {
    when (choice) {
        ApprovalChoice.ONCE -> Button(onClick) { Text(choice.buttonLabel) }
        ApprovalChoice.DENY -> OutlinedButton(
            onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(choice.buttonLabel) }
        else -> OutlinedButton(onClick) { Text(choice.buttonLabel) }
    }
}

@Composable
private fun ClosedLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Whole seconds left until [deadline], re-read every second. */
@Composable
private fun secondsUntil(deadline: Instant, clock: () -> Instant): Long {
    val now by produceState(clock()) {
        while (true) {
            value = clock()
            delay(1_000)
        }
    }
    return Duration.between(now, deadline).seconds.coerceAtLeast(0)
}

@Composable
private fun Countdown(remaining: Long) {
    Text(
        "auto-deny in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
