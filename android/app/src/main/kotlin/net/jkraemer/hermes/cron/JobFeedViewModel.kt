package net.jkraemer.hermes.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.jkraemer.hermes.chat.ParsedMarkdown
import net.jkraemer.hermes.core.cron.CronApi
import net.jkraemer.hermes.core.cron.CronRun
import net.jkraemer.hermes.core.cron.ShownRun
import net.jkraemer.hermes.core.cron.feedEntries
import net.jkraemer.hermes.settings.SeenStore

/**
 * [entries] are the runs that reported something, oldest first. [loading] while the runs and their replies are
 * being read; [failed] when the runs could not be.
 */
data class JobFeedState(val entries: List<ShownRun> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

/**
 * The feed of one cron job: what each of its runs reported. It loads when created; entries appear as their replies
 * arrive, a few read at a time, newest first; the job is marked seen up to the newest one shown.
 */
class JobFeedViewModel(
    private val source: CronSource,
    private val jobId: String,
    private val seen: SeenStore,
) : ViewModel() {
    private val _state = MutableStateFlow(JobFeedState())
    val state: StateFlow<JobFeedState> = _state.asStateFlow()

    private val markdown = ParsedMarkdown(viewModelScope)

    /** Each entry's parsed reply, by its text. */
    val parsedReplies: StateFlow<Map<String, State.Success>> = markdown.parsed

    /** The replies read so far, by session id, kept across refreshes; a run without one is asked again. */
    private val replies = mutableMapOf<String, String>()
    private var load: Job? = null

    init {
        refresh()
    }

    /** Reads the job's runs again, and the replies of those not read before. */
    fun refresh() {
        load?.cancel()
        load = viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val runs = try {
                source.runs(jobId, CronApi.MAX_RUNS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failed = true) }
                return@launch
            }
            show(runs)
            val reading = Semaphore(PARALLEL_REPLIES)
            coroutineScope {
                for (run in runs) {
                    if (run.archived || run.running || run.sessionId in replies) continue
                    launch {
                        val reply = reading.withPermit { replyOf(run) } ?: return@launch
                        replies[run.sessionId] = reply
                        show(runs)
                    }
                }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    /** The run's reply; null when it has none or it could not be read, which leaves the run out. */
    private suspend fun replyOf(run: CronRun): String? = try {
        source.finalReply(run)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun show(runs: List<CronRun>) {
        val entries = feedEntries(runs, replies)
        entries.forEach { markdown.parse(it.reply) }
        _state.update { it.copy(entries = entries) }
        entries.lastOrNull()?.let { seen.markJobSeen(jobId, it.time) }
    }

    private companion object {
        /** How many replies are read at once. */
        const val PARALLEL_REPLIES = 4
    }
}
