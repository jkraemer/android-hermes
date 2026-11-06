package net.jkraemer.hermes.core.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.core.auth.NotLoggedInException
import net.jkraemer.hermes.core.chat.RpcPeer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Ready : ConnectionState
    data class Failed(val reason: String, val needsLogin: Boolean) : ConnectionState
}

private const val FIRST_RECONNECT_DELAY_MS = 1_000L
private const val MAX_RECONNECT_DELAY_MS = 30_000L
private const val READY_TIMEOUT_MS = 30_000L
private const val NORMAL_CLOSURE = 1000
private const val METHOD_NOT_FOUND = -32601
private val TICKET_QUERY = Regex("""ticket=[^&\s"']*""")

/** Delay before reconnect attempt number [attempt] (0-based): 1 s, doubling, capped at 30 s. */
internal fun reconnectDelayMs(attempt: Int): Long =
    minOf(FIRST_RECONNECT_DELAY_MS shl minOf(attempt, 10), MAX_RECONNECT_DELAY_MS)

/**
 * JSON-RPC client for the Hermes gateway WebSocket (`/api/ws`). Frames are newline-delimited JSON.
 * Reconnects with backoff after an unexpected close, minting a fresh ticket each time. Work is
 * launched in [scope] and stopped by [close]; [sleep] and [pingIntervalMs] exist so tests need no
 * real waiting.
 */
class RpcConnection(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val ticketProvider: suspend () -> String,
    private val scope: CoroutineScope,
    private val pingIntervalMs: Long = 15_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: HermesLog = HermesLog.None,
) : RpcPeer {
    /** One WebSocket attempt. Callbacks from a link that is no longer [current] are stale and ignored. */
    private inner class Link {
        val ready = CompletableDeferred<Unit>()
        @Volatile var established = false
        @Volatile var webSocket: WebSocket? = null
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RpcFrame.Event>(extraBufferCapacity = 1024)
    override val events: SharedFlow<RpcFrame.Event> = _events.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<RpcFrame.ServerRequest>(extraBufferCapacity = 64)
    override val serverRequests: SharedFlow<RpcFrame.ServerRequest> = _serverRequests.asSharedFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    // Link transitions (current, closed, state changes, ping/attempt jobs) happen under [lock]
    // because OkHttp callbacks, the caller and the reconnect coroutine all drive them.
    private val lock = Any()
    @Volatile private var current: Link? = null
    @Volatile private var closed = false
    private var pingJob: Job? = null
    private var attempt: Job? = null
    private var inflight: CompletableDeferred<Unit>? = null

    /** The ticket of the latest attempt, known so it can be kept out of failure messages. */
    @Volatile private var ticketInUse: String? = null

    /**
     * Opens the socket and returns once the gateway is ready for requests. Does nothing when
     * already ready; joins the attempt or reconnect loop that is already running. Throws
     * [NotLoggedInException] when there is no session; any other failure leaves the state
     * `Failed(needsLogin = false)` and is rethrown. A failed initial connect is not retried
     * automatically, the caller retries by calling this again.
     */
    suspend fun connect() {
        val running = synchronized(lock) {
            if (_state.value == ConnectionState.Ready) return
            inflight?.takeIf { it.isActive } ?: run {
                closed = false
                launchAttempt { connectOnce() }
            }
        }
        running.await()
        val state = _state.value
        if (state is ConnectionState.Failed) {
            if (state.needsLogin) throw NotLoggedInException()
            throw IOException(state.reason)
        }
    }

    /** Must be called under [lock]. */
    private fun launchAttempt(block: suspend () -> Unit): CompletableDeferred<Unit> {
        val outcome = CompletableDeferred<Unit>()
        inflight = outcome
        attempt = scope.launch {
            try {
                block()
                outcome.complete(Unit)
            } catch (e: CancellationException) {
                outcome.completeExceptionally(IOException("Connection closed"))
                throw e
            } catch (e: Throwable) {
                outcome.completeExceptionally(e)
            }
        }
        return outcome
    }

    private suspend fun connectOnce() {
        try {
            establish()
        } catch (e: NotLoggedInException) {
            transition(ConnectionState.Failed("Not logged in", needsLogin = true))
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = redactTicket(e.message ?: "Connection failed")
            transition(ConnectionState.Failed(reason, needsLogin = false), logAs = "${e::class.qualifiedName}: $reason")
            throw e
        }
    }

    /**
     * The ticket is a credential and travels in the upgrade URL's query, so a failure message that
     * quotes the URL, or the ticket itself, must not reach the state or the log.
     */
    private fun redactTicket(message: String): String {
        val withoutQuery = message.replace(TICKET_QUERY, "ticket=…")
        return ticketInUse?.takeIf { it.isNotEmpty() }?.let { withoutQuery.replace(it, "…") } ?: withoutQuery
    }

    /** A closed connection stays [ConnectionState.Disconnected]. [logAs] replaces the reason in the log line. */
    private fun transition(newState: ConnectionState, logAs: String? = null) {
        synchronized(lock) { if (!closed) setState(newState, logAs) }
    }

    /** Must be called under [lock]. Logs changes only. */
    private fun setState(newState: ConnectionState, logAs: String? = null) {
        if (_state.value == newState) return
        _state.value = newState
        log.log("connection: " + when (newState) {
            ConnectionState.Disconnected -> "Disconnected"
            ConnectionState.Connecting -> "Connecting"
            ConnectionState.Ready -> "Ready"
            is ConnectionState.Failed ->
                if (newState.needsLogin) "Failed (login needed): ${newState.reason}" else "Failed: ${logAs ?: newState.reason}"
        })
    }

    private suspend fun establish() {
        transition(ConnectionState.Connecting)
        val ticket = ticketProvider()
        ticketInUse = ticket
        val link = Link()
        synchronized(lock) {
            if (closed) throw IOException("Connection closed")
            current = link
        }
        try {
            val url = baseUrl.resolve("api/ws")!!.newBuilder().addQueryParameter("ticket", ticket).build()
            link.webSocket = client.newWebSocket(Request.Builder().url(url).build(), listenerFor(link))
            try {
                withTimeout(READY_TIMEOUT_MS) { link.ready.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timed out waiting for gateway.ready")
            }
            call("client.capabilities", buildJsonObject { put("server_requests", true) })
            synchronized(lock) {
                // The socket may have dropped or the connection been closed since the response.
                if (current !== link || closed) throw IOException("Connection closed")
                link.established = true
                setState(ConnectionState.Ready)
                pingJob = scope.launch { pingForever(link) }
            }
        } catch (e: Throwable) {
            synchronized(lock) { if (current === link) current = null }
            link.webSocket?.cancel()
            throw e
        }
    }

    private suspend fun pingForever(link: Link) {
        while (true) {
            delay(pingIntervalMs)
            try {
                call("ping", timeoutMs = pingIntervalMs)
            } catch (e: IOException) {
                // No answer means the socket is dead.
                dropped(link, e)
                link.webSocket?.cancel()
                return
            } catch (_: RpcException) {
            }
        }
    }

    override suspend fun call(
        method: String,
        params: JsonObject,
        timeoutMs: Long,
    ): JsonElement {
        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            return withTimeout(timeoutMs) { response.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out waiting for the response to $method")
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun answer(requestId: String, result: JsonObject) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("result", result)
        })
    }

    suspend fun reject(requestId: String, code: Int, message: String) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        })
    }

    fun close() {
        val cause = IOException("Connection closed")
        synchronized(lock) {
            closed = true
            attempt?.cancel()
            pingJob?.cancel()
            val link = current
            current = null
            link?.ready?.completeExceptionally(cause)
            link?.webSocket?.close(NORMAL_CLOSURE, null)
            failPending(cause)
            setState(ConnectionState.Disconnected)
        }
    }

    private fun send(frame: JsonObject) {
        val webSocket = current?.webSocket ?: throw IOException("Not connected")
        if (!webSocket.send(frame.toString() + "\n")) throw IOException("Connection closed")
    }

    private fun failPending(cause: IOException) {
        pending.values.forEach { it.completeExceptionally(cause) }
    }

    private fun listenerFor(link: Link) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (current !== link) return
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                parseFrame(line)?.let { dispatch(link, it) }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            dropped(link, IOException("Connection closed by the server ($code $reason)"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(link, IOException("Connection closed ($code $reason)"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dropped(link, if (t is IOException) t else IOException(t))
        }
    }

    private fun dispatch(link: Link, frame: RpcFrame) {
        when (frame) {
            is RpcFrame.Response -> {
                val waiting = pending.remove(frame.id) ?: return
                if (frame.error != null) waiting.completeExceptionally(RpcException(frame.error))
                else waiting.complete(frame.result ?: JsonNull)
            }
            is RpcFrame.Event -> {
                if (frame.type == "gateway.ready") link.ready.complete(Unit)
                _events.tryEmit(frame)
            }
            is RpcFrame.ServerRequest ->
                if (frame.method == "approval" || frame.method == "clarify") {
                    _serverRequests.tryEmit(frame)
                } else {
                    link.webSocket?.send(rejection(frame.id).toString() + "\n")
                }
        }
    }

    private fun rejection(requestId: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        put("error", buildJsonObject {
            put("code", METHOD_NOT_FOUND)
            put("message", "not supported")
        })
    }

    private fun dropped(link: Link, cause: IOException) {
        synchronized(lock) {
            if (current !== link) return
            current = null
            link.ready.completeExceptionally(cause)
            failPending(cause)
            pingJob?.cancel()
            // A link that never became ready is reported to whoever is establishing it.
            if (link.established && !closed) {
                setState(ConnectionState.Connecting)
                launchAttempt { reconnect() }
            }
        }
    }

    private suspend fun reconnect() {
        var attempts = 0
        while (!closed) {
            sleep(reconnectDelayMs(attempts++))
            if (closed) return
            try {
                establish()
                return
            } catch (_: NotLoggedInException) {
                transition(ConnectionState.Failed("Not logged in", needsLogin = true))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                transition(ConnectionState.Connecting)
            }
        }
    }
}
