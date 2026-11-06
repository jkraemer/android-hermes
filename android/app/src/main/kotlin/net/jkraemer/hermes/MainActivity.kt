package net.jkraemer.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
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

private const val FIELD_SEPARATOR = "\u001f"

/** Keeps the screen across rotation and process death, which a plain `remember` would not. */
internal val ScreenSaver = Saver<Screen?, String>(
    save = {
        when (it) {
            null -> ""
            Screen.Login -> "login"
            Screen.Sessions -> "sessions"
            is Screen.Chat -> listOf("chat", it.key, it.fromList, it.storedSessionId.orEmpty(), it.title.orEmpty())
                .joinToString(FIELD_SEPARATOR)
        }
    },
    restore = {
        when (it) {
            "" -> null
            "login" -> Screen.Login
            "sessions" -> Screen.Sessions
            else -> {
                val (_, key, fromList, id, title) = it.split(FIELD_SEPARATOR, limit = 5)
                Screen.Chat(id.ifEmpty { null }, title.ifEmpty { null }, fromList.toBoolean(), key)
            }
        }
    },
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
        Screen.Sessions, is Screen.Chat -> SessionsAndChat(graph, current) { screen = it }
    }
}

/** The session list and the chat, which share the navigation drawer and the list's sessions. */
@Composable
private fun SessionsAndChat(graph: AppGraph, current: Screen, show: (Screen) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val sessionList = viewModel {
        SessionListViewModel(SessionsApiSource { graph.sessions }, graph.needsAnswer, graph.seenStore)
    }
    val sessionState by sessionList.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The drawer shows what changed since it was last open.
    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) sessionList.refresh() }

    // A chat opened from the list goes back to it; from anywhere else back leaves the app.
    BackHandler(current is Screen.Chat && current.fromList) { show(Screen.Sessions) }

    fun closeDrawer() = scope.launch { drawerState.close() }
    fun openChat(id: String?, title: String?) {
        sessionList.closeSearch()
        show(Screen.Chat(id, title, fromList = current is Screen.Sessions))
        closeDrawer()
    }

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                SessionsDrawerContent(
                    sessionState.drawer,
                    onSearch = {
                        sessionList.openSearch()
                        show(Screen.Sessions)
                        closeDrawer()
                    },
                    onNewChat = { openChat(null, null) },
                    onOpenChat = ::openChat,
                    onViewAll = {
                        show(Screen.Sessions)
                        closeDrawer()
                    },
                )
            }
        },
        drawerState = drawerState,
    ) {
        when (current) {
            Screen.Sessions -> SessionListScreen(
                sessionList,
                host = graph.serverUrl?.host.orEmpty(),
                onOpenMenu = { scope.launch { drawerState.open() } },
                onOpenChat = ::openChat,
                onNotLoggedIn = { show(Screen.Login) },
                onRetryConnect = graph::reconnect,
            )
            is Screen.Chat -> {
                val chat = viewModel(key = current.key) {
                    ChatViewModel(graph.rpc, current.storedSessionId, current.title, graph.needsAnswer, graph.seenStore, log = graph.log)
                }
                DisposableEffect(chat) {
                    onDispose { if (!activity.isChangingConfigurations) chat.close() }
                }
                ChatScreen(
                    chat,
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
