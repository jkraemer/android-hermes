package net.jkraemer.hermes.core.cron

import java.time.Duration
import java.time.Instant

/** A run that reported something: when it ran and the reply it delivered. */
data class ShownRun(val sessionId: String, val time: Instant, val reply: String)

/**
 * A job as the lists show it. [latestShownRun] is its newest run that reported something, null when none has;
 * [unread] when that run is newer than the job was last seen. [runs] are its unarchived runs, newest first.
 */
data class CronJobSummary(val job: CronJob, val latestShownRun: ShownRun?, val unread: Boolean, val runs: List<CronRun>) {
    /** The job's place in time: when it last reported something. */
    val time: Instant? get() = latestShownRun?.time
}

/** Runs older than this are archived once read, or once known to be silent. */
val ARCHIVE_AFTER: Duration = Duration.ofDays(7)

/**
 * [job] with its [runs] (newest first), seen up to [seenUntil]. Asks [replyOf] for the replies of the newest
 * finished runs only until one is not silent.
 */
suspend fun summarizeJob(job: CronJob, runs: List<CronRun>, seenUntil: Instant, replyOf: suspend (CronRun) -> String?): CronJobSummary {
    val listed = runs.filter { !it.archived }
    val latest = listed.filter { !it.running }.firstNotNullOfOrNull { run -> shown(run, replyOf(run)) }
    return CronJobSummary(job, latest, unread = false, listed).seenUpTo(seenUntil)
}

/** The summary with the job seen up to [seenUntil]: unread when its newest shown run is later. */
fun CronJobSummary.seenUpTo(seenUntil: Instant): CronJobSummary =
    copy(unread = latestShownRun != null && latestShownRun.time > seenUntil)

/** What a job's feed shows: of [runs], those whose reply is in [replies] and not silent, oldest first. */
fun feedEntries(runs: List<CronRun>, replies: Map<String, String?>): List<ShownRun> =
    runs.filter { !it.archived && !it.running }.mapNotNull { shown(it, replies[it.sessionId]) }.sortedBy { it.time }

/**
 * The runs among [runs] to archive at [now]: those last active more than [ARCHIVE_AFTER] ago (a run the user went
 * on with counts from then) that were read (ran no later than [seenUntil]) or that were silent, which is never
 * shown and so never read. [replyOf] is asked only for unread ones.
 */
suspend fun runsToArchive(runs: List<CronRun>, seenUntil: Instant, now: Instant, replyOf: suspend (CronRun) -> String?): List<CronRun> =
    runs.filter { !it.archived && !it.running && it.lastActive < now.minus(ARCHIVE_AFTER) }
        .filter { it.startedAt <= seenUntil || replyOf(it)?.let(::isSilent) == true }

private fun shown(run: CronRun, reply: String?): ShownRun? =
    reply?.takeUnless(::isSilent)?.let { ShownRun(run.sessionId, run.startedAt, it) }
