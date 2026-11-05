package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import net.jkraemer.hermes.core.auth.FileCookieStore
import net.jkraemer.hermes.core.auth.HermesAuth
import net.jkraemer.hermes.core.auth.LoginResult
import net.jkraemer.hermes.core.auth.PersistentCookieJar
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.rpc.RpcConnection
import net.jkraemer.hermes.core.sessions.SessionsApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import java.io.File

/** A logged-in client of the e2e Hermes: REST, the RPC connection and chat controllers on it. */
class E2eClient private constructor(cookieFile: File) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val httpClient = OkHttpClient.Builder().cookieJar(PersistentCookieJar(FileCookieStore(cookieFile))).build()
    private val baseUrl = E2eEnv.url.toHttpUrl()
    private val auth = HermesAuth(baseUrl, httpClient)
    private val controllers = mutableListOf<ChatController>()

    val rpc = RpcConnection(httpClient, baseUrl, auth::mintWsTicket, scope)
    val sessions = SessionsApi(baseUrl, httpClient)

    /** Created before any session is opened, so no event or request is missed. */
    fun newChat(): ChatController = ChatController(rpc, scope).also { controllers += it }

    override fun close() {
        controllers.forEach { it.close() }
        rpc.close()
        scope.cancel()
    }

    companion object {
        // Hermes rate-limits password logins, so only the first client of a test run logs in;
        // later ones start from a copy of its cookies.
        private var loggedInCookies: File? = null

        suspend fun connect(cookieFile: File): E2eClient {
            val template = loggedInCookies
            if (template != null) template.copyTo(cookieFile, overwrite = true)
            val client = E2eClient(cookieFile)
            try {
                if (template == null) {
                    assertEquals(LoginResult.Ok, client.auth.login(E2eEnv.user, E2eEnv.password))
                    loggedInCookies = File.createTempFile("e2e-cookies", null).also {
                        cookieFile.copyTo(it, overwrite = true)
                        it.deleteOnExit()
                    }
                }
                withTimeout(30_000) { client.rpc.connect() }
            } catch (e: Throwable) {
                client.close()
                throw e
            }
            return client
        }
    }
}
