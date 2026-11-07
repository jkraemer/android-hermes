package net.jkraemer.hermes.core.timeline

import net.jkraemer.hermes.core.cron.CronJobSummary
import net.jkraemer.hermes.core.sessions.SessionSummary
import java.time.Instant
import java.time.ZoneId

enum class Bucket { NEEDS_ANSWER, PINNED, TODAY, YESTERDAY, OLDER }

sealed interface TimelineItem {
    data class Header(val bucket: Bucket) : TimelineItem
    data class Row(val session: SessionSummary, val unread: Boolean, val needsAnswer: Boolean) : TimelineItem

    /** A cron job, which stands for all of its runs. */
    data class Job(val summary: CronJobSummary) : TimelineItem
}

/**
 * Groups [sessions] and [jobs] into buckets, newest first within each, with a header before every non-empty
 * bucket. A session is unread when it was active after it was last opened, or after [seenBaseline] when it
 * never was. Cron runs are not listed: their jobs are, in the bucket of the day they last reported something;
 * a job that never did is left out.
 */
fun buildTimeline(
    sessions: List<SessionSummary>,
    jobs: List<CronJobSummary>,
    now: Instant,
    zone: ZoneId,
    lastOpened: Map<String, Instant>,
    seenBaseline: Instant,
    needsAnswer: Set<String>,
): List<TimelineItem> {
    val today = now.atZone(zone).toLocalDate()
    fun dayBucket(time: Instant): Bucket {
        val day = time.atZone(zone).toLocalDate()
        return when {
            !day.isBefore(today) -> Bucket.TODAY
            day == today.minusDays(1) -> Bucket.YESTERDAY
            else -> Bucket.OLDER
        }
    }
    fun bucketOf(session: SessionSummary): Bucket = when {
        session.id in needsAnswer -> Bucket.NEEDS_ANSWER
        session.pinned -> Bucket.PINNED
        else -> dayBucket(session.lastActive)
    }

    class Placed(val bucket: Bucket, val time: Instant, val item: TimelineItem)
    val sessionRows = sessions.filter { it.isListed }.map { Placed(bucketOf(it), it.lastActive, rowOf(it, lastOpened, seenBaseline, needsAnswer)) }
    val jobRows = jobs.mapNotNull { job -> job.time?.let { Placed(dayBucket(it), it, TimelineItem.Job(job)) } }
    val byBucket = (sessionRows + jobRows).sortedByDescending { it.time }.groupBy({ it.bucket }, { it.item })
    return Bucket.entries.flatMap { bucket ->
        val inBucket = byBucket[bucket] ?: return@flatMap emptyList()
        listOf<TimelineItem>(TimelineItem.Header(bucket)) + inBucket
    }
}

/** A chat that was opened but never written to (e.g. the one on screen right now) has nothing to list. */
private val SessionSummary.hasMessages: Boolean get() = messageCount > 0

/** Whether the session is listed as itself: a cron run is listed through its job. */
private val SessionSummary.isListed: Boolean get() = hasMessages && !isCron

private fun rowOf(session: SessionSummary, lastOpened: Map<String, Instant>, seenBaseline: Instant, needsAnswer: Set<String>) =
    TimelineItem.Row(
        session,
        unread = session.lastActive > (lastOpened[session.id] ?: seenBaseline),
        needsAnswer = session.id in needsAnswer,
    )

/** What the navigation drawer lists: the cron jobs, every pinned session, and the newest other chats. */
data class DrawerEntries(
    val pinned: List<TimelineItem.Row>,
    val recents: List<TimelineItem.Row>,
    val scheduled: List<CronJobSummary> = emptyList(),
)

private const val DRAWER_RECENTS = 10

/**
 * The sessions are newest first; the recents leave out cron runs, whose [jobs] are listed instead, and are cut
 * off after [DRAWER_RECENTS]. The jobs are newest first by their time; those that never reported anything come
 * last, by name.
 */
fun buildDrawerEntries(
    sessions: List<SessionSummary>,
    jobs: List<CronJobSummary>,
    lastOpened: Map<String, Instant>,
    seenBaseline: Instant,
    needsAnswer: Set<String>,
): DrawerEntries {
    val (pinned, unpinned) = sessions.filter { it.hasMessages }.sortedByDescending { it.lastActive }.partition { it.pinned }
    fun row(session: SessionSummary) = rowOf(session, lastOpened, seenBaseline, needsAnswer)
    val scheduled = jobs.sortedWith(compareByDescending<CronJobSummary, Instant?>(nullsFirst()) { it.time }.thenBy { it.job.name })
    return DrawerEntries(pinned.map(::row), unpinned.filter { it.isListed }.take(DRAWER_RECENTS).map(::row), scheduled)
}
