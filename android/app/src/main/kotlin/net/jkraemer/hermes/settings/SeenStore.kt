package net.jkraemer.hermes.settings

import android.content.SharedPreferences
import java.time.Instant

/**
 * Remembers when each session was last opened, and up to which run each cron job was seen, to tell which ones
 * changed since. Sessions and jobs never opened count as seen at [baseline], the first start of the app, so
 * installing it does not mark the whole history unread.
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

    /** The time of the newest run the job's feed showed when it was last open; null when it never was. */
    fun jobSeenUntil(jobId: String): Instant? =
        prefs.getLong(JOB_SEEN_UNTIL_PREFIX + jobId, -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

    /** Records that the job was seen up to [until]; an earlier time than the one recorded changes nothing. */
    fun markJobSeen(jobId: String, until: Instant) {
        val seen = jobSeenUntil(jobId)
        if (seen == null || until > seen) prefs.edit().putLong(JOB_SEEN_UNTIL_PREFIX + jobId, until.toEpochMilli()).apply()
    }

    /** When old cron runs were last archived; null before the first time. */
    fun lastArchivePass(): Instant? = prefs.getLong(ARCHIVE_PASS_KEY, -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

    fun markArchivePass(at: Instant) {
        prefs.edit().putLong(ARCHIVE_PASS_KEY, at.toEpochMilli()).apply()
    }

    private companion object {
        const val BASELINE_KEY = "seen_baseline"
        const val LAST_OPENED_KEY = "last_opened"
        const val JOB_SEEN_UNTIL_PREFIX = "job_seen_until:"
        const val ARCHIVE_PASS_KEY = "cron_archive_pass"
    }
}
