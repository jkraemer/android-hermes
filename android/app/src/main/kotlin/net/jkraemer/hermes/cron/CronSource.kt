package net.jkraemer.hermes.cron

import net.jkraemer.hermes.core.cron.CronApi
import net.jkraemer.hermes.core.cron.CronJob
import net.jkraemer.hermes.core.cron.CronRun
import net.jkraemer.hermes.core.cron.CronRunPage
import net.jkraemer.hermes.core.cron.FinalReplies

/** What the app needs from Hermes' cron jobs. */
interface CronSource {
    suspend fun jobs(): List<CronJob>

    /** The job's runs among its newest [limit] rows, newest first. */
    suspend fun runs(jobId: String, limit: Int): CronRunPage

    /** The reply [run] ended with, if any. */
    suspend fun finalReply(run: CronRun): String?
}

/** [api] and [replies] are read on every call because the server, and with them the API, can change. */
class CronApiSource(private val api: () -> CronApi, private val replies: () -> FinalReplies) : CronSource {
    override suspend fun jobs() = api().jobs()
    override suspend fun runs(jobId: String, limit: Int) = api().runs(jobId, limit)
    override suspend fun finalReply(run: CronRun) = replies().of(run)
}
