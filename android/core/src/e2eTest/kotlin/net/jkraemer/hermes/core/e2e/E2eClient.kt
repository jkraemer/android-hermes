package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.jkraemer.hermes.core.auth.FileCookieStore
import net.jkraemer.hermes.core.auth.HermesAuth
import net.jkraemer.hermes.core.auth.LoginResult
import net.jkraemer.hermes.core.auth.PersistentCookieJar
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.cron.CronApi
import net.jkraemer.hermes.core.push.PushApi
import net.jkraemer.hermes.core.rpc.RpcConnection
import net.jkraemer.hermes.core.sessions.SessionsApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory

/** A logged-in client of the e2e Hermes: REST, the RPC connection and chat controllers on it. */
class E2eClient private constructor(cookieFile: File) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val httpClient = OkHttpClient.Builder()
        .cookieJar(PersistentCookieJar(FileCookieStore(cookieFile)))
        // Remembers the TCP sockets so a test can cut the connection the way a network loss would.
        .socketFactory(object : SocketFactory() {
            private val default = getDefault()
            override fun createSocket(): Socket = default.createSocket().also { sockets += it }
            override fun createSocket(host: String, port: Int): Socket = default.createSocket(host, port).also { sockets += it }
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                default.createSocket(host, port, localHost, localPort).also { sockets += it }
            override fun createSocket(host: InetAddress, port: Int): Socket = default.createSocket(host, port).also { sockets += it }
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                default.createSocket(address, port, localAddress, localPort).also { sockets += it }
        })
        .build()
    private val baseUrl = E2eEnv.url.toHttpUrl()
    private val auth = HermesAuth(baseUrl, httpClient)
    private val controllers = mutableListOf<ChatController>()

    val rpc = RpcConnection(httpClient, baseUrl, auth::mintWsTicket, scope)
    val sessions = SessionsApi(baseUrl, httpClient)
    val cron = CronApi(baseUrl, httpClient)
    val push = PushApi(baseUrl, httpClient)

    /**
     * A REST call the app itself does not make, e.g. to set up a cron job; it may take as long as a model turn.
     * [path] is relative to the server and may carry a query.
     */
    suspend fun rest(method: String, path: String, json: String? = null): String = withContext(Dispatchers.IO) {
        val body = json?.toRequestBody("application/json".toMediaType()) ?: if (method == "POST") ByteArray(0).toRequestBody() else null
        val request = Request.Builder().url(baseUrl.resolve(path)!!).method(method, body).build()
        httpClient.newBuilder().readTimeout(Duration.ofMinutes(5)).build().newCall(request).execute().use { response ->
            val text = response.body!!.string()
            check(response.isSuccessful) { "$method $path failed with ${response.code}: $text" }
            text
        }
    }

    /** Created before any session is opened, so no event or request is missed. */
    fun newChat(): ChatController = ChatController(rpc, scope).also { controllers += it }

    /** Closes the TCP sockets without a WebSocket close, so [rpc] sees an unexpected drop. */
    fun dropGatewayConnection() {
        check(sockets.isNotEmpty()) { "no connection to drop" }
        sockets.forEach { it.close() }
    }

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
