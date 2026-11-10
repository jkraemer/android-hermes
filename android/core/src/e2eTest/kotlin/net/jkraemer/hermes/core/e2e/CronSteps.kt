package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.cron.CronRun
import org.junit.Assert.assertEquals
import java.util.UUID

/** A job prompt whose run reports the single word cron-ok. */
const val REPORTING_JOB_PROMPT =
    "This run always has news to report. Your report is the single word cron-ok, so your final response must be exactly: cron-ok"

/** A job prompt whose run has nothing to report. */
const val SILENT_JOB_PROMPT = "There is never anything new to report. Respond with exactly [SILENT]"

/** A name no earlier job of this long-lived Hermes home has. */
fun uniqueName(prefix: String) = prefix + "-" + UUID.randomUUID().toString().take(8)

/**
 * Creates a job that runs [prompt] on [schedule] (by default daily, so it runs only when triggered) and returns its id;
 * the test must delete it with [deleteCronJobs].
 */
suspend fun E2eClient.createCronJob(name: String, prompt: String, schedule: String = "every 1d"): String {
    val body = buildJsonObject {
        put("name", name)
        put("schedule", schedule)
        put("prompt", prompt)
        put("deliver", "local")
    }.toString()
    return Json.parseToJsonElement(rest("POST", "api/cron/jobs", body)).jsonObject.getValue("id").jsonPrimitive.content
}

/** Runs the job now; Hermes answers once the run has finished. Returns the job's one run. */
suspend fun E2eClient.runOnce(jobId: String): CronRun {
    rest("POST", "api/cron/jobs/$jobId/trigger")
    val runs = cron.runs(jobId).runs
    assertEquals("runs of $jobId: $runs", 1, runs.size)
    return runs.single()
}

/** Waits up to [timeoutMs] for the scheduler to have run the job once to the end; null when it has not. */
suspend fun E2eClient.awaitScheduledRun(jobId: String, timeoutMs: Long): CronRun? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val runs = cron.runs(jobId).runs
        if (runs.isNotEmpty() && runs.none { it.running }) return runs.single()
        delay(2_000)
    }
    return null
}

/** Deletes the jobs, as far as they still exist. */
suspend fun E2eClient.deleteCronJobs(jobIds: List<String>) {
    jobIds.forEach { runCatching { rest("DELETE", "api/cron/jobs/$it") } }
}
