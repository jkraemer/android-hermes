package net.jkraemer.hermes.core.chat

/**
 * The work a cron run did before its final reply: the chat's items before [end], which are the job's prompt and
 * everything the agent did on the way. [steps] counts them, each tool call on its own.
 */
data class RunWork(val end: Int, val steps: Int)

/**
 * The work in a cron run's chat [items]. The run is everything before the user's first own message (the first
 * message is the job's prompt); its final reply is its last assistant message with text, and while there is none
 * yet, the whole run is work. Null when there is nothing before the final reply.
 */
fun cronRunWork(items: List<ChatItem>): RunWork? {
    val runEnd = cronRunEnd(items) { it is ChatItem.User }
    val finalReply = (0 until runEnd).lastOrNull { val item = items[it]; item is ChatItem.Assistant && item.markdown.isNotBlank() }
    val end = finalReply ?: runEnd
    if (end == 0) return null
    val steps = items.subList(0, end).sumOf { item ->
        when (item) {
            is ChatItem.Tools -> item.calls.size
            is ChatItem.Assistant -> if (item.markdown.isBlank()) 0 else 1
            else -> 1
        }
    }
    return RunWork(end, steps)
}

/**
 * Where a cron run ends in its session's [messages]: at the user's first own message, since the first one is the
 * job's prompt; the whole transcript while there is none.
 */
fun <T> cronRunEnd(messages: List<T>, isUser: (T) -> Boolean): Int =
    messages.withIndex().filter { isUser(it.value) }.getOrNull(1)?.index ?: messages.size
