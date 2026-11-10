package net.jkraemer.hermes.core.push

import kotlinx.coroutines.runBlocking
import net.jkraemer.hermes.core.auth.NotLoggedInException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PushApiTest {
    private val server = MockWebServer()
    private lateinit var api: PushApi

    @Before
    fun setUp() {
        server.start()
        api = PushApi(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun subscribePutsTheKeysAsJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        api.subscribe("https://ntfy.sh/up?x=1", "p256", "authkey")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/plugins/hermes-app/subscription", request.path)
        assertEquals("""{"endpoint":"https://ntfy.sh/up?x=1","p256dh":"p256","auth":"authkey"}""", request.body.readUtf8())
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    }

    @Test
    fun unsubscribeDeletesTheSubscription() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        api.unsubscribe()

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/plugins/hermes-app/subscription", request.path)
    }

    @Test
    fun followPutsTheEscapedSessionId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        api.follow("2026 11/a")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/plugins/hermes-app/followed/2026%2011%2Fa", request.path)
    }

    @Test
    fun sendTestPostsToTest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        api.sendTest()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/plugins/hermes-app/test", request.path)
    }

    @Test
    fun missingPluginIsReported() {
        for (call in listOf<suspend () -> Unit>({ api.subscribe("e", "p", "a") }, { api.unsubscribe() }, { api.follow("s") }, { api.sendTest() })) {
            server.enqueue(MockResponse().setResponseCode(404))
            assertThrows(PluginMissingException::class.java) { runBlocking { call() } }
        }
    }

    @Test
    fun sendTestWithoutASubscriptionIsReported() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"no subscription"}"""))

        assertThrows(NotSubscribedException::class.java) { runBlocking { api.sendTest() } }
    }

    @Test
    fun aLostLoginIsReported() {
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertThrows(NotLoggedInException::class.java) { runBlocking { api.follow("s") } }
        }
    }

    @Test
    fun otherFailuresAreIllegalState() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(IllegalStateException::class.java) { runBlocking { api.unsubscribe() } }
        server.enqueue(MockResponse().setResponseCode(409))
        assertThrows(IllegalStateException::class.java) { runBlocking { api.follow("s") } }
    }
}
