package net.jkraemer.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import net.jkraemer.hermes.chat.ChatScreen
import net.jkraemer.hermes.chat.ChatViewModel
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jkraemer.hermes.sessions.SessionsDrawerContent
import net.jkraemer.hermes.ui.HermesTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.login.LoginScreen
import net.jkraemer.hermes.login.LoginViewModel
import net.jkraemer.hermes.sessions.SessionListScreen
import net.jkraemer.hermes.sessions.SessionListViewModel
import net.jkraemer.hermes.sessions.SessionsApiSource
import okhttp3.HttpUrl.Companion.toHttpUrl

class MainActivity : ComponentActivity() {
    private val graph get() = (application as HermesApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The system bar icons follow the system's light or dark setting, like the theme does.
        enableEdgeToEdge()
        setContent {
            HermesTheme {
                Surface { HermesRoot(graph) }
            }
        }
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
    }
}

private fun JSONObject.toScreen(): Screen = when (getString("screen")) {
    "login" -> Screen.Login
    "sessions" -> Screen.Sessions(optJSONObject("back")?.toScreen() as Screen.Chat?)
    else -> Screen.Chat(
        optString("id").ifEmpty { null },
        optString("title").ifEmpty { null },
        optJSONObject("from")?.toScreen() as Screen.Sessions?,
        getString("key"),
    )
}

/** Keeps the screen across rotation and process death, which a plain `remember` would not. */
internal val ScreenSaver = Saver<Screen?, String>(
    save = { it?.toJson()?.toString() ?: "" },
    restore = { if (it.isEmpty()) null else JSONObject(it).toScreen() },
)

@Composable
private fun HermesRoot(graph: AppGraph) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (screen == null) screen = graph.startScreen()
    }
    // A lost session shows as a failed connection that needs a login.
    LaunchedEffect(screen == null || screen == Screen.Login) {
        if (screen == null || screen == Screen.Login) return@LaunchedEffect
        graph.rpc.state.collect {
            if (it is ConnectionState.Failed && it.needsLogin) screen = Screen.Login
        }
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
        is Screen.Sessions, is Screen.Chat -> SessionsAndChat(graph, current) { screen = it }
    }
}

/** The session list and the chat, which share the navigation drawer and the list's sessions. */
@Composable
private fun SessionsAndChat(graph: AppGraph, current: Screen, show: (Screen) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val sessionList = viewModel {
        SessionListViewModel(SessionsApiSource { graph.sessions }, graph.needsAnswer, graph.seenStore)
    }
    val chat = (current as? Screen.Chat)?.let { open ->
        viewModel(key = open.key) {
            ChatViewModel(graph.rpc, open.storedSessionId, open.title, graph.needsAnswer, graph.seenStore, log = graph.log)
        }
    }
    val sessionState by sessionList.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The drawer lists the newest sessions, so they are loaded before it is first opened and
    // brought up to date as it opens, without disturbing the full list.
    LaunchedEffect(Unit) { sessionList.refreshQuietly() }
    LaunchedEffect(drawerState.targetValue == DrawerValue.Open) { if (drawerState.targetValue == DrawerValue.Open) sessionList.refreshQuietly() }

    // Back goes to the list a chat was opened from, and from the list to the chat it was opened
    // from; with neither it leaves the app.
    BackHandler(current is Screen.Chat && current.fromList != null) { show((current as Screen.Chat).fromList!!) }
    BackHandler(current is Screen.Sessions && current.back != null) { show((current as Screen.Sessions).back!!) }

    fun closeDrawer() = scope.launch { drawerState.close() }

    /** The chat to come back to, opened again with a fresh controller; an unused new chat is just another new chat. */
    fun Screen.Chat.reopened() = Screen.Chat(
        storedSessionId.takeIf { chat?.hasStoredSession == true }, title.takeIf { chat?.hasStoredSession == true },
    )

    fun showList() {
        show(
            when (current) {
                // A chat that came from the list goes back to that list, which already remembers where it began.
                is Screen.Chat -> current.fromList ?: Screen.Sessions(back = current.reopened())
                else -> current
            },
        )
        closeDrawer()
    }

    fun openChat(id: String?, title: String?) {
        sessionList.closeSearch()
        val fromList = when (current) {
            is Screen.Sessions -> current
            is Screen.Chat -> current.fromList
            else -> null
        }
        show(Screen.Chat(id, title, fromList))
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
                    onNewChat = { openChat(null, null) },
                    onOpenChat = ::openChat,
                    onViewAll = ::showList,
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
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onNewChat = { openChat(null, null) },
                    onRetryConnect = graph::reconnect,
                    onStoredSessionId = { id -> if (current.storedSessionId == null) show(current.copy(storedSessionId = id)) },
                )
            }
            else -> Unit
        }
    }
}
