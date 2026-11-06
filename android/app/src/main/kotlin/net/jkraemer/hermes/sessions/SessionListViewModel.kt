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
import net.jkraemer.hermes.core.auth.NotLoggedInException
import net.jkraemer.hermes.core.sessions.SessionPage
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.sessions.SessionsApi
import net.jkraemer.hermes.core.timeline.TimelineItem
import net.jkraemer.hermes.core.timeline.buildTimeline
import net.jkraemer.hermes.settings.SeenStore
import java.io.IOException
import java.time.Clock

/** What the session list needs from the server. */
interface SessionListSource {
    suspend fun list(offset: Int, limit: Int): SessionPage
    suspend fun search(query: String, limit: Int): List<SessionSummary>
    suspend fun update(id: String, title: String?, pinned: Boolean?, archived: Boolean?)
    suspend fun lastAssistantText(id: String): String?
}

/** [api] is read on every call because the server, and with it the API, can change. */
class SessionsApiSource(private val api: () -> SessionsApi) : SessionListSource {
    override suspend fun list(offset: Int, limit: Int) = api().list(offset, limit)
    override suspend fun search(query: String, limit: Int) = api().search(query, limit)
    override suspend fun update(id: String, title: String?, pinned: Boolean?, archived: Boolean?) =
        api().update(id, title, pinned, archived)
    override suspend fun lastAssistantText(id: String) = api().lastAssistantText(id)
}

enum class SessionListError { UNREACHABLE, FAILED }

data class SessionListState(
    val items: List<TimelineItem> = emptyList(),
    val refreshing: Boolean = false,
    val query: String = "",
    val error: SessionListError? = null,
    val needsLogin: Boolean = false,
)

private data class Loaded(
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int = 0,
    /** Null while the query is blank. */
    val searchResults: List<SessionSummary>? = null,
    val refreshing: Boolean = false,
    val query: String = "",
    val error: SessionListError? = null,
    val needsLogin: Boolean = false,
    /** First line of each cron run's reply by stored session id; a run does not change once finished. */
    val cronReplies: Map<String, String> = emptyMap(),
)

/**
 * [needsAnswer] holds the stored ids of sessions waiting for the user. [clock] decides which
 * day counts as today.
 */
class SessionListViewModel(
    private val source: SessionListSource,
    needsAnswer: StateFlow<Set<String>>,
    private val seen: SeenStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val loaded = MutableStateFlow(Loaded())
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private val replyRequested = mutableSetOf<String>()

    val state: StateFlow<SessionListState> = combine(loaded, needsAnswer) { loaded, needsAnswer ->
        val items = (loaded.searchResults?.map { TimelineItem.Row(it, unread = false, needsAnswer = it.id in needsAnswer) }
            ?: buildTimeline(
                loaded.sessions, clock.instant(), clock.zone, seen.lastOpened(), seen.baseline, needsAnswer,
            )).map { item ->
            // The preview of a cron run is the job prompt; its reply says more.
            if (item !is TimelineItem.Row) item
            else loaded.cronReplies[item.session.id]?.let { item.copy(session = item.session.copy(preview = it)) } ?: item
        }
        SessionListState(items, loaded.refreshing, loaded.query, loaded.error, loaded.needsLogin)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionListState())

    /** Reloads the first page, and the search results if there is a query. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { reload() }
    }

    /** Appends the next page when there is one. */
    fun loadMore() {
        val current = loaded.value
        if (current.query.isNotBlank() || current.sessions.size >= current.total || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            attempt { source.list(current.sessions.size, PAGE_SIZE) }?.let { page ->
                loaded.update { it.copy(sessions = (it.sessions + page.sessions).distinctBy(SessionSummary::id), total = page.total) }
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

    fun rename(id: String, title: String) = change { source.update(id, title, null, null) }

    fun setPinned(id: String, pinned: Boolean) = change { source.update(id, null, pinned, null) }

    fun archive(id: String) = change { source.update(id, null, null, true) }

    /**
     * Called when a cron row is on screen: fetches the run's reply once, for the row to show instead
     * of the job prompt. A failed fetch is silent, the row keeps its preview, and is tried again
     * the next time the row appears.
     */
    fun cronRowShown(id: String) {
        if (!replyRequested.add(id)) return
        viewModelScope.launch {
            val reply = try {
                source.lastAssistantText(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                replyRequested.remove(id)
                return@launch
            }
            reply?.lineSequence()?.map(String::trim)?.firstOrNull(String::isNotEmpty)?.let { line ->
                loaded.update { it.copy(cronReplies = it.cronReplies + (id to line)) }
            }
        }
    }

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
        val query = loaded.value.query
        if (query.isNotBlank()) runSearch(query)
        loaded.update { it.copy(refreshing = false) }
    }

    private suspend fun runSearch(query: String) {
        attempt { source.search(query.trim(), SEARCH_LIMIT) }?.let { results ->
            // The query may have changed or been cleared while the search was running.
            loaded.update {
                if (it.query.trim() == query.trim()) it.copy(searchResults = results.distinctBy(SessionSummary::id)) else it
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
        const val PAGE_SIZE = 50
        const val SEARCH_LIMIT = 20
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
