package net.jkraemer.hermes.core.sessions

import java.time.Instant

/** Chat-platform sessions are owned by their gateways and never shown in the app. */
val EXCLUDED_SOURCES = listOf("telegram", "mattermost")

data class SessionSummary(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String,
    val startedAt: Instant,
    val lastActive: Instant,
    val messageCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
) {
    val isCron: Boolean get() = source == "cron"
}

data class SessionPage(val sessions: List<SessionSummary>, val total: Int)

/**
 * A search result: the session and the text around the match, if the server sent any.
 */
data class SearchHit(val session: SessionSummary, val snippet: Snippet?)

/** One line of [text] with the matched spans as [matches] (inclusive index ranges into [text]). */
data class Snippet(val text: String, val matches: List<IntRange>)

private const val MATCH_START = ">>>"
private const val MATCH_END = "<<<"

/**
 * Turns an FTS5 snippet, whose matches sit between `>>>` and `<<<`, into one line of text:
 * markers removed, whitespace runs collapsed to a single space, ends trimmed.
 */
fun parseSnippet(raw: String): Snippet {
    val text = StringBuilder()
    val matches = mutableListOf<IntRange>()
    var matchStart: Int? = null
    var pendingSpace = false
    var i = 0
    fun closeMatch() {
        matchStart?.let { if (text.length > it) matches += it..text.length - 1 }
        matchStart = null
    }
    fun append(c: Char) {
        if (c.isWhitespace()) {
            pendingSpace = text.isNotEmpty()
            return
        }
        if (pendingSpace) text.append(' ')
        pendingSpace = false
        text.append(c)
    }
    while (i < raw.length) {
        when {
            raw.startsWith(MATCH_START, i) -> {
                // A space before the match belongs before the span, not inside it.
                if (pendingSpace) { text.append(' '); pendingSpace = false }
                matchStart = text.length
                i += MATCH_START.length
            }
            raw.startsWith(MATCH_END, i) -> {
                closeMatch()
                i += MATCH_END.length
            }
            else -> append(raw[i++])
        }
    }
    closeMatch()
    return Snippet(text.toString(), matches)
}
