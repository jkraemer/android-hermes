package net.jkraemer.hermes

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.core.auth.FileCookieStore
import net.jkraemer.hermes.core.auth.HermesAuth
import net.jkraemer.hermes.core.auth.NotLoggedInException
import net.jkraemer.hermes.core.auth.PersistentCookieJar
import net.jkraemer.hermes.core.cron.CronApi
import net.jkraemer.hermes.core.cron.FinalReplies
import net.jkraemer.hermes.core.push.PushApi
import net.jkraemer.hermes.core.rpc.RpcConnection
import net.jkraemer.hermes.core.sessions.SessionSummary
import net.jkraemer.hermes.core.sessions.SessionsApi
import net.jkraemer.hermes.push.Notifier
import net.jkraemer.hermes.push.PushHandler
import net.jkraemer.hermes.push.PushRegistration
import net.jkraemer.hermes.push.UnifiedPushDistributor
import net.jkraemer.hermes.settings.FavoritesStore
import net.jkraemer.hermes.settings.NeedsAnswerStore
import net.jkraemer.hermes.settings.NotificationRulesStore
import net.jkraemer.hermes.settings.SeenStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

sealed interface Screen {
    data object Login : Screen

    /**
     * The full session list; back returns to [back], the chat or cron job it was opened from, and leaves the app
     * when there is none.
     */
    data class Sessions(val back: Screen? = null) : Screen

    /**
     * [storedSessionId] null starts a new chat; [title] is the one the session list showed. [fromList]
     * is the list back returns to, if the chat was opened from one. [key] tells chats apart, so
     * opening another chat from a chat gets a fresh controller; it is kept when a new chat learns its id.
     * [cron] is whether the session is a cron run, whose first message is the job's prompt. [fromJob] is the cron
     * job's feed the run was opened from, which back returns to first. [pendingMessage] is sent once the chat is
     * open; it is not saved with the screen, since by then it has been sent.
     */
    data class Chat(
        val storedSessionId: String?,
        val title: String? = null,
        val fromList: Sessions? = null,
        val key: String = UUID.randomUUID().toString(),
        val cron: Boolean = false,
        val fromJob: Job? = null,
        val pendingMessage: String? = null,
    ) : Screen

    /**
     * The feed of the cron job [jobId] called [name]. [back] is the list or the chat it was opened from, which back
     * returns to; [key] tells feeds apart.
     */
    data class Job(
        val jobId: String,
        val name: String,
        val back: Screen? = null,
        val key: String = UUID.randomUUID().toString(),
    ) : Screen

    /** The settings; back returns to [back], the list, chat or feed it was opened from. */
    data class Settings(val back: Screen? = null) : Screen

    /** The favorite models; back returns to [back], the settings or the chat options it was opened from. */
    data class FavoriteModels(val back: Screen? = null) : Screen
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
class AppGraph(private val context: Context) {
    private class Services(
        val auth: HermesAuth,
        val sessions: SessionsApi,
        val rpc: RpcConnection,
        val cron: CronApi,
        val finalReplies: FinalReplies,
        val push: PushApi,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cookieJar = PersistentCookieJar(FileCookieStore(File(context.filesDir, "cookies.json")))
    private val client = OkHttpClient.Builder().cookieJar(cookieJar).build()

    /** Logcat line for the connection and chat events the core reports. */
    val log = HermesLog { Log.i(LOG_TAG, it) }

    private var services: Services? = null

    val seenStore = SeenStore(prefs)

    val favoritesStore = FavoritesStore(prefs)

    /** When each cron job's reports are announced by a push. */
    val notificationRules = NotificationRulesStore(prefs)

    /** The sessions announced by a push to wait for the user's answer. */
    val pushedNeedsAnswer = NeedsAnswerStore(prefs)

    /** Stored ids of the sessions whose chats showed an open card; the chat screens keep it current. */
    val chatsNeedingAnswer = MutableStateFlow(emptySet<String>())

    /** Stored ids of the sessions waiting for the user's answer: those the chats showed and those a push announced. */
    val needsAnswer: StateFlow<Set<String>> =
        combine(chatsNeedingAnswer, pushedNeedsAnswer.sessions) { chats, pushed -> chats + pushed }
            .stateIn(scope, SharingStarted.Eagerly, chatsNeedingAnswer.value + pushedNeedsAnswer.sessions.value)

    /** The stored id of the chat on screen, if any. */
    val visibleSessionId = MutableStateFlow<String?>(null)

    init {
        // A request nobody resolved expires by itself; nothing else would notice while the app only sits there.
        scope.launch {
            while (true) {
                delay(EXPIRY_CHECK_INTERVAL)
                pushedNeedsAnswer.dropExpired()
            }
        }
    }

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
    val cron: CronApi get() = requireServices().cron

    /** The cron runs' final replies, kept while the server stays the same. */
    val finalReplies: FinalReplies get() = requireServices().finalReplies

    val pushApi: PushApi get() = requireServices().push

    /** The UnifiedPush registration; it sends the endpoint to the plugin of the current server. */
    val pushRegistration = PushRegistration(
        { pushApi }, UnifiedPushDistributor, { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }, scope,
    )

    /** Whether the app has asked for the notification permission on its own initiative, which it does once per install. */
    var askedForNotificationPermission: Boolean
        get() = prefs.getBoolean(ASKED_NOTIFICATION_PERMISSION_KEY, false)
        set(value) = prefs.edit().putBoolean(ASKED_NOTIFICATION_PERMISSION_KEY, value).apply()

    val pushHandler = PushHandler(
        pushRegistration,
        pushedNeedsAnswer,
        chatsNeedingAnswer,
        Notifier(context),
        notificationRules::ruleFor,
        { visibleSessionId.value },
        { sessions.finalReply(it) },
        scope,
        log,
    )

    private fun buildServices(url: HttpUrl): Services {
        val auth = HermesAuth(url, client)
        val sessions = SessionsApi(url, client)
        return Services(
            auth, sessions, RpcConnection(client, url, auth::mintWsTicket, scope, log = log), CronApi(url, client), FinalReplies(sessions::finalReply),
            PushApi(url, client),
        )
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
        val EXPIRY_CHECK_INTERVAL = 30.seconds
        const val LOG_TAG = "Hermes"
        const val PREFS_NAME = "hermes"
        const val SERVER_URL_KEY = "server_url"
        const val ASKED_NOTIFICATION_PERMISSION_KEY = "asked_notification_permission"
    }
}
