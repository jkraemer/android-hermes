package net.jkraemer.hermes

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import okhttp3.Cookie
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val graph get() = (RuntimeEnvironment.getApplication() as HermesApp).graph
    private val server = MockWebServer()

    @After fun stopServer() {
        server.shutdown()
        graph.close()
    }

    /** Points the app at a fake server whose `api/auth/me` answers with [me]; everything else is a 404. */
    private fun serverAnswering(me: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(
                if (request.path == "/api/auth/me") me else 404,
            )
        }
        server.start()
        graph.serverUrl = server.url("/")
    }

    /** Stores a session cookie for the server, as an earlier login would have. */
    private fun storeSessionCookie() {
        val url = graph.serverUrl!!
        val cookie = Cookie.Builder().name("session").value("x").hostOnlyDomain(url.host).build()
        graph.cookieJar.saveFromResponse(url, listOf(cookie))
    }

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun launchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun withoutServerUrlShowsLoginScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("login-screen") }
    }

    @Test
    fun expiredLoginShowsLoginScreen() {
        serverAnswering(me = 401)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("login-screen") }
    }

    @Test
    fun validLoginShowsSessionsScreen() {
        serverAnswering(me = 200)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("sessions-screen") }
    }

    @Test
    fun unreachableServerWithStoredCookieShowsSessionsScreen() {
        serverAnswering(me = 200)
        storeSessionCookie()
        server.shutdown()
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("sessions-screen") }
    }

    @Test
    fun unreachableServerWithoutCookieShowsLoginScreen() {
        serverAnswering(me = 200)
        server.shutdown()
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("login-screen") }
    }
}
