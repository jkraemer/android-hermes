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
import net.jkraemer.hermes.core.sessions.SearchHit
import net.jkraemer.hermes.core.sessions.SessionPage
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.sessions.SessionsApi
import net.jkraemer.hermes.core.timeline.DrawerEntries
import net.jkraemer.hermes.core.timeline.TimelineItem
import net.jkraemer.hermes.core.timeline.buildDrawerEntries
import net.jkraemer.hermes.core.timeline.buildTimeline
import net.jkraemer.hermes.settings.SeenStore
import java.io.IOException
import java.time.Clock

/** What the session list needs from the server. */
interface SessionListSource {
    suspend fun list(offset: Int, limit: Int): SessionPage
    suspend fun search(query: String, limit: Int): List<SearchHit>
    suspend fun update(id: String, title: String?, pinned: Boolean?, archived: Boolean?)
}

/** [api] is read on every call because the server, and with it the API, can change. */
class SessionsApiSource(private val api: () -> SessionsApi) : SessionListSource {
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

    val state: StateFlow<SessionListState> = combine(loaded, needsAnswer) { loaded, needsAnswer ->
        val lastOpened = seen.lastOpened()
        SessionListState(
            items = buildTimeline(loaded.sessions, clock.instant(), clock.zone, lastOpened, seen.baseline, needsAnswer),
            drawer = buildDrawerEntries(loaded.sessions, lastOpened, seen.baseline, needsAnswer),
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
        const val PAGE_SIZE = 50
        const val SEARCH_LIMIT = 20
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
