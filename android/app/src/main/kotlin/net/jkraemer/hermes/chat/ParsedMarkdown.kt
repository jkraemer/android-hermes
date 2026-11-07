package net.jkraemer.hermes.chat

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Finished replies' parsed Markdown, by their text: each parsed once, off the main thread, in [scope], and kept
 * while it lives, so a reply that scrolls back into view is laid out at its full height straight away.
 */
class ParsedMarkdown(private val scope: CoroutineScope) {
    private val _parsed = MutableStateFlow<Map<String, State.Success>>(emptyMap())
    val parsed: StateFlow<Map<String, State.Success>> = _parsed.asStateFlow()

    /** The texts whose parse has been started. */
    private val started = mutableSetOf<String>()

    fun parse(markdown: String) {
        if (!started.add(markdown)) return
        scope.launch {
            val parsed = parseMarkdownFlow(markdown).first { it !is State.Loading }
            if (parsed is State.Success) _parsed.update { it + (markdown to parsed) }
        }
    }
}
