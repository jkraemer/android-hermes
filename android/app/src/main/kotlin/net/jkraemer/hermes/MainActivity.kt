package net.jkraemer.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        setContent {
            MaterialTheme {
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

private const val CHAT_KEY = "chat"

/** Keeps the screen across rotation and process death, which a plain `remember` would not. */
private val ScreenSaver = Saver<Screen?, String>(
    save = {
        when (it) {
            null -> ""
            Screen.Login -> "login"
            Screen.Sessions -> "sessions"
            is Screen.Chat -> it.storedSessionId?.let { id -> "$CHAT_KEY:$id" } ?: CHAT_KEY
        }
    },
    restore = {
        when {
            it.isEmpty() -> null
            it == "login" -> Screen.Login
            it == "sessions" -> Screen.Sessions
            it == CHAT_KEY -> Screen.Chat(null)
            else -> Screen.Chat(it.removePrefix("$CHAT_KEY:"))
        }
    },
)

@Composable
private fun HermesRoot(graph: AppGraph) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen?>(null) }

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
    BackHandler(screen is Screen.Chat) { screen = Screen.Sessions }

    when (screen) {
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
                screen = Screen.Sessions
                graph.reconnect()
            })
        }
        Screen.Sessions -> {
            val sessionList = viewModel {
                SessionListViewModel(SessionsApiSource { graph.sessions }, graph.needsAnswer, graph.seenStore)
            }
            SessionListScreen(
                sessionList,
                host = graph.serverUrl?.host.orEmpty(),
                onOpenChat = { screen = Screen.Chat(it) },
                onNotLoggedIn = { screen = Screen.Login },
                onRetryConnect = graph::reconnect,
            )
        }
        is Screen.Chat -> Box(Modifier.testTag("chat-screen"))
    }
}
