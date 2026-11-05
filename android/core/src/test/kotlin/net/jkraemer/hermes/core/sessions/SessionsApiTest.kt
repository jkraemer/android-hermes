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
        assertEquals("telegram,mattermost", url.queryParameter("exclude_sources"))
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
        assertEquals(emptyList<SessionSummary>(), api.search("  "))
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
        assertEquals("s1", results.single().id)
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

    @Test
    fun unauthorizedThrowsNotLoggedIn() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(401)) }
        val calls = listOf<suspend () -> Unit>(
            { api.list() },
            { api.search("q") },
            { api.update("s1", title = "x") },
        )
        for (call in calls) {
            try {
                runBlocking { call() }
                fail("expected NotLoggedInException")
            } catch (_: NotLoggedInException) {
            }
        }
    }
}
