package net.jkraemer.hermes

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private fun serverAnswering(me: Int, wsTicket: Int = 404, sessions: String? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/sessions?") == true && sessions != null) return MockResponse().setBody(sessions)
                return MockResponse().setResponseCode(
                    when (request.path) {
                        "/api/auth/me" -> me
                        "/api/auth/ws-ticket" -> wsTicket
                        else -> 404
                    },
                )
            }
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

    private fun sessionsJson(vararg rows: Pair<String, String>): String {
        val now = System.currentTimeMillis() / 1000.0
        return rows.joinToString(",", """{"sessions":[""", """],"total":${rows.size}}""") { (title, source) ->
            """{"id":"id-$title","title":"$title","source":"$source","started_at":${now - 90},"last_active":${now - 60}}"""
        }
    }

    private fun showsText(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun launchWithoutARecentChatStartsANewChat() {
        serverAnswering(me = 200)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("chat-screen")
            assertTrue(showsText("New chat"))
        }
    }

    @Test
    fun launchReopensTheChatThatWasActiveJustNow() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"))
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("chat-screen")
            compose.waitUntil(10_000) { showsText("Sourdough") }
        }
    }

    @Test
    fun launchIgnoresCronRunsEvenWhenTheyAreRecent() {
        serverAnswering(me = 200, sessions = sessionsJson("Nightly" to "cron"))
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("chat-screen")
            assertTrue(showsText("New chat"))
            assertFalse(showsText("Nightly"))
        }
    }

    @Test
    fun unreachableServerWithStoredCookieStartsANewChat() {
        serverAnswering(me = 200)
        storeSessionCookie()
        server.shutdown()
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("chat-screen") }
    }

    @Test
    fun menuOpensTheDrawerAndViewAllShowsTheFullList() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui", "Nightly" to "cron"))
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("chat-screen")
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.waitUntil(10_000) { showsText("View all") }
            compose.onNodeWithText("View all").performClick()
            awaitTag("sessions-screen")
            // The drawer, still composed, has a "Recents" heading too; the top bar is the one with the menu button.
            compose.onNode(hasText("Recents") and hasAnyAncestor(hasTestTag("sessions-screen"))).assertIsDisplayed()
        }
    }

    @Test
    fun backFromAChatOpenedFromTheListReturnsToTheList() {
        serverAnswering(me = 200, sessions = sessionsJson("Nightly" to "cron"))
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.waitUntil(10_000) { showsText("View all") }
            compose.onNodeWithText("View all").performClick()
            awaitTag("sessions-screen")
            val listRow = hasText("Nightly") and hasAnyAncestor(hasTestTag("session-list"))
            compose.waitUntil(10_000) { compose.onAllNodes(listRow).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(listRow).performClick()
            awaitTag("chat-screen")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitTag("sessions-screen")
        }
    }

    @Test
    fun backFromTheHomeChatLeavesTheApp() {
        serverAnswering(me = 200)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            var finishing = false
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                finishing = it.isFinishing
            }
            assertTrue("back did not finish the activity", finishing)
        }
    }

    @Test
    fun theScreenSurvivesSavingAndRestoring() {
        val chat = Screen.Chat("id:with:colons", "A | title", fromList = true)
        val restore = { screen: Screen? ->
            with(ScreenSaver) {
                val saved = SaverScope { true }.save(screen)!!
                restore(saved)
            }
        }
        assertEquals(chat, restore(chat))
        assertEquals(Screen.Chat(null, null, false, "k").copy(), restore(Screen.Chat(null, null, false, "k")))
        assertEquals(Screen.Sessions, restore(Screen.Sessions))
        assertEquals(Screen.Login, restore(Screen.Login))
        assertEquals(null, restore(null))
    }

    @Test
    fun unreachableServerWithoutCookieShowsLoginScreen() {
        serverAnswering(me = 200)
        server.shutdown()
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("login-screen") }
    }

    @Test
    fun lostSessionOnTheConnectionShowsLoginScreen() {
        serverAnswering(me = 200, wsTicket = 401)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { awaitTag("login-screen") }
    }
}
