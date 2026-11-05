package net.jkraemer.hermes.core.auth

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentCookieJarTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val url = "http://hermes.example:9119/".toHttpUrl()

    private fun cookie(name: String, expiresAt: Long = System.currentTimeMillis() + 60_000, value: String = "v-$name") =
        Cookie.Builder().name(name).value(value).domain("hermes.example").path("/").expiresAt(expiresAt).build()

    @Test
    fun cookieJarSurvivesRestart() {
        val file = folder.newFile("cookies.json")
        PersistentCookieJar(FileCookieStore(file)).saveFromResponse(url, listOf(cookie("hermes_session_at")))

        val restarted = PersistentCookieJar(FileCookieStore(file))

        assertEquals(listOf("hermes_session_at"), restarted.loadForRequest(url).map { it.name })
        assertEquals("v-hermes_session_at", restarted.loadForRequest(url).single().value)
    }

    @Test
    fun expiredCookiesAreDropped() {
        val file = folder.newFile("cookies.json")
        val store = FileCookieStore(file)
        store.save(listOf(cookie("old", expiresAt = System.currentTimeMillis() - 1_000), cookie("fresh")))

        val jar = PersistentCookieJar(store)

        assertEquals(listOf("fresh"), jar.loadForRequest(url).map { it.name })
    }

    @Test
    fun savingACookieAgainReplacesIt() {
        val jar = PersistentCookieJar(FileCookieStore(folder.newFile("cookies.json")))
        jar.saveFromResponse(url, listOf(cookie("a")))
        jar.saveFromResponse(url, listOf(cookie("a", value = "second")))

        assertEquals(listOf("second"), jar.loadForRequest(url).map { it.value })
    }

    @Test
    fun clearForgetsCookiesAlsoAfterRestart() {
        val file = folder.newFile("cookies.json")
        val jar = PersistentCookieJar(FileCookieStore(file))
        jar.saveFromResponse(url, listOf(cookie("a")))

        jar.clear()

        assertTrue(jar.loadForRequest(url).isEmpty())
        assertTrue(PersistentCookieJar(FileCookieStore(file)).loadForRequest(url).isEmpty())
    }

    @Test
    fun fileCookieStoreKeepsAllCookieAttributes() {
        val store = FileCookieStore(folder.newFile("cookies.json"))
        val original = Cookie.Builder().name("n").value("v").domain("hermes.example").path("/p")
            .expiresAt(System.currentTimeMillis() + 60_000).secure().httpOnly().build()
        store.save(listOf(original))

        assertEquals(listOf(original), store.load())
    }

    @Test
    fun missingFileLoadsNoCookies() {
        assertTrue(FileCookieStore(java.io.File(folder.root, "absent.json")).load().isEmpty())
    }
}
