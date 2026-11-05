package net.jkraemer.hermes.core.timeline

import net.jkraemer.hermes.core.sessions.SessionSummary
import java.time.Instant
import java.time.ZoneId

enum class Bucket { NEEDS_ANSWER, PINNED, TODAY, YESTERDAY, OLDER }

sealed interface TimelineItem {
    data class Header(val bucket: Bucket) : TimelineItem
    data class Row(val session: SessionSummary, val unread: Boolean, val needsAnswer: Boolean) : TimelineItem
}

/**
 * Groups [sessions] into buckets, newest first within each, with a header before every
 * non-empty bucket. A session is unread when it was active after it was last opened, or
 * after [seenBaseline] when it never was.
 */
fun buildTimeline(
    sessions: List<SessionSummary>,
    now: Instant,
    zone: ZoneId,
    lastOpened: Map<String, Instant>,
    seenBaseline: Instant,
    needsAnswer: Set<String>,
): List<TimelineItem> {
    val today = now.atZone(zone).toLocalDate()
    fun bucketOf(session: SessionSummary): Bucket {
        val day = session.lastActive.atZone(zone).toLocalDate()
        return when {
            session.id in needsAnswer -> Bucket.NEEDS_ANSWER
            session.pinned -> Bucket.PINNED
            !day.isBefore(today) -> Bucket.TODAY
            day == today.minusDays(1) -> Bucket.YESTERDAY
            else -> Bucket.OLDER
        }
    }

    val byBucket = sessions.sortedByDescending { it.lastActive }.groupBy(::bucketOf)
    return Bucket.entries.flatMap { bucket ->
        val inBucket = byBucket[bucket] ?: return@flatMap emptyList()
        listOf<TimelineItem>(TimelineItem.Header(bucket)) + inBucket.map {
            TimelineItem.Row(
                it,
                unread = it.lastActive > (lastOpened[it.id] ?: seenBaseline),
                needsAnswer = it.id in needsAnswer,
            )
        }
    }
}
