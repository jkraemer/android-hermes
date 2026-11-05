package net.jkraemer.hermes.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import net.jkraemer.hermes.core.auth.LoginResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {
    @get:Rule val compose = createComposeRule()

    private val attempts = mutableListOf<Triple<String, String, String>>()
    private var loggedIn = 0

    private fun show(initialServerUrl: String = "https://hermes.example.ts.net/", result: LoginResult) {
        val viewModel = LoginViewModel(initialServerUrl) { url, user, password ->
            attempts += Triple(url, user, password)
            result
        }
        compose.setContent { MaterialTheme { LoginScreen(viewModel, onLoggedIn = { loggedIn++ }) } }
    }

    private fun signIn(server: String? = null) {
        if (server != null) {
            compose.onNodeWithTag("server").performTextClearance()
            compose.onNodeWithTag("server").performTextInput(server)
        }
        compose.onNodeWithTag("username").performTextInput("jens")
        compose.onNodeWithTag("password").performTextInput("secret")
        compose.onNodeWithText("Sign in").performClick()
        compose.waitForIdle()
    }

    @Test
    fun prefillsTheServerUrl() {
        show(result = LoginResult.Ok)
        compose.onNodeWithText("https://hermes.example.ts.net/").assertIsDisplayed()
    }

    @Test
    fun showsErrorForBadCredentials() {
        show(result = LoginResult.BadCredentials)
        signIn()
        compose.onNodeWithText("Wrong username or password").assertIsDisplayed()
        assertEquals(0, loggedIn)
    }

    @Test
    fun showsRateLimitHint() {
        show(result = LoginResult.RateLimited)
        signIn()
        compose.onNodeWithText("Too many attempts, wait a minute").assertIsDisplayed()
    }

    @Test
    fun showsTailscaleHintWhenUnreachable() {
        show(result = LoginResult.Unreachable(IOException("timeout")))
        signIn()
        compose.onNodeWithText("Can't reach hermes.example.ts.net — is Tailscale on?").assertIsDisplayed()
    }

    @Test
    fun successNavigatesToSessions() {
        show(result = LoginResult.Ok)
        signIn()
        assertEquals(1, loggedIn)
        assertEquals(listOf(Triple("https://hermes.example.ts.net/", "jens", "secret")), attempts)
    }

    @Test
    fun assumesHttpsWhenTheServerHasNoScheme() {
        show(initialServerUrl = "", result = LoginResult.Ok)
        signIn(server = "hermes.example.ts.net")
        assertEquals("https://hermes.example.ts.net/", attempts.single().first)
    }

    @Test
    fun rejectsAServerAddressThatIsNotAUrl() {
        show(result = LoginResult.Ok)
        signIn(server = "http://")
        compose.onNodeWithText("Enter a valid server address").assertIsDisplayed()
        assertEquals(0, attempts.size)
    }
}
