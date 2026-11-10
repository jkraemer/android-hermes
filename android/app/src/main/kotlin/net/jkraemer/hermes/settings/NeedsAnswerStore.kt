package net.jkraemer.hermes.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * The sessions with a question or approval that a push announced and nobody has answered yet. A request stays until
 * it is resolved, its session's chat shows no open card, or its deadline (when Hermes gives up waiting) passes, so
 * a missed `resolved` push cannot keep a session in "Needs answer" for good.
 */
class NeedsAnswerStore(private val prefs: SharedPreferences, private val clock: () -> Instant = Instant::now) {
    private data class Entry(val sessionId: String, val requestId: String, val deadline: Instant)

    private val _sessions = MutableStateFlow(emptySet<String>())
    val sessions: StateFlow<Set<String>> = _sessions

    init {
        change { it }
    }

    fun add(sessionId: String, requestId: String, deadline: Instant) =
        change { entries -> entries.filter { it.requestId != requestId } + Entry(sessionId, requestId, deadline) }

    fun resolve(requestId: String) = change { entries -> entries.filter { it.requestId != requestId } }

    fun clearSession(sessionId: String) = change { entries -> entries.filter { it.sessionId != sessionId } }

    /** Forgets the requests whose deadline has passed; nothing else does so while no push or chat touches the store. */
    fun dropExpired() = change { it }

    @Synchronized
    private fun change(transform: (List<Entry>) -> List<Entry>) {
        val now = clock()
        val entries = transform(load()).filter { it.deadline > now }
        prefs.edit().putString(ENTRIES_KEY, save(entries)).apply()
        _sessions.value = entries.map { it.sessionId }.toSet()
    }

    private fun load(): List<Entry> {
        val stored = prefs.getString(ENTRIES_KEY, null) ?: return emptyList()
        val array = JSONArray(stored)
        return (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            Entry(entry.getString("session"), entry.getString("request"), Instant.ofEpochMilli(entry.getLong("deadline")))
        }
    }

    private fun save(entries: List<Entry>): String =
        JSONArray(
            entries.map { JSONObject().put("session", it.sessionId).put("request", it.requestId).put("deadline", it.deadline.toEpochMilli()) },
        ).toString()

    companion object {
        private const val ENTRIES_KEY = "needs_answer"

        /** How long a question stays unanswered before it no longer counts. */
        val CLARIFY_TIMEOUT: Duration = Duration.ofHours(1)
    }
}
