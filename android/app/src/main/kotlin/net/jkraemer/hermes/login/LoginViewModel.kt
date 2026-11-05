package net.jkraemer.hermes.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jkraemer.hermes.core.auth.LoginResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LoginUiState(
    val serverUrl: String,
    val busy: Boolean = false,
    val error: String? = null,
    val succeeded: Boolean = false,
)

/** [login] gets the normalized server URL, the username and the password. */
class LoginViewModel(
    initialServerUrl: String,
    private val login: suspend (String, String, String) -> LoginResult,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState(initialServerUrl))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun submit(serverUrl: String, username: String, password: String) {
        if (_state.value.busy) return
        val url = parseServerUrl(serverUrl)
        if (url == null) {
            _state.update { it.copy(serverUrl = serverUrl, error = "Enter a valid server address") }
            return
        }
        _state.update { it.copy(serverUrl = serverUrl, busy = true, error = null) }
        viewModelScope.launch {
            val result = login(url.toString(), username, password)
            _state.update {
                when (result) {
                    LoginResult.Ok -> it.copy(busy = false, succeeded = true)
                    LoginResult.BadCredentials -> it.copy(busy = false, error = "Wrong username or password")
                    LoginResult.RateLimited -> it.copy(busy = false, error = "Too many attempts, wait a minute")
                    is LoginResult.Unreachable ->
                        it.copy(busy = false, error = "Can't reach ${url.host} — is Tailscale on?")
                }
            }
        }
    }

    /** Called once the success has been acted on, so showing the screen again does not repeat it. */
    fun loginHandled() {
        _state.update { it.copy(succeeded = false) }
    }

    /** Assumes https when there is no scheme; the trailing slash keeps relative API paths resolving below it. */
    private fun parseServerUrl(input: String): HttpUrl? {
        val trimmed = input.trim()
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
    }
}
