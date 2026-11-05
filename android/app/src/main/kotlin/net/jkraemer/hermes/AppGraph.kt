package net.jkraemer.hermes

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.jkraemer.hermes.core.auth.FileCookieStore
import net.jkraemer.hermes.core.auth.HermesAuth
import net.jkraemer.hermes.core.auth.NotLoggedInException
import net.jkraemer.hermes.core.auth.PersistentCookieJar
import net.jkraemer.hermes.core.rpc.RpcConnection
import net.jkraemer.hermes.core.sessions.SessionsApi
import net.jkraemer.hermes.settings.SeenStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File

sealed interface Screen {
    data object Login : Screen
    data object Sessions : Screen

    /** [storedSessionId] null starts a new chat; [title] is the one the session list showed. */
    data class Chat(val storedSessionId: String?, val title: String? = null) : Screen
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
        return Services(auth, SessionsApi(url, client), RpcConnection(client, url, auth::mintWsTicket, scope))
    }

    private fun requireServices() = checkNotNull(services) { "No server URL is set" }

    /**
     * Login unless there is a server and a session. When the server cannot be reached the
     * stored cookies are all there is to go on: with some, show the sessions (which report
     * their own error), without any the user cannot be logged in.
     */
    suspend fun startScreen(): Screen {
        val url = serverUrl ?: return Screen.Login
        return try {
            if (auth.isLoggedIn()) Screen.Sessions else Screen.Login
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotLoggedInException) {
            Screen.Login
        } catch (e: Exception) {
            if (cookieJar.loadForRequest(url).isEmpty()) Screen.Login else Screen.Sessions
        }
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
        const val PREFS_NAME = "hermes"
        const val SERVER_URL_KEY = "server_url"
    }
}
