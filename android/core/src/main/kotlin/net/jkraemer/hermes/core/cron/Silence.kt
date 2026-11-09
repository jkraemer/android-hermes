package net.jkraemer.hermes.core.cron

/**
 * The replies by which an agent says it has nothing to report. Hermes' own list
 * (`gateway.response_filters.LIVE_GATEWAY_SILENT_MARKERS`), translated forms included.
 */
private val SILENCE_MARKERS = setOf("[SILENT]", "SILENT", "NO_REPLY", "NO REPLY", "[静默]", "静默", "[沉默]", "沉默")

private val BRACKETED_SILENCE_MARKERS = SILENCE_MARKERS.filter { it.startsWith("[") }

/** Longer than any marker could plausibly be, even with stray punctuation. */
private const val MARKER_LENGTH_CAP = 64

private val PUNCTUATION_TYPES = setOf(
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
).map { it.toInt() }.toSet()

private val LINE_BREAK = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085  ]")

/**
 * Whether a cron run's final reply means the run had nothing to report, so Hermes delivered nothing: a silence
 * marker as the whole reply or as its own first or last line, or the bracketed marker opening the reply;
 * trimmed, case-insensitive, stray punctuation around it ignored. A marker mid-sentence is real content.
 * A port of Hermes' `is_autonomous_silence_response`, which the cron scheduler decides delivery by.
 */
fun isSilent(text: String): Boolean {
    val stripped = text.trim()
    if (stripped.isEmpty()) return false
    val lines = stripped.split(LINE_BREAK).filter { it.isNotBlank() }
    return BRACKETED_SILENCE_MARKERS.any { stripped.uppercase().startsWith(it) } ||
        listOf(stripped, lines.first(), lines.last()).any(::isMarker)
}

/** Whether [text] is exactly a marker, give or take whitespace, case and punctuation at its edges. */
private fun isMarker(text: String): Boolean {
    val stripped = text.trim()
    if (stripped.codePointCount(0, stripped.length) !in 1..MARKER_LENGTH_CAP) return false
    val depunctuated = stripEdgePunctuation(stripped)
    return listOf(stripped, depunctuated).any { canonical(it) in SILENCE_MARKERS }
}

/**
 * What Python's `str.split()` splits at: Kotlin's whitespace (which covers U+001C..U+001F and the space separators)
 * plus NEL. Deliberately not a regex: Android's ICU engine rejects the Unicode-character-class flag that
 * a whitespace pattern would need, and compiling one crashed the app on a phone.
 */
private fun Char.isPythonWhitespace() = isWhitespace() || this == '\u0085'

/** Upper case, with every run of whitespace one space. */
private fun canonical(text: String): String =
    text.uppercase().split { it.isPythonWhitespace() }.filter { it.isNotEmpty() }.joinToString(" ")

private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    for (i in indices) if (isSeparator(this[i])) {
        parts += substring(start, i)
        start = i + 1
    }
    parts += substring(start)
    return parts
}

/** Square brackets stay, so a malformed `[SILENT` cannot become `SILENT`. */
private fun isEdgePunctuation(codePoint: Int) =
    codePoint != '['.code && codePoint != ']'.code && Character.getType(codePoint) in PUNCTUATION_TYPES

private fun stripEdgePunctuation(text: String): String {
    var start = 0
    var end = text.length
    while (start < end && isEdgePunctuation(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start))
    while (end > start && isEdgePunctuation(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end))
    return text.substring(start, end).trim()
}
