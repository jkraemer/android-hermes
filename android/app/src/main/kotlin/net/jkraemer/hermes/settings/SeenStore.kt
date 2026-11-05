package net.jkraemer.hermes.settings

import android.content.SharedPreferences
import java.time.Instant

/**
 * Remembers when each session was last opened, to tell which ones changed since. Sessions
 * never opened count as seen at [baseline], the first start of the app, so installing it
 * does not mark the whole history unread.
 */
class SeenStore(private val prefs: SharedPreferences, now: () -> Instant = Instant::now) {
    val baseline: Instant

    init {
        if (!prefs.contains(BASELINE_KEY)) prefs.edit().putLong(BASELINE_KEY, now().toEpochMilli()).apply()
        baseline = Instant.ofEpochMilli(prefs.getLong(BASELINE_KEY, 0))
    }

    fun lastOpened(): Map<String, Instant> =
        prefs.getStringSet(LAST_OPENED_KEY, emptySet()).orEmpty().associate { entry ->
            // "<epoch millis>:<session id>"; the id may itself contain colons.
            val (millis, id) = entry.split(":", limit = 2)
            id to Instant.ofEpochMilli(millis.toLong())
        }

    fun markOpened(id: String, at: Instant) {
        val entries = (lastOpened() + (id to at)).map { (key, value) -> "${value.toEpochMilli()}:$key" }
        prefs.edit().putStringSet(LAST_OPENED_KEY, entries.toSet()).apply()
    }

    private companion object {
        const val BASELINE_KEY = "seen_baseline"
        const val LAST_OPENED_KEY = "last_opened"
    }
}
