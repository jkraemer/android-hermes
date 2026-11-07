package net.jkraemer.hermes.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.core.auth.NotLoggedInException
import net.jkraemer.hermes.core.cron.CronApi
import net.jkraemer.hermes.core.cron.CronJob
import net.jkraemer.hermes.core.cron.CronJobSummary
import net.jkraemer.hermes.core.cron.runsToArchive
import net.jkraemer.hermes.core.cron.seenUpTo
import net.jkraemer.hermes.core.cron.summarizeJob
import net.jkraemer.hermes.cron.CronSource
import net.jkraemer.hermes.core.sessions.SearchHit
import net.jkraemer.hermes.core.sessions.SessionPage
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.sessions.SessionsApi
import net.jkraemer.hermes.core.timeline.DrawerEntries
import net.jkraemer.hermes.core.timeline.TimelineItem
import net.jkraemer.hermes.core.timeline.buildDrawerEntries
import net.jkraemer.hermes.core.timeline.buildTimeline
import net.jkraemer.hermes.core.timeline.isListed
import net.jkraemer.hermes.settings.SeenStore
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What the session list needs from the server: the sessions, and the cron jobs it lists in place of their runs. */
interface SessionListSource : CronSource {
    suspend fun list(offset: Int, limit: Int): SessionPage
    suspend fun search(query: String, limit: Int): List<SearchHit>
    suspend fun update(id: String, title: String?, pinned: Boolean?, archived: Boolean?)
}

/** [api] is read on every call because the server, and with it the API, can change. */
class SessionsApiSource(private val api: () -> SessionsApi, cron: CronSource) : SessionListSource, CronSource by cron {
    override suspend fun list(offset: Int, limit: Int) = api().list(offset, limit)
    override suspend fun search(query: String, limit: Int) = api().search(query, limit)
    override suspend fun update(id: String, title: String?, pinned: Boolean?, archived: Boolean?) =
        api().update(id, title, pinned, archived)
}

enum class SessionListError { UNREACHABLE, FAILED }

data class SessionListState(
    val items: List<TimelineItem> = emptyList(),
    /** What the drawer lists; built from the same sessions as [items]. */
    val drawer: DrawerEntries = DrawerEntries(emptyList(), emptyList()),
    /** The hits of the current query; null while it is blank. They replace [items] on screen. */
    val results: List<SearchHit>? = null,
    val searchOpen: Boolean = false,
    val refreshing: Boolean = false,
    val query: String = "",
    val error: SessionListError? = null,
    val needsLogin: Boolean = false,
)

private data class Loaded(
    val sessions: List<SessionSummary> = emptyList(),
    val jobs: List<CronJobSummary> = emptyList(),
    val total: Int = 0,
    /** Null while the query is blank. */
    val searchResults: List<SearchHit>? = null,
    val refreshing: Boolean = false,
    val query: String = "",
    val searchOpen: Boolean = false,
    val error: SessionListError? = null,
    val needsLogin: Boolean = false,
)

/**
 * [needsAnswer] holds the stored ids of sessions waiting for the user. [clock] decides which
 * day counts as today. Loading the list also archives old cron runs, at most once a day, and
 * reports what fails at that to [log].
 */
class SessionListViewModel(
    private val source: SessionListSource,
    needsAnswer: StateFlow<Set<String>>,
    private val seen: SeenStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val log: HermesLog = HermesLog.None,
) : ViewModel() {
    private val loaded = MutableStateFlow(Loaded())
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var jobsLoad: Job? = null

    /** Each job's summary by its id, with the job's last run time it was made for. */
    private val summaries = mutableMapOf<String, Pair<Instant?, CronJobSummary>>()

    val state: StateFlow<SessionListState> = combine(loaded, needsAnswer) { loaded, needsAnswer ->
        val lastOpened = seen.lastOpened()
        SessionListState(
            items = buildTimeline(loaded.sessions, loaded.jobs, clock.instant(), clock.zone, lastOpened, seen.baseline, needsAnswer),
            drawer = buildDrawerEntries(loaded.sessions, loaded.jobs, lastOpened, seen.baseline, needsAnswer),
            results = loaded.searchResults,
            searchOpen = loaded.searchOpen,
            refreshing = loaded.refreshing,
            query = loaded.query,
            error = loaded.error,
            needsLogin = loaded.needsLogin,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionListState())

    /** Reloads the first page, and the search results if there is a query. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { reload() }
    }

    /**
     * Brings the newest sessions up to date without a refresh indicator and without dropping the
     * pages loaded further down, for the drawer, which only needs the newest ones. A failure is
     * silent: the next screen refresh reports it.
     */
    fun refreshQuietly() {
        viewModelScope.launch {
            val page = try {
                source.list(0, PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotLoggedInException) {
                loaded.update { it.copy(needsLogin = true) }
                return@launch
            } catch (e: Exception) {
                return@launch
            }
            loaded.update { it.copy(sessions = (page.sessions + it.sessions).distinctBy(SessionSummary::id), total = page.total) }
        }
        loadJobs()
    }

    /**
     * Brings the cron jobs up to date, replacing a load in progress. A job whose last run is the one it was
     * summarized for keeps its summary, with only its unread state renewed. A job that fails to load keeps what it
     * showed (or is left out until it loads) without holding up the others. Failures are logged; the sessions' own
     * load reports a server that cannot be reached.
     */
    private fun loadJobs() {
        jobsLoad?.cancel()
        jobsLoad = viewModelScope.launch {
            val jobs = try {
                source.jobs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotLoggedInException) {
                loaded.update { it.copy(needsLogin = true) }
                return@launch
            } catch (e: Exception) {
                log.log("cron jobs failed to load: $e")
                return@launch
            }
            val summaries = coroutineScope { jobs.map { job -> async { summaryOrLast(job) } }.awaitAll() }
            loaded.update { it.copy(jobs = summaries.filterNotNull()) }
            archiveOldRunsWhenDue(jobs)
        }
    }

    /** How far the job was seen; a job never opened counts as seen at the baseline, as a session does. */
    private fun seenUntil(jobId: String): Instant = seen.jobSeenUntil(jobId) ?: seen.baseline

    /** The job's summary; when it cannot be made, the last one it had, if any. */
    private suspend fun summaryOrLast(job: CronJob): CronJobSummary? = try {
        summaryOf(job)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is NotLoggedInException) loaded.update { it.copy(needsLogin = true) } else log.log("cron job ${job.id} failed to load: $e")
        summaries[job.id]?.second?.copy(job = job)?.seenUpTo(seenUntil(job.id))
    }

    /**
     * Reads the job's [FEW_RUNS] newest rows, which hold a report unless the job has been silent for a while, and
     * only then, if there are older ones, the most Hermes lists, to find when it last reported something.
     */
    private suspend fun summaryOf(job: CronJob): CronJobSummary {
        val seenUntil = seenUntil(job.id)
        summaries[job.id]?.let { (lastRunAt, summary) -> if (lastRunAt == job.lastRunAt) return summary.copy(job = job).seenUpTo(seenUntil) }
        val newest = source.runs(job.id, FEW_RUNS)
        val summary = summarizeJob(job, newest.runs, seenUntil, source::finalReply).takeIf { it.latestShownRun != null || !newest.hasOlder }
            ?: summarizeJob(job, source.runs(job.id, CronApi.MAX_RUNS).runs, seenUntil, source::finalReply)
        return summary.also { summaries[job.id] = job.lastRunAt to it }
    }

    /**
     * Archives, in the background, the runs older than a week that were read or were silent, so they leave
     * Hermes' session lists too; at most once every [ARCHIVE_INTERVAL]. Failures are only logged.
     */
    private fun archiveOldRunsWhenDue(jobs: List<CronJob>) {
        val now = clock.instant()
        if (seen.lastArchivePass()?.let { Duration.between(it, now) < ARCHIVE_INTERVAL } == true) return
        seen.markArchivePass(now)
        viewModelScope.launch {
            for (job in jobs) {
                val runs = quietly("runs of cron job ${job.id} failed to load") { source.runs(job.id, CronApi.MAX_RUNS).runs } ?: continue
                val old = quietly("old runs of cron job ${job.id} failed to load") {
                    runsToArchive(runs, seenUntil(job.id), now, source::finalReply)
                } ?: continue
                for (run in old) quietly("archiving cron run ${run.sessionId} failed") { source.update(run.sessionId, null, null, true) }
            }
        }
    }

    /** Runs [block]; a failure is logged as [failure] and the result is null. */
    private suspend fun <T> quietly(failure: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log("$failure: $e")
        null
    }

    /**
     * Appends the next page when there is one, and the ones after it while a page adds nothing to the list (only
     * chats never written to, say): the list asks for more only when its end comes into view, which it already is.
     */
    fun loadMore() {
        val current = loaded.value
        if (current.query.isNotBlank() || current.sessions.size >= current.total || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            while (true) {
                val offset = loaded.value.sessions.size
                val page = attempt { source.list(offset, PAGE_SIZE) } ?: return@launch
                loaded.update { it.copy(sessions = (it.sessions + page.sessions).distinctBy(SessionSummary::id), total = page.total) }
                // A page of sessions already loaded (the list shifted meanwhile) would be asked for again and again.
                val grew = loaded.value.sessions.size > offset
                if (page.sessions.any { it.isListed } || !grew || loaded.value.sessions.size >= page.total) return@launch
            }
        }
    }

    fun search(query: String) {
        loaded.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            loaded.update { it.copy(searchResults = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query)
        }
    }

    fun openSearch() = loaded.update { it.copy(searchOpen = true) }

    /** Closing search drops the query, so the timeline is back. */
    fun closeSearch() {
        searchJob?.cancel()
        loaded.update { it.copy(searchOpen = false, query = "", searchResults = null) }
    }

    fun rename(id: String, title: String) = change { source.update(id, title, null, null) }

    fun setPinned(id: String, pinned: Boolean) = change { source.update(id, null, pinned, null) }

    fun archive(id: String) = change { source.update(id, null, null, true) }

    /** Called once the lost login has been acted on, so showing the screen again does not repeat it. */
    fun loginHandled() = loaded.update { it.copy(needsLogin = false) }

    private fun change(update: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            attempt(update)?.let { reload() }
        }
    }

    private suspend fun reload() {
        loaded.update { it.copy(refreshing = true) }
        attempt { source.list(0, PAGE_SIZE) }?.let { page ->
            loaded.update { it.copy(sessions = page.sessions, total = page.total, error = null) }
        }
        loadJobs()
        val query = loaded.value.query
        if (query.isNotBlank()) runSearch(query)
        loaded.update { it.copy(refreshing = false) }
    }

    private suspend fun runSearch(query: String) {
        attempt { source.search(query.trim(), SEARCH_LIMIT) }?.let { results ->
            // The query may have changed or been cleared while the search was running.
            loaded.update {
                if (it.query.trim() == query.trim()) it.copy(searchResults = results.distinctBy { hit -> hit.session.id }) else it
            }
        }
    }

    /** Runs [block]; a failure becomes UI state and the result is null. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotLoggedInException) {
        loaded.update { it.copy(needsLogin = true) }
        null
    } catch (e: IOException) {
        loaded.update { it.copy(error = SessionListError.UNREACHABLE) }
        null
    } catch (e: Exception) {
        loaded.update { it.copy(error = SessionListError.FAILED) }
        null
    }

    private companion object {
        val ARCHIVE_INTERVAL: Duration = Duration.ofHours(24)
        const val FEW_RUNS = 10
        const val PAGE_SIZE = 50
        const val SEARCH_LIMIT = 20
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
