package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.chat.ChatItem
import net.jkraemer.hermes.core.chat.ChatState

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

fun ChatState.hasFinishedReplyContaining(text: String) =
    items.any { it is ChatItem.Assistant && !it.streaming && it.markdown.contains(text, ignoreCase = true) }

/** Waits for [predicate], returning null on timeout or when the agent turn ended in an error. */
suspend fun ChatController.awaitState(timeoutMs: Long = MODEL_TIMEOUT_MS, predicate: (ChatState) -> Boolean): ChatState? =
    withTimeoutOrNull(timeoutMs) { state.first { predicate(it) || it.hasError() } }?.takeIf(predicate)

/** Sends [prompt] and waits for a finished assistant message containing [expected]. */
suspend fun ChatController.sendAndAwaitReply(prompt: String, expected: String): ChatState {
    send(prompt)
    return awaitState { it.hasFinishedReplyContaining(expected) }
        ?: throw AssertionError("no reply containing '$expected' (waited up to ${MODEL_TIMEOUT_MS / 1000} s): ${state.value.lastAgentOutput()}")
}

fun failMisstep(chat: ChatController): Nothing =
    throw AssertionError("agent did not follow the prompt (model misstep, not an app bug): ${chat.state.value.lastAgentOutput()}")
