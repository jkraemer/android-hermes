package net.jkraemer.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import net.jkraemer.hermes.chat.ChatScreen
import net.jkraemer.hermes.chat.ChatViewModel
import net.jkraemer.hermes.cron.CronApiSource
import net.jkraemer.hermes.cron.JobFeedScreen
import net.jkraemer.hermes.cron.JobFeedViewModel
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jkraemer.hermes.sessions.SessionsDrawerContent
import net.jkraemer.hermes.push.Notifier
import net.jkraemer.hermes.push.PushStatus
import net.jkraemer.hermes.settings.FavoriteModelsScreen
import net.jkraemer.hermes.settings.FavoriteModelsViewModel
import net.jkraemer.hermes.settings.SettingsScreen
import net.jkraemer.hermes.ui.HermesTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jkraemer.hermes.core.push.NotificationRule
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.login.LoginScreen
import net.jkraemer.hermes.login.LoginViewModel
import net.jkraemer.hermes.sessions.SessionListScreen
import net.jkraemer.hermes.sessions.SessionListViewModel
import net.jkraemer.hermes.sessions.SessionsApiSource
import okhttp3.HttpUrl.Companion.toHttpUrl

class MainActivity : ComponentActivity() {
    private val graph get() = (application as HermesApp).graph

    /** The session a tapped notification asked for, until the app has opened it. */
    private val requestedSession = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system bar icons follow the system's light or dark setting, like the theme does.
        enableEdgeToEdge()
        // A recreated activity restores its screen; the launching intent was already acted on.
        if (savedInstanceState == null) requestedSession.value = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)
        setContent {
            HermesTheme {
                Surface { HermesRoot(graph, requestedSession.value) { requestedSession.value = null } }
            }
        }
    }

    // A tapped notification reaches the running activity here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_SESSION_ID)?.let { requestedSession.value = it }
    }

    // Coming to the foreground is when a failed connection gets its next try.
    override fun onStart() {
        super.onStart()
        graph.reconnect()
    }
}

private fun Screen.toJson(): JSONObject = when (this) {
    Screen.Login -> JSONObject().put("screen", "login")
    is Screen.Sessions -> JSONObject().put("screen", "sessions").apply { back?.let { put("back", it.toJson()) } }
    is Screen.Chat -> JSONObject().put("screen", "chat").put("key", key).apply {
        storedSessionId?.let { put("id", it) }
        title?.let { put("title", it) }
        fromList?.let { put("from", it.toJson()) }
        if (cron) put("cron", true)
        fromJob?.let { put("job", it.toJson()) }
    }
    is Screen.Job -> JSONObject().put("screen", "job").put("key", key).put("id", jobId).put("name", name).apply {
        back?.let { put("back", it.toJson()) }
    }
    is Screen.Settings -> JSONObject().put("screen", "settings").apply { back?.let { put("back", it.toJson()) } }
    is Screen.FavoriteModels -> JSONObject().put("screen", "favorite-models").apply { back?.let { put("back", it.toJson()) } }
}

private fun JSONObject.toScreen(): Screen = when (getString("screen")) {
    "login" -> Screen.Login
    "sessions" -> Screen.Sessions(optJSONObject("back")?.toScreen()?.also { require(it is Screen.Chat || it is Screen.Job) })
    "job" -> Screen.Job(
        getString("id"),
        getString("name"),
        optJSONObject("back")?.toScreen()?.also { require(it is Screen.Sessions || it is Screen.Chat) },
        getString("key"),
    )
    "settings" -> Screen.Settings(optJSONObject("back")?.toScreen()?.also { require(it is Screen.Sessions || it is Screen.Chat || it is Screen.Job) })
    "favorite-models" -> Screen.FavoriteModels(optJSONObject("back")?.toScreen())
    else -> Screen.Chat(
        optString("id").ifEmpty { null },
        optString("title").ifEmpty { null },
        optJSONObject("from")?.toScreen() as Screen.Sessions?,
        getString("key"),
        optBoolean("cron"),
        optJSONObject("job")?.toScreen() as Screen.Job?,
    )
}

/** Keeps the screen across rotation and process death, which a plain `remember` would not. */
internal val ScreenSaver = Saver<Screen?, String>(
    save = { it?.toJson()?.toString() ?: "" },
    // State saved by another version, or damaged, is not worth a crash: the app starts afresh.
    restore = { runCatching { if (it.isEmpty()) null else JSONObject(it).toScreen() }.getOrNull() },
)

@Composable
private fun HermesRoot(graph: AppGraph, requestedSession: String?, onSessionOpened: () -> Unit) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (screen == null) screen = graph.startScreen()
    }
    // A session asked for by a notification opens once there is somewhere to open it: after the start screen is known and not the login.
    LaunchedEffect(requestedSession, screen == null || screen == Screen.Login) {
        if (requestedSession != null && screen != null && screen != Screen.Login) {
            screen = Screen.Chat(requestedSession)
            onSessionOpened()
        }
    }
    // A lost session shows as a failed connection that needs a login.
    LaunchedEffect(screen == null || screen == Screen.Login) {
        if (screen == null || screen == Screen.Login) return@LaunchedEffect
        graph.rpc.state.collect {
            if (it is ConnectionState.Failed && it.needsLogin) screen = Screen.Login
        }
    }

    // Registering again on every start keeps the server's subscription current; it needs the activity to offer the choice of distributor.
    val activity = LocalContext.current as ComponentActivity
    LaunchedEffect(screen == null || screen == Screen.Login) {
        if (screen != null && screen != Screen.Login) graph.pushRegistration.start(activity)
    }

    when (val current = screen) {
        null -> Unit
        Screen.Login -> {
            val login = viewModel {
                LoginViewModel(graph.serverUrl?.toString().orEmpty()) { url, username, password ->
                    // Assigning the URL gives the login, and the connection after it, fresh state.
                    graph.serverUrl = url.toHttpUrl()
                    graph.auth.login(username, password)
                }
            }
            LoginScreen(login, onLoggedIn = {
                scope.launch {
                    screen = graph.openingScreen()
                    graph.reconnect()
                }
            })
        }
        is Screen.Sessions, is Screen.Chat, is Screen.Job -> SessionsAndChat(graph, current) { screen = it }
        is Screen.Settings -> {
            val favorites by graph.favoritesStore.favorites.collectAsStateWithLifecycle()
            BackHandler(current.back != null) { screen = current.back }
            val push by graph.pushRegistration.status.collectAsStateWithLifecycle()
            // The permission can be changed in the system settings while the app is away.
            LifecycleResumeEffect(Unit) {
                graph.pushRegistration.permissionChanged()
                onPauseOrDispose {}
            }
            SettingsScreen(
                favorites.size,
                push,
                onBack = { screen = current.back },
                onOpenFavoriteModels = { screen = Screen.FavoriteModels(back = current) },
                onRetryPush = {
                    if (push == PushStatus.NoDistributor) graph.pushRegistration.pickDistributor(activity) else graph.pushRegistration.start(activity)
                },
                onNotificationPermissionResult = graph.pushRegistration::permissionChanged,
                onSendTest = { graph.pushApi.sendTest() },
            )
        }
        is Screen.FavoriteModels -> {
            val favoriteModels = viewModel { FavoriteModelsViewModel({ graph.rpc }, graph.favoritesStore) }
            BackHandler(current.back != null) { screen = current.back }
            FavoriteModelsScreen(favoriteModels, onBack = { screen = current.back })
        }
    }
}

/** The session list, the chat and a cron job's feed, which share the navigation drawer and the list's sessions. */
@Composable
private fun SessionsAndChat(graph: AppGraph, current: Screen, show: (Screen) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val cronSource = remember(graph) { CronApiSource({ graph.cron }, { graph.finalReplies }) }
    val sessionList = viewModel {
        SessionListViewModel(SessionsApiSource({ graph.sessions }, cronSource), graph.needsAnswer, graph.seenStore, log = graph.log)
    }
    val chat = (current as? Screen.Chat)?.let { open ->
        viewModel(key = open.key) {
            ChatViewModel(
                graph.rpc, open.storedSessionId, open.title, graph.chatsNeedingAnswer, graph.seenStore, graph.pushedNeedsAnswer, graph.visibleSessionId,
                log = graph.log, pendingMessage = open.pendingMessage, favorites = graph.favoritesStore.favorites,
            )
        }
    }
    val feed = (current as? Screen.Job)?.let { open -> viewModel(key = open.key) { JobFeedViewModel(cronSource, open.jobId, graph.seenStore) } }
    val rules by graph.notificationRules.rules.collectAsStateWithLifecycle()
    val sessionState by sessionList.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The drawer lists the newest sessions, so they are loaded before it is first opened and
    // brought up to date as it opens, without disturbing the full list.
    LaunchedEffect(Unit) { sessionList.refreshQuietly() }
    LaunchedEffect(drawerState.targetValue == DrawerValue.Open) { if (drawerState.targetValue == DrawerValue.Open) sessionList.refreshQuietly() }

    // Back goes from a cron run to the job's feed it was opened from, from a chat to the list it was opened from,
    // from a feed to the list or chat it was opened from, and from the list to the chat or feed it was opened from;
    // with none it leaves the app.
    val backTo = when (current) {
        is Screen.Chat -> current.fromJob ?: current.fromList
        is Screen.Job -> current.back
        is Screen.Sessions -> current.back
        else -> null
    }
    BackHandler(backTo != null) { show(backTo!!) }
    BackHandler(current is Screen.Sessions && sessionState.searchOpen, sessionList::closeSearch)
    // The open drawer is on top of everything, so back closes it first; the last handler registered wins.
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

    fun closeDrawer() = scope.launch { drawerState.close() }

    /** The chat to come back to, opened again with a fresh controller; an unused new chat is just another new chat. */
    fun Screen.Chat.reopened() = Screen.Chat(
        storedSessionId.takeIf { chat?.hasStoredSession == true }, title.takeIf { chat?.hasStoredSession == true },
        fromList, cron = cron, fromJob = fromJob,
    )

    /** The list [screen] is, or that it came from, if any. */
    fun listBehind(screen: Screen?): Screen.Sessions? = when (screen) {
        is Screen.Sessions -> screen
        is Screen.Chat -> screen.fromList
        is Screen.Job -> listBehind(screen.back)
        else -> null
    }

    fun showList() {
        show(
            when (current) {
                // A chat or feed that came from the list goes back to that list, which already remembers where it began.
                is Screen.Chat -> current.fromList ?: Screen.Sessions(back = current.reopened())
                is Screen.Job -> listBehind(current) ?: Screen.Sessions(back = current)
                else -> current
            },
        )
        closeDrawer()
    }

    fun openChat(id: String?, title: String?, cron: Boolean) {
        sessionList.closeSearch()
        show(Screen.Chat(id, title, listBehind(current), cron = cron))
        closeDrawer()
    }

    /** A feed goes back to where it was opened; one opened from another feed, to where that one was opened. */
    fun openJob(jobId: String, name: String) {
        sessionList.closeSearch()
        val back = when (current) {
            is Screen.Chat -> current.reopened()
            is Screen.Job -> current.back
            else -> current
        }
        show(Screen.Job(jobId, name, back))
        closeDrawer()
    }

    /** Opens a run of the job on screen, whose back returns to the job; [message] is sent in it once it is open. */
    fun openRun(job: Screen.Job, sessionId: String, message: String? = null) =
        show(Screen.Chat(sessionId, job.name, listBehind(job), cron = true, fromJob = job, pendingMessage = message))

    /** Settings go back to where they were opened, a chat being opened again like when going back from the list. */
    fun openSettings() {
        sessionList.closeSearch()
        show(Screen.Settings(back = if (current is Screen.Chat) current.reopened() else current))
        closeDrawer()
    }

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                SessionsDrawerContent(
                    sessionState.drawer,
                    onSearch = {
                        sessionList.openSearch()
                        showList()
                    },
                    onNewChat = { openChat(null, null, false) },
                    onOpenChat = ::openChat,
                    onOpenJob = ::openJob,
                    onViewAll = ::showList,
                    onOpenSettings = ::openSettings,
                )
            }
        },
        drawerState = drawerState,
    ) {
        when (current) {
            is Screen.Sessions -> SessionListScreen(
                sessionList,
                host = graph.serverUrl?.host.orEmpty(),
                onOpenMenu = { scope.launch { drawerState.open() } },
                onOpenChat = ::openChat,
                onOpenJob = ::openJob,
                onNotLoggedIn = { show(Screen.Login) },
                onRetryConnect = graph::reconnect,
            )
            is Screen.Chat -> {
                val model = checkNotNull(chat)
                DisposableEffect(model) {
                    onDispose { if (!activity.isChangingConfigurations) model.close() }
                }
                ChatScreen(
                    model,
                    cron = current.cron,
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onNewChat = { openChat(null, null, false) },
                    onRetryConnect = graph::reconnect,
                    onStoredSessionId = { id -> if (current.storedSessionId == null) show(current.copy(storedSessionId = id)) },
                    onOpenFavorites = { show(Screen.FavoriteModels(back = current.reopened())) },
                )
            }
            is Screen.Job -> JobFeedScreen(
                checkNotNull(feed),
                name = current.name,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onOpenRun = { openRun(current, it) },
                onReply = { run, text -> openRun(current, run, text) },
                rule = rules[current.jobId] ?: NotificationRule.Always,
                onSaveRule = { graph.notificationRules.set(current.jobId, it) },
            )
            else -> Unit
        }
    }
}
