package net.jkraemer.hermes.core.cron

import java.util.concurrent.ConcurrentHashMap

/**
 * The final replies of cron runs, fetched by session id with [fetch]. A finished run's reply does not change,
 * so it is fetched once and kept for as long as this lives; a running run's is fetched every time.
 */
class FinalReplies(private val fetch: suspend (sessionId: String) -> String?) {
    /** A wrapper, since the map cannot hold "no reply" as null. */
    private data class Reply(val text: String?)

    private val finished = ConcurrentHashMap<String, Reply>()

    suspend fun of(run: CronRun): String? {
        finished[run.sessionId]?.let { return it.text }
        val text = fetch(run.sessionId)
        if (!run.running) finished[run.sessionId] = Reply(text)
        return text
    }
}
