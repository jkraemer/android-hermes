package net.jkraemer.hermes.core.cron

import kotlinx.coroutines.runBlocking
import net.jkraemer.hermes.core.auth.NotLoggedInException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class CronApiTest {
    private val server = MockWebServer()
    private lateinit var api: CronApi

    @Before
    fun setUp() {
        server.start()
        api = CronApi(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun jobsListsTheDefaultProfilesJobsWithoutAskingForAProfile() = runBlocking {
        server.enqueue(MockResponse().setBody("""[
            {"id":"j1","name":"Attention Check","prompt":"/attention-check","schedule":{"kind":"interval"},"last_run_at":"2026-11-07T05:34:45.326038+00:00","is_default_profile":true,"extra":1},
            {"id":"j2","name":"Other profile's job","is_default_profile":false},
            {"id":"j3","name":"Older Hermes without the field","last_run_at":null}
        ]"""))

        val jobs = api.jobs()

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/cron/jobs", url.encodedPath)
        assertNull(url.queryParameter("profile"))
        assertEquals(
            listOf(
                CronJob("j1", "Attention Check", lastRunAt = Instant.parse("2026-11-07T05:34:45.326038Z")),
                CronJob("j3", "Older Hermes without the field", lastRunAt = null),
            ),
            jobs,
        )
    }

    @Test
    fun anUnreadableLastRunTimeIsNone() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"j1","name":"x","last_run_at":"yesterday"}]"""))

        assertNull(api.jobs().single().lastRunAt)
    }

    @Test
    fun aJobWithoutANameIsCalledByItsId() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"j1","name":""},{"id":"j2","name":null},{"id":"j3"}]"""))

        assertEquals(listOf("j1", "j2", "j3"), api.jobs().map { it.name })
    }

    @Test
    fun runsAreTheJobsRunSessionsNewestFirst() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"runs":[
            {"id":"local-2","source":"cron","started_at":1794029685.5,"ended_at":null,"scheduler_owned":true,"archived":false,"pinned":0,"cron_job_id":"j1"},
            {"id":"cron_output:j1:exec:0","source":"cron_output","started_at":1794029600.0,"archived":false},
            {"id":"cron_j1_20261107_053000","source":"cron","started_at":1794029400.0,"ended_at":1794029410.0,"scheduler_owned":false,"archived":true}
        ],"limit":50}"""))

        val runs = api.runs("j1", limit = 50)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/cron/jobs/j1/runs", url.encodedPath)
        assertEquals("50", url.queryParameter("limit"))
        assertNull(url.queryParameter("profile"))
        assertEquals(
            listOf(
                CronRun("local-2", Instant.ofEpochMilli(1794029685500), running = true, archived = false),
                CronRun("cron_j1_20261107_053000", Instant.ofEpochSecond(1794029400), running = false, archived = true),
            ),
            runs,
        )
    }

    @Test
    fun runsClampTheLimitToWhatHermesServes() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"runs":[]}"""))
        server.enqueue(MockResponse().setBody("""{"runs":[]}"""))

        api.runs("j1", limit = 500)
        api.runs("j1", limit = 0)

        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("limit"))
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun aJobIdIsOnePathSegment() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"runs":[]}"""))

        api.runs("a/b c")

        assertEquals("/api/cron/jobs/a%2Fb%20c/runs", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun unauthorizedThrowsNotLoggedIn() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
        for (call in listOf<suspend () -> Unit>({ api.jobs() }, { api.runs("j1") })) {
            try {
                runBlocking { call() }
                fail("expected NotLoggedInException")
            } catch (_: NotLoggedInException) {
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun aServerErrorFails(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        api.jobs()
    }
}
