package net.jkraemer.hermes.core.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HermesAuthTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var jar: PersistentCookieJar
    private lateinit var auth: HermesAuth

    @Before
    fun setUp() {
        server.start()
        jar = PersistentCookieJar(FileCookieStore(folder.newFile("cookies.json")))
        auth = HermesAuth(server.url("/"), OkHttpClient.Builder().cookieJar(jar).build())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun loginPostsBasicProviderJson() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"next":"/"}"""))

        auth.login("jens", "pw")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/password-login", request.path)
        assertEquals("""{"provider":"basic","username":"jens","password":"pw"}""", request.body.readUtf8())
    }

    @Test
    fun loginOkStoresCookies() = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "hermes_session_at=tok; Path=/; HttpOnly").setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        assertEquals(LoginResult.Ok, auth.login("jens", "pw"))
        auth.isLoggedIn()

        server.takeRequest()
        assertEquals("hermes_session_at=tok", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun loginWith401IsBadCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(LoginResult.BadCredentials, auth.login("jens", "nope"))
    }

    @Test
    fun loginWith429IsRateLimited() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(LoginResult.RateLimited, auth.login("jens", "pw"))
    }

    @Test
    fun loginWithServerErrorIsUnreachable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(auth.login("jens", "pw") is LoginResult.Unreachable)
    }

    @Test
    fun loginConnectionRefusedIsUnreachable() = runBlocking {
        server.shutdown()
        assertTrue(auth.login("jens", "pw") is LoginResult.Unreachable)
    }

    @Test
    fun isLoggedInReflectsMeStatus() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(401))

        assertTrue(auth.isLoggedIn())
        assertEquals("/api/auth/me", server.takeRequest().path)
        assertFalse(auth.isLoggedIn())
    }

    @Test
    fun mintWsTicketReturnsTicket() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ticket":"t1","ttl_seconds":30}"""))

        assertEquals("t1", auth.mintWsTicket())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/ws-ticket", request.path)
    }

    @Test
    fun mintWsTicketWithoutSessionThrowsNotLoggedIn() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            runBlocking { auth.mintWsTicket() }
            org.junit.Assert.fail("expected NotLoggedInException")
        } catch (_: NotLoggedInException) {
        }
    }

    @Test
    fun logoutClearsCookies() = runBlocking {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "hermes_session_at=tok; Path=/").setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        auth.login("jens", "pw")

        auth.logout()
        auth.isLoggedIn()

        server.takeRequest()
        assertEquals(null, server.takeRequest().getHeader("Cookie"))
    }
}
