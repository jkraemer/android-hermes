package net.jkraemer.hermes.core.sessions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.jkraemer.hermes.core.auth.NotLoggedInException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class SessionsApiTest {
    private val server = MockWebServer()
    private lateinit var api: SessionsApi

    @Before
    fun setUp() {
        server.start()
        api = SessionsApi(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueueList(vararg rows: String, total: Int = rows.size) {
        server.enqueue(MockResponse().setBody("""{"sessions":[${rows.joinToString(",")}],"total":$total,"limit":50,"offset":0,"storage":{}}"""))
    }

    private fun listOne(row: String): SessionSummary = runBlocking {
        enqueueList(row)
        api.list().sessions.single()
    }

    @Test
    fun listRequestsRecentOrderWithoutChatPlatforms() = runBlocking {
        enqueueList()

        api.list()

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/sessions", url.encodedPath)
        assertEquals("recent", url.queryParameter("order"))
        assertEquals("exclude", url.queryParameter("archived"))
        // Cron runs are listed through their jobs; search still finds them.
        assertEquals("telegram,mattermost,cron", url.queryParameter("exclude_sources"))
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
    }

    @Test
    fun listClampsLimit() = runBlocking {
        enqueueList()
        enqueueList()

        api.list(limit = 500)
        api.list(limit = 0)

        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("limit"))
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun listParsesRows() = runBlocking {
        enqueueList(
            """{"id":"a","title":"T","preview":"P","source":"api_server","started_at":1700000000.5,"last_active":1700000100.0,"message_count":7,"pinned":true,"archived":false,"extra":1}""",
            total = 12,
        )

        val page = api.list()

        assertEquals(12, page.total)
        val s = page.sessions.single()
        assertEquals(SessionSummary("a", "T", "P", "api_server", Instant.ofEpochMilli(1700000000500), Instant.ofEpochSecond(1700000100), 7, true, false), s)
    }

    @Test
    fun listPrefersResolvedId() {
        assertEquals("b", listOne("""{"id":"a","resolved_id":"b","source":"x","started_at":1.0}""").id)
    }

    @Test
    fun missingLastActiveFallsBackToStartedAt() {
        val s = listOne("""{"id":"a","source":"x","started_at":1700000000.0,"last_active":null}""")
        assertEquals(Instant.ofEpochSecond(1700000000), s.lastActive)
    }

    @Test
    fun cronSourceIsCron() {
        assertTrue(listOne("""{"id":"a","source":"cron","started_at":1.0}""").isCron)
        assertFalse(listOne("""{"id":"a","source":"api_server","started_at":1.0}""").isCron)
    }

    @Test
    fun searchBlankQueryMakesNoRequest() = runBlocking {
        assertEquals(emptyList<SearchHit>(), api.search("  "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun searchSendsQueryAndExclusions() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"id":"s1","title":"Hit","source":"api_server","started_at":1700000000.0,"last_active":null}]}"""))

        val results = api.search("hello world", limit = 5)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/sessions/search", url.encodedPath)
        assertEquals("hello world", url.queryParameter("q"))
        assertEquals("telegram,mattermost", url.queryParameter("exclude_sources"))
        assertEquals("5", url.queryParameter("limit"))
        assertEquals("s1", results.single().session.id)
    }

    @Test
    fun searchHitCarriesTheContentSnippetWithItsMatchMarked() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[
            {"id":"s1","snippet":"...the >>>sourdough<<<\n  starter needs\nfeeding","role":"user","source":"api_server","started_at":1.0}
        ]}"""))

        val hit = api.search("sourdough").single()

        assertEquals(Snippet("...the sourdough starter needs feeding", listOf(7..15)), hit.snippet)
    }

    @Test
    fun searchHitFromAnIdOrTitleMatchHasAPlainSnippet() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[
            {"id":"s1","snippet":"Session ID: s1","source":"tui","started_at":1.0},
            {"id":"s2","source":"tui","started_at":1.0}
        ]}"""))

        val hits = api.search("s1")

        assertEquals(Snippet("Session ID: s1", emptyList()), hits[0].snippet)
        assertEquals(null, hits[1].snippet)
    }

    @Test
    fun updateSendsOnlyGivenFieldsWithMutationIdentity() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"session_id":"s1","exists":true,"runtime_revision":4,"runtime_generation":1}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.update("s1", pinned = true)

        val snapshot = server.takeRequest()
        assertEquals("GET", snapshot.method)
        assertEquals("/api/sessions/s1/mutation-snapshot", snapshot.path)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/sessions/s1", patch.path)
        val body = Json.parseToJsonElement(patch.body.readUtf8()).jsonObject
        assertEquals(setOf("request_id", "expected_revision", "pinned"), body.keys)
        assertEquals("4", body.getValue("expected_revision").toString())
        assertEquals("true", body.getValue("pinned").toString())
    }

    private fun enqueueMessages(vararg messages: String) {
        server.enqueue(MockResponse().setBody("""{"session_id":"s1","messages":[${messages.joinToString(",")}],"pagination":{}}"""))
    }

    @Test
    fun finalReplyReadsTheLatestPageOfMessages() = runBlocking {
        enqueueMessages()

        api.finalReply("a/b")

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/sessions/a%2Fb/messages", url.encodedPath)
        assertEquals("latest", url.queryParameter("order"))
        assertEquals("10", url.queryParameter("limit"))
        assertEquals("false", url.queryParameter("inline_images"))
    }

    @Test
    fun finalReplyIsTheLastAssistantMessageWithText() = runBlocking {
        enqueueMessages(
            """{"role":"user","content":"[IMPORTANT: You are running as a scheduled cron job.]"}""",
            """{"role":"assistant","content":"Let me check."}""",
            """{"role":"assistant","content":"","tool_calls":[{"id":"t1"}]}""",
            """{"role":"tool","content":"{\"total_count\": 0}"}""",
            """{"role":"assistant","content":"Two new mails.\n\n- one\n- two"}""",
            """{"role":"assistant","content":"  "}""",
            """{"role":"tool","content":"ok"}""",
        )

        assertEquals("Two new mails.\n\n- one\n- two", api.finalReply("s1"))
    }

    @Test
    fun finalReplyPrefersTheDisplayTextAndSkipsContentThatIsNotText() = runBlocking {
        enqueueMessages(
            """{"role":"assistant","text":"Shown text","content":"stored text"}""",
            """{"role":"assistant","content":[{"type":"image_url"}]}""",
        )

        assertEquals("Shown text", api.finalReply("s1"))
    }

    private fun user(text: String) = """{"role":"user","content":"$text"}"""
    private fun said(text: String) = """{"role":"assistant","content":"$text"}"""
    private fun tool() = """{"role":"tool","content":"ok"}"""

    @Test
    fun theFinalReplyIsTheRunsReportNotTheAnswerToALaterReply() = runBlocking {
        enqueueMessages(user("[IMPORTANT: cron] check mail"), tool(), said("Two new mails."), user("Which ones?"), said("From Anna and Bob."))

        assertEquals("Two new mails.", api.finalReply("s1"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFullLatestPageOfTheRunsOwnWorkEndsInTheReport() = runBlocking {
        enqueueMessages(*(Array(9) { tool() } + said("Two new mails.")))

        assertEquals("Two new mails.", api.finalReply("s1"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFullLatestPageWithAReplyInItReadsTheRunFromItsStart() = runBlocking {
        // The latest page: the end of the run's work, the user's reply and the answer.
        enqueueMessages(*(Array(6) { tool() } + said("Two new mails.") + user("Which ones?") + tool() + said("From Anna and Bob.")))
        // From the start, in pages of 50: the prompt and the run's long work, then its report and the reply.
        enqueueMessages(*(arrayOf(user("[IMPORTANT: cron] check mail")) + Array(49) { tool() }))
        enqueueMessages(said("Two new mails."), user("Which ones?"), said("From Anna and Bob."))

        assertEquals("Two new mails.", api.finalReply("s1"))

        server.takeRequest()
        val first = server.takeRequest().requestUrl!!
        assertEquals(listOf("oldest", "50", "0", "false"), listOf("order", "limit", "offset", "inline_images").map { first.queryParameter(it) })
        assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("offset"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun readingFromTheStartStopsAtTheEndOfTheTranscript() = runBlocking {
        // Exactly one page long, so the latest page cannot tell whether its user message is the prompt.
        val transcript = arrayOf(user("check mail")) + Array(8) { tool() } + said("All quiet.")
        enqueueMessages(*transcript)
        enqueueMessages(*transcript)

        assertEquals("All quiet.", api.finalReply("s1"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun finalReplyIsNullWithoutAnAssistantMessage() = runBlocking {
        enqueueMessages("""{"role":"user","content":"prompt"}""", """{"role":"tool","content":"ok"}""")

        assertEquals(null, api.finalReply("s1"))
    }

    @Test
    fun unauthorizedThrowsNotLoggedIn() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(401)) }
        val calls = listOf<suspend () -> Unit>(
            { api.list() },
            { api.search("q") },
            { api.update("s1", title = "x") },
            { api.finalReply("s1") },
        )
        for (call in calls) {
            try {
                runBlocking { call() }
                fail("expected NotLoggedInException")
            } catch (_: NotLoggedInException) {
            }
        }
    }

    @Test
    fun explicitNullsFallBackToDefaults() {
        val summary = listOne("""{"id":"s1","pinned":null,"archived":null,"message_count":null}""")

        assertFalse(summary.pinned)
        assertFalse(summary.archived)
        assertEquals(0, summary.messageCount)
    }

    // An unclosed response body keeps its connection from going back to the pool, so the next
    // request would open a new one (sequence number 0 again).
    @Test
    fun everyResponseIsClosedSoTheConnectionIsReused() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("down"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        enqueueList()

        runCatching { api.list() }
        runCatching { api.search("q") }
        runCatching { api.update("s1", title = "x") }
        api.list()

        assertEquals(listOf(0, 1, 2, 3), (0..3).map { server.takeRequest().sequenceNumber })
    }
}
