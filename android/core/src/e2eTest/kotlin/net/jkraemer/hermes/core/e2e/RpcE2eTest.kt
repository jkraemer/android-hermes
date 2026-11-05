package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jkraemer.hermes.core.auth.FileCookieStore
import net.jkraemer.hermes.core.auth.HermesAuth
import net.jkraemer.hermes.core.auth.LoginResult
import net.jkraemer.hermes.core.auth.PersistentCookieJar
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcConnection
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpcE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var connection: RpcConnection? = null

    @After
    fun tearDown() {
        connection?.close()
        scope.cancel()
    }

    private suspend fun connect(): RpcConnection {
        val jar = PersistentCookieJar(FileCookieStore(folder.newFile()))
        val client = OkHttpClient.Builder().cookieJar(jar).build()
        val baseUrl = E2eEnv.url.toHttpUrl()
        val auth = HermesAuth(baseUrl, client)
        assertEquals(LoginResult.Ok, auth.login(E2eEnv.user, E2eEnv.password))
        return RpcConnection(client, baseUrl, auth::mintWsTicket, scope).also {
            connection = it
            withTimeout(30_000) { it.connect() }
        }
    }

    @Test
    fun connectAndPing() = runBlocking {
        val rpc = connect()

        assertEquals(ConnectionState.Ready, rpc.state.value)
        rpc.call("ping")
        Unit
    }

    @Test
    fun sessionCreateReturnsIds() = runBlocking {
        val rpc = connect()

        val result = rpc.call("session.create").jsonObject

        assertTrue(result.getValue("session_id").jsonPrimitive.content.isNotEmpty())
        assertTrue(result.getValue("stored_session_id").jsonPrimitive.content.isNotEmpty())
    }
}
