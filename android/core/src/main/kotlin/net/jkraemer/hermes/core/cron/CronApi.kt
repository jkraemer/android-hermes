package net.jkraemer.hermes.core.cron

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jkraemer.hermes.core.bodyOf
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime

/** [lastRunAt] is when its last run finished; null before the first. */
data class CronJob(val id: String, val name: String, val lastRunAt: Instant?)

/**
 * One run of a cron job: the session it ran in. [running] while the scheduler still owns it, so its final reply
 * is not there yet. [archived] runs are hidden from Hermes' session lists. [lastActive] is when anything last
 * happened in the session, which is later than the run when the user went on with it.
 */
data class CronRun(
    val sessionId: String,
    val startedAt: Instant,
    val running: Boolean,
    val archived: Boolean,
    val lastActive: Instant = startedAt,
)

@Serializable
private data class JobRow(
    val id: String,
    val name: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("is_default_profile") val isDefaultProfile: Boolean = true,
)

@Serializable
private data class RunRow(
    val id: String,
    val source: String? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("scheduler_owned") val schedulerOwned: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
private data class RunsResponse(val runs: List<RunRow>)

/** Hermes' scheduled jobs and their runs. [client] must carry the dashboard session cookies. */
class CronApi(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** The jobs of the default profile, the only one the app works with; Hermes lists every profile's. */
    suspend fun jobs(): List<CronJob> = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegments("api/cron/jobs").build()
        json.decodeFromString<List<JobRow>>(get(url)).filter { it.isDefaultProfile }.map {
            CronJob(it.id, it.name?.takeIf(String::isNotBlank) ?: it.id, it.lastRunAt?.let { time -> runCatching { OffsetDateTime.parse(time).toInstant() }.getOrNull() })
        }
    }

    /**
     * The job's newest [limit] runs, newest first. Hermes also lists the output of runs that had no agent
     * session (script-only jobs); those have nothing to open and are left out.
     */
    suspend fun runs(jobId: String, limit: Int = MAX_RUNS): List<CronRun> = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegments("api/cron/jobs").addPathSegment(jobId).addPathSegment("runs")
            .addQueryParameter("limit", limit.coerceIn(1, MAX_RUNS).toString())
            .build()
        json.decodeFromString<RunsResponse>(get(url)).runs.filter { it.source == "cron" }.map {
            CronRun(it.id, it.startedAt.toInstant(), running = it.schedulerOwned, archived = it.archived, lastActive = (it.lastActive ?: it.startedAt).toInstant())
        }
    }

    private fun get(url: HttpUrl): String = client.bodyOf(Request.Builder().url(url).build())

    private fun Double.toInstant(): Instant = Instant.ofEpochMilli((this * 1000).toLong())

    companion object {
        /** The most runs Hermes lists at once. */
        const val MAX_RUNS = 100
    }
}
