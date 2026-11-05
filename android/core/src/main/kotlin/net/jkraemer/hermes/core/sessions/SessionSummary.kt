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
