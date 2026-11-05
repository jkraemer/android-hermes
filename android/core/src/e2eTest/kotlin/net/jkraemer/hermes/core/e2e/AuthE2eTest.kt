package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.runBlocking
import net.jkraemer.hermes.core.auth.FileCookieStore
import net.jkraemer.hermes.core.auth.HermesAuth
import net.jkraemer.hermes.core.auth.LoginResult
import net.jkraemer.hermes.core.auth.PersistentCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun newAuth(): HermesAuth {
        val jar = PersistentCookieJar(FileCookieStore(folder.newFile()))
        return HermesAuth(E2eEnv.url.toHttpUrl(), OkHttpClient.Builder().cookieJar(jar).build())
    }

    @Test
    fun loginAndMintTicket() = runBlocking {
        val auth = newAuth()

        assertEquals(LoginResult.Ok, auth.login(E2eEnv.user, E2eEnv.password))

        assertTrue(auth.isLoggedIn())
        assertTrue(auth.mintWsTicket().isNotEmpty())
    }

    @Test
    fun wrongPasswordIsBadCredentials() = runBlocking {
        val auth = newAuth()

        assertEquals(LoginResult.BadCredentials, auth.login(E2eEnv.user, "wrong-password"))

        assertFalse(auth.isLoggedIn())
    }
}
