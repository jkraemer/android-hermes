package net.jkraemer.hermes

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.core.auth.FileCookieStore
import net.jkraemer.hermes.core.auth.HermesAuth
import net.jkraemer.hermes.core.auth.NotLoggedInException
import net.jkraemer.hermes.core.auth.PersistentCookieJar
import net.jkraemer.hermes.core.rpc.RpcConnection
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.sessions.SessionsApi
import net.jkraemer.hermes.settings.SeenStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface Screen {
    data object Login : Screen

    /** The full session list; back returns to [back], the chat it was opened from, and leaves the app when there is none. */
    data class Sessions(val back: Chat? = null) : Screen

    /**
     * [storedSessionId] null starts a new chat; [title] is the one the session list showed. [fromList]
     * is the list back returns to, if the chat was opened from one. [key] tells chats apart, so
     * opening another chat from a chat gets a fresh controller; it is kept when a new chat learns its id.
     */
    data class Chat(
        val storedSessionId: String?,
        val title: String? = null,
        val fromList: Sessions? = null,
        val key: String = UUID.randomUUID().toString(),
    ) : Screen
}

/** A chat that was active within this long before launch is reopened instead of starting a new one. */
private val RESUME_WINDOW = Duration.ofMinutes(30)

/**
 * The chat the app opens at launch: the most recently active one if it was active within
 * [RESUME_WINDOW] of [now], else a new chat. Cron runs never count as a chat.
 */
fun launchScreen(sessions: List<SessionSummary>, now: Instant): Screen {
    val last = sessions.filter { !it.isCron }.maxByOrNull { it.lastActive }
    return if (last != null && Duration.between(last.lastActive, now) < RESUME_WINDOW) {
        Screen.Chat(last.id, last.title)
    } else {
        Screen.Chat(null)
    }
}

/**
 * What must exist once per process: the server URL, the cookie jar holding the login, and the
 * server clients built for that URL.
 */
class AppGraph(context: Context) {
    private class Services(val auth: HermesAuth, val sessions: SessionsApi, val rpc: RpcConnection)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cookieJar = PersistentCookieJar(FileCookieStore(File(context.filesDir, "cookies.json")))
    private val client = OkHttpClient.Builder().cookieJar(cookieJar).build()

    /** Logcat line for the connection and chat events the core reports. */
    val log = HermesLog { Log.i(LOG_TAG, it) }

    private var services: Services? = null

    val seenStore = SeenStore(prefs)

    /** Stored ids of the sessions waiting for the user's answer; the chat screen keeps it current. */
    val needsAnswer = MutableStateFlow(emptySet<String>())

    /**
     * The Hermes server. Assigning it, even to the same value, persists it and rebuilds [auth],
     * [sessions] and [rpc], so their state starts fresh.
     */
    var serverUrl: HttpUrl? = prefs.getString(SERVER_URL_KEY, null)?.toHttpUrlOrNull()
        set(value) {
            field = value
            services?.rpc?.close()
            services = value?.let(::buildServices)
            prefs.edit().apply { if (value == null) remove(SERVER_URL_KEY) else putString(SERVER_URL_KEY, value.toString()) }.apply()
        }

    init {
        // The setter is not run for the initial value.
        services = serverUrl?.let(::buildServices)
    }

    val auth: HermesAuth get() = requireServices().auth
    val sessions: SessionsApi get() = requireServices().sessions
    val rpc: RpcConnection get() = requireServices().rpc

    private fun buildServices(url: HttpUrl): Services {
        val auth = HermesAuth(url, client)
        return Services(auth, SessionsApi(url, client), RpcConnection(client, url, auth::mintWsTicket, scope, log = log))
    }

    private fun requireServices() = checkNotNull(services) { "No server URL is set" }

    /**
     * Login unless there is a server and a session, else [openingScreen]. When the server cannot
     * be reached the stored cookies are all there is to go on: with some, open a new chat (which
     * reports its own connection error), without any the user cannot be logged in.
     */
    suspend fun startScreen(): Screen {
        val url = serverUrl ?: return Screen.Login
        return try {
            if (auth.isLoggedIn()) openingScreen() else Screen.Login
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            Screen.Login
        } catch (e: Exception) {
            if (cookieJar.loadForRequest(url).isEmpty()) Screen.Login else Screen.Chat(null)
        }
    }

    /**
     * The chat to open once logged in: see [launchScreen]. When the sessions cannot be read there is
     * no recent chat to know of, so it is a new one.
     */
    suspend fun openingScreen(): Screen = try {
        launchScreen(sessions.list().sessions, Instant.now())
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotLoggedInException) {
        Screen.Login
    } catch (e: Exception) {
        Screen.Chat(null)
    }

    /**
     * Connects [rpc] if it is not connected. A failure shows in `rpc.state`, which is where
     * the UI reads it from; a failed connect is retried only by calling this again.
     */
    fun reconnect() {
        val rpc = services?.rpc ?: return
        scope.launch {
            try {
                rpc.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Reported through rpc.state.
            }
        }
    }

    fun close() {
        services?.rpc?.close()
        scope.cancel()
    }

    private companion object {
        const val LOG_TAG = "Hermes"
        const val PREFS_NAME = "hermes"
        const val SERVER_URL_KEY = "server_url"
    }
}
