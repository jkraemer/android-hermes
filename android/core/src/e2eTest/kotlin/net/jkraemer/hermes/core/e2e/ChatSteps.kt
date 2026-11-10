package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.jkraemer.hermes.core.chat.ApprovalChoice
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.chat.ChatState
import org.junit.Assert.fail
import java.io.File

const val MODEL_TIMEOUT_MS = 120_000L

/** The last thing the agent said, or the error it ended with, for failure messages. */
fun ChatState.lastAgentOutput(): String =
    items.lastOrNull { it is ChatItem.Assistant || it is ChatItem.Error }.let {
        when (it) {
            is ChatItem.Assistant -> it.markdown
            is ChatItem.Error -> "error: ${it.message}"
            else -> ""
        }
    }

private fun ChatState.hasError() = items.any { it is ChatItem.Error }

/** An error ending the turn is the provider or harness failing, which says nothing about the model or the app. */
private fun ChatController.failIfTurnErrored() {
    val error = state.value.items.lastOrNull { it is ChatItem.Error } as ChatItem.Error? ?: return
    throw AssertionError("turn ended in an error (provider/harness, not a model misstep): ${error.message}")
}

fun ChatState.hasFinishedReplyContaining(text: String) =
    items.any { it is ChatItem.Assistant && !it.streaming && it.markdown.contains(text, ignoreCase = true) }

/** Waits for [predicate], returning null on timeout or when the agent turn ended in an error. */
suspend fun ChatController.awaitState(timeoutMs: Long = MODEL_TIMEOUT_MS, predicate: (ChatState) -> Boolean): ChatState? =
    withTimeoutOrNull(timeoutMs) { state.first { predicate(it) || it.hasError() } }?.takeIf(predicate)

/** Sends [prompt] and waits for a finished assistant message containing [expected]. */
suspend fun ChatController.sendAndAwaitReply(prompt: String, expected: String): ChatState {
    send(prompt)
    awaitState { it.hasFinishedReplyContaining(expected) }?.let { return it }
    failIfTurnErrored()
    throw AssertionError("no reply containing '$expected' (waited up to ${MODEL_TIMEOUT_MS / 1000} s): ${state.value.lastAgentOutput()}")
}

fun failMisstep(chat: ChatController): Nothing {
    chat.failIfTurnErrored()
    throw AssertionError("agent did not follow the prompt (model misstep, not an app bug): ${chat.state.value.lastAgentOutput()}")
}

/** For waits after a card was answered: the agent had done what the prompt asked, so a hang is ours to explain. */
fun failTurnNotEnded(chat: ChatController): Nothing {
    chat.failIfTurnErrored()
    throw AssertionError("turn did not end after answering (protocol problem?): ${chat.state.value.lastAgentOutput()}")
}

fun ChatState.approval(): ChatItem.Approval? = items.filterIsInstance<ChatItem.Approval>().firstOrNull()
fun ChatState.clarify(): ChatItem.Clarify? = items.filterIsInstance<ChatItem.Clarify>().firstOrNull()

/** Asks the agent to remove [directory], a command that needs approval; waits for its card and returns the command and the card. */
suspend fun ChatController.requestRemoval(directory: File): Pair<String, ChatItem.Approval> {
    val command = "rm -rf ${directory.path}"
    send("Use the terminal tool to run exactly this command and nothing else: $command")
    val card = awaitState { it.approval() != null }?.approval() ?: failMisstep(this)
    return command to card
}

/** Answers anything but DENY only when the card is for exactly the [requested] command. */
suspend fun ChatController.answerGuarded(requested: String, card: ChatItem.Approval, choice: ApprovalChoice) {
    if (choice != ApprovalChoice.DENY && card.command.trim() != requested) {
        answerApproval(card.requestId, ApprovalChoice.DENY)
        fail("agent requested a different command (model misstep): ${card.command}")
    }
    answerApproval(card.requestId, choice)
}

/** Asks the agent to let the user choose between red and blue with the clarify tool; waits for its card. */
suspend fun ChatController.requestColourChoice(): ChatItem.Clarify {
    send("Before doing anything, use the clarify tool to ask me to choose between the options red and blue, then reply with the colour I chose.")
    return awaitState { it.clarify() != null }?.clarify() ?: failMisstep(this)
}
