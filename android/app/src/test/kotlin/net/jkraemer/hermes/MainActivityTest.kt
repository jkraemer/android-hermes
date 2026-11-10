package net.jkraemer.hermes

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jkraemer.hermes.chat.ChatViewModel
import okhttp3.Cookie
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val CRON_TRANSCRIPT = """[{"role":"user","text":"/attention-check"},{"role":"assistant","text":"All quiet."}]"""

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val graph get() = (RuntimeEnvironment.getApplication() as HermesApp).graph
    private val server = MockWebServer()

    /** Every JSON-RPC request the fake gateway received, as method and params. */
    private val rpcCalls = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    private fun rpcCalls(method: String) = rpcCalls.filter { it.first == method }.map { it.second }

    /** A gateway that is ready at once, answers every request, and opens whatever session it is asked for. */
    private inner class FakeGateway(private val transcript: String = "[]") : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"e1"}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Json.parseToJsonElement(text.trimEnd('\n')).jsonObject
            val method = frame["method"]?.jsonPrimitive?.content ?: return
            val params = frame["params"] as? JsonObject ?: JsonObject(emptyMap())
            rpcCalls += method to params
            val stored = params["session_id"]?.jsonPrimitive?.content ?: "new-${rpcCalls.size}"
            val result = when (method) {
                "session.resume", "session.create" ->
                    """{"session_id":"live-$stored","stored_session_id":"$stored","messages":$transcript,"running":false,"open_requests":[]}"""
                else -> "{}"
            }
            webSocket.send("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""" + "\n")
        }
    }

    @After fun stopServer() {
        server.shutdown()
        graph.close()
    }

    /**
     * Points the app at a fake server whose `api/auth/me` answers with [me]; everything else is a 404. [search] answers
     * every search; [cron] is the one cron job "Nightly" (id `j1`) with one run, `run1`, that reported "Backups done.".
     */
    private fun serverAnswering(
        me: Int,
        wsTicket: Int = 404,
        sessions: String? = null,
        gateway: Boolean = false,
        transcript: String = "[]",
        search: String? = null,
        cron: Boolean = false,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/api/sessions?") && sessions != null) return MockResponse().setBody(sessions)
                if (path.startsWith("/api/sessions/search?") && search != null) return MockResponse().setBody(search)
                if (cron && path == "/api/cron/jobs") return MockResponse().setBody("""[{"id":"j1","name":"Nightly","last_run_at":null}]""")
                if (cron && path.startsWith("/api/cron/jobs/j1/runs?")) {
                    val started = System.currentTimeMillis() / 1000.0 - 300
                    return MockResponse().setBody("""{"runs":[{"id":"run1","source":"cron","started_at":$started,"scheduler_owned":false,"archived":false}]}""")
                }
                if (cron && path.startsWith("/api/sessions/run1/messages?")) {
                    return MockResponse().setBody("""{"messages":[{"role":"user","content":"/nightly"},{"role":"assistant","content":"Backups done."}]}""")
                }
                if (gateway && request.path == "/api/auth/ws-ticket") return MockResponse().setBody("""{"ticket":"t"}""")
                if (gateway && request.path?.startsWith("/api/ws") == true) return MockResponse().withWebSocketUpgrade(FakeGateway(transcript))
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

    /** Rows of title to source, each active a minute longer ago than the one before: the first is the newest. */
    private fun sessionsJson(vararg rows: Pair<String, String>): String {
        val now = System.currentTimeMillis() / 1000.0
        return rows.withIndex().joinToString(",", """{"sessions":[""", """],"total":${rows.size}}""") { (index, row) ->
            val (title, source) = row
            """{"id":"id-$title","title":"$title","source":"$source","started_at":${now - 90 - index * 60},"message_count":2,"last_active":${now - 60 - index * 60}}"""
        }
    }

    private fun inChat(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("chat-screen"))

    private fun inList(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("session-list"))

    private fun await(matcher: SemanticsMatcher) =
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun openDrawer() = compose.onNodeWithContentDescription("Menu").performClick()

    private fun openDrawerEntry(title: String) {
        openDrawer()
        await(hasText(title) and hasClickAction())
        compose.onNode(hasText(title) and hasClickAction()).performClick()
    }

    private fun pressBack(scenario: ActivityScenario<MainActivity>) =
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    /** The chat view models the activity holds, oldest first by their store key. */
    private fun chatModels(scenario: ActivityScenario<MainActivity>): List<ChatViewModel> {
        var models = emptyList<ChatViewModel>()
        scenario.onActivity { activity ->
            models = activity.viewModelStore.keys().mapNotNull { activity.viewModelStore[it] as? ChatViewModel }
        }
        return models
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
            // The chat must not be the run.
            assertTrue(compose.onAllNodes(inChat("Nightly")).fetchSemanticsNodes().isEmpty())
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
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"))
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.waitUntil(10_000) { showsText("View all") }
            compose.onNodeWithText("View all").performClick()
            awaitTag("sessions-screen")
            val listRow = hasText("Sourdough") and hasAnyAncestor(hasTestTag("session-list"))
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
    fun openingAnotherChatFromTheDrawerStartsAFreshControllerAndClosesTheOldOne() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui", "Baking" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            compose.waitUntil(10_000) { rpcCalls("session.resume").size == 1 }
            assertEquals("id-Sourdough", rpcCalls("session.resume").single()["session_id"]?.jsonPrimitive?.content)
            val first = chatModels(scenario).single()

            openDrawerEntry("Baking")

            await(inChat("Baking"))
            compose.waitUntil(10_000) { rpcCalls("session.resume").size == 2 }
            assertEquals("id-Baking", rpcCalls("session.resume").last()["session_id"]?.jsonPrimitive?.content)
            assertTrue("the first chat's controller was not closed", first.isClosed)
            val open = chatModels(scenario).filter { !it.isClosed }
            assertEquals(1, open.size)
            assertTrue(open.single() !== first)
        }
    }

    @Test
    fun backFromAChatOpenedFromTheDrawerOfTheHomeChatLeavesTheApp() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui", "Baking" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            openDrawerEntry("Baking")
            await(inChat("Baking"))

            var finishing = false
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                finishing = it.isFinishing
            }
            assertTrue("back did not leave the app", finishing)
        }
    }

    @Test
    fun backFromAChatSwitchedToInTheDrawerReturnsToTheListTheFirstChatCameFrom() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui", "Baking" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            openDrawer()
            await(hasText("View all"))
            compose.onNodeWithText("View all").performClick()
            awaitTag("sessions-screen")
            await(inList("Baking"))
            compose.onNode(inList("Baking")).performClick()
            await(inChat("Baking"))

            openDrawerEntry("Sourdough")
            await(inChat("Sourdough"))
            pressBack(scenario)

            awaitTag("sessions-screen")
        }
    }

    @Test
    fun backFromTheListReturnsToTheChatItWasOpenedFrom() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            await(inChat("Sourdough"))
            openDrawer()
            await(hasText("View all"))
            compose.onNodeWithText("View all").performClick()
            awaitTag("sessions-screen")

            pressBack(scenario)

            awaitTag("chat-screen")
            await(inChat("Sourdough"))
            // The chat is opened again with a fresh controller.
            compose.waitUntil(10_000) { rpcCalls("session.resume").size == 2 }
        }
    }

    @Test
    fun backFromTheListToANewChatThatWasNeverUsedStartsAnotherNewChat() {
        serverAnswering(me = 200, gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            compose.waitUntil(10_000) { rpcCalls("session.create").size == 1 }
            openDrawer()
            await(hasText("View all"))
            compose.onNodeWithText("View all").performClick()
            awaitTag("sessions-screen")

            pressBack(scenario)

            awaitTag("chat-screen")
            compose.waitUntil(10_000) { rpcCalls("session.create").size == 2 }
            assertEquals(emptyList<JsonObject>(), rpcCalls("session.resume"))
        }
    }

    @Test
    fun backFromTheListWithSearchOpenClosesSearchFirst() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            openDrawer()
            await(hasContentDescription("Search") and hasClickAction())
            compose.onAllNodes(hasContentDescription("Search") and hasClickAction())[0].performClick()
            awaitTag("sessions-screen")
            awaitTag("search")

            pressBack(scenario)

            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("search")).fetchSemanticsNodes().isEmpty() }
            awaitTag("sessions-screen")
        }
    }

    @Test
    fun theDrawerAlreadyListsSessionsTheFirstTimeItIsOpened() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui", "Baking" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("chat-screen")
            // Baking is not the open chat, so it can only be the drawer's entry; no menu click has happened.
            compose.waitUntil(10_000) { showsText("Baking") }
        }
    }

    @Test
    fun settingsRoundTrip() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            await(inChat("Sourdough"))
            openDrawer()
            await(hasText("Settings"))
            compose.onNodeWithText("Settings").performClick()
            awaitTag("settings-screen")
            compose.onNodeWithText("Favorite models").performClick()
            awaitTag("favorite-models-screen")

            scenario.recreate()
            awaitTag("favorite-models-screen")
            pressBack(scenario)
            awaitTag("settings-screen")

            scenario.recreate()
            awaitTag("settings-screen")
            pressBack(scenario)
            awaitTag("chat-screen")
            await(inChat("Sourdough"))
        }
    }

    @Test
    fun theScreenSurvivesSavingAndRestoring() {
        val restore = { screen: Screen? ->
            with(ScreenSaver) {
                val saved = SaverScope { true }.save(screen)!!
                restore(saved)
            }
        }
        val home = Screen.Chat("id", "Home | title", key = "h")
        val list = Screen.Sessions(back = home)
        val fromList = Screen.Chat("id:with:colons", "A \u001f title", fromList = list, key = "k")
        assertEquals(fromList, restore(fromList))
        val cron = Screen.Chat("cron1", "Nightly", cron = true, key = "c")
        assertEquals(cron, restore(cron))
        val job = Screen.Job("j:1", "Nightly | job", back = list, key = "j")
        assertEquals(job, restore(job))
        val jobFromAChat = Screen.Job("j:1", "Nightly", back = home, key = "j2")
        assertEquals(jobFromAChat, restore(jobFromAChat))
        val runFromJob = Screen.Chat("run1", "Nightly", fromList = list, cron = true, fromJob = job, key = "r")
        assertEquals(runFromJob, restore(runFromJob))
        assertEquals(Screen.Sessions(back = job), restore(Screen.Sessions(back = job)))
        assertEquals(Screen.Chat(null, null, null, "k"), restore(Screen.Chat(null, null, null, "k")))
        assertEquals(list, restore(list))
        assertEquals(Screen.Sessions(), restore(Screen.Sessions()))
        val settings = Screen.Settings(back = home)
        assertEquals(settings, restore(settings))
        assertEquals(Screen.Settings(back = job), restore(Screen.Settings(back = job)))
        assertEquals(Screen.Settings(back = list), restore(Screen.Settings(back = list)))
        assertEquals(Screen.FavoriteModels(back = settings), restore(Screen.FavoriteModels(back = settings)))
        assertEquals(Screen.FavoriteModels(back = home), restore(Screen.FavoriteModels(back = home)))
        assertEquals(Screen.FavoriteModels(back = null), restore(Screen.FavoriteModels(back = null)))
        assertEquals(Screen.Login, restore(Screen.Login))
        assertEquals(null, restore(null))
    }

    @Test
    fun aMessageWaitingToBeSentIsNotSaved() {
        val saved = with(ScreenSaver) { SaverScope { true }.save(Screen.Chat("run1", "Nightly", cron = true, key = "k", pendingMessage = "hello")) }!!
        assertEquals(Screen.Chat("run1", "Nightly", cron = true, key = "k"), ScreenSaver.restore(saved))
    }

    @Test
    fun savedChatWithoutTheCronFieldRestoresAsNotCron() {
        val saved = """{"screen":"chat","key":"k","id":"id1","title":"T"}"""
        assertEquals(Screen.Chat("id1", "T", key = "k"), ScreenSaver.restore(saved))
    }

    private fun inFeed(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("job-feed-screen"))

    @Test
    fun openingACronRunFromASearchHitShowsItsWorkCollapsed() {
        val hit = """{"results":[{"id":"run1","title":"Nightly · Nov 05 11:58","source":"cron","started_at":1.0,"message_count":2}]}"""
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true, transcript = CRON_TRANSCRIPT, search = hit)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("chat-screen")
            openDrawer()
            await(hasContentDescription("Search") and hasClickAction())
            compose.onAllNodes(hasContentDescription("Search") and hasClickAction())[0].performClick()
            awaitTag("search")
            compose.onNodeWithTag("search").performTextInput("nightly")
            await(inList("Nightly · Nov 05 11:58"))
            compose.onNode(inList("Nightly · Nov 05 11:58")).performClick()

            await(inChat("Show work (1 step)"))
            assertTrue(compose.onAllNodes(inChat("/attention-check")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun aJobInTheDrawerOpensItsFeedWhoseRunOpensWithItsWorkCollapsedAndBackGoesToTheFeed() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true, transcript = CRON_TRANSCRIPT, cron = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            openDrawerEntry("Nightly")
            awaitTag("job-feed-screen")
            await(inFeed("Backups done."))

            compose.onNode(inFeed("Show the whole run") and hasClickAction()).performClick()
            await(inChat("Show work (1 step)"))
            compose.waitUntil(10_000) { rpcCalls("session.resume").any { it["session_id"]?.jsonPrimitive?.content == "run1" } }

            pressBack(scenario)
            awaitTag("job-feed-screen")
        }
    }

    @Test
    fun aJobOpenedFromTheDrawerOfTheHomeChatGoesBackToThatChat() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true, cron = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            await(inChat("Sourdough"))
            openDrawerEntry("Nightly")
            awaitTag("job-feed-screen")

            var finishing = false
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                finishing = it.isFinishing
            }

            assertTrue("back left the app", !finishing)
            awaitTag("chat-screen")
            await(inChat("Sourdough"))
            compose.waitUntil(10_000) { rpcCalls("session.resume").count { it["session_id"]?.jsonPrimitive?.content == "id-Sourdough" } == 2 }
        }
    }

    @Test
    fun aJobOpenedFromTheListGoesBackToTheList() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true, cron = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            openDrawer()
            await(hasText("View all"))
            compose.onNodeWithText("View all").performClick()
            awaitTag("sessions-screen")
            await(inList("Nightly"))
            compose.onNode(inList("Nightly")).performClick()
            awaitTag("job-feed-screen")

            pressBack(scenario)
            awaitTag("sessions-screen")
        }
    }

    @Test
    fun aReplyFromTheFeedIsSentInTheNewestRunsChat() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true, transcript = CRON_TRANSCRIPT, cron = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTag("chat-screen")
            openDrawerEntry("Nightly")
            await(inFeed("Backups done."))

            compose.onNode(hasTestTag("composer") and hasAnyAncestor(hasTestTag("job-feed-screen"))).performTextInput("Which backups?")
            compose.onNode(hasContentDescription("Send") and hasAnyAncestor(hasTestTag("job-feed-screen"))).performClick()

            awaitTag("chat-screen")
            compose.waitUntil(10_000) { rpcCalls("prompt.submit").isNotEmpty() }
            assertEquals("Which backups?", rpcCalls("prompt.submit").single()["text"]?.jsonPrimitive?.content)
            assertTrue(rpcCalls("session.resume").any { it["session_id"]?.jsonPrimitive?.content == "run1" })
            await(inChat("Show work (1 step)"))
        }
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

    @Test
    fun savedStateInAFormatThatIsNotTheCurrentOneRestoresToNothing() {
        val old = listOf(
            "login", "sessions", "chat", "chat:id-1",
            listOf("chat", "key", "false", "id", "title").joinToString("\u001f"),
            "{", "{\"screen\":\"other\"}", "{\"screen\":\"chat\"}", "{\"screen\":\"sessions\",\"back\":{\"screen\":\"login\"}}",
            "[]", "null", "\u0000",
        )
        for (saved in old) assertEquals(saved, null, ScreenSaver.restore(saved))
    }

    @Test
    fun backClosesTheDrawerBeforeSearch() {
        serverAnswering(me = 200, sessions = sessionsJson("Sourdough" to "tui"), gateway = true)
        storeSessionCookie()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTag("chat-screen")
            openDrawer()
            await(hasContentDescription("Search") and hasClickAction())
            compose.onAllNodes(hasContentDescription("Search") and hasClickAction())[0].performClick()
            awaitTag("search")
            openDrawer()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("View all")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText("View all").assertIsDisplayed()

            pressBack(scenario)
            compose.waitForIdle()

            // The drawer closed; search is still open.
            assertEquals(1, compose.onAllNodes(hasTestTag("search")).fetchSemanticsNodes().size)
            pressBack(scenario)
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("search")).fetchSemanticsNodes().isEmpty() }
            awaitTag("sessions-screen")
        }
    }

    @Test
    fun theWindowResizesForTheKeyboardSoComposeAppliesItsInsetOnlyOnce() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(android.content.ComponentName(app, MainActivity::class.java), 0)
        // The manifest's adjustResize is what is checked; the constant is deprecated only for setting it in code.
        @Suppress("DEPRECATION")
        val adjustResize = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        assertEquals(
            adjustResize,
            info.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }
}
