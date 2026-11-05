package net.jkraemer.hermes.core.rpc

import kotlinx.coroutines.CompletableDeferred
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
import net.jkraemer.hermes.core.auth.NotLoggedInException
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
) {
    /** One WebSocket attempt. Callbacks from a link that is no longer [current] are stale and ignored. */
    private inner class Link {
        val ready = CompletableDeferred<Unit>()
        @Volatile var established = false
        @Volatile var webSocket: WebSocket? = null
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RpcFrame.Event>(extraBufferCapacity = 1024)
    val events: SharedFlow<RpcFrame.Event> = _events.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<RpcFrame.ServerRequest>(extraBufferCapacity = 64)
    val serverRequests: SharedFlow<RpcFrame.ServerRequest> = _serverRequests.asSharedFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile private var current: Link? = null
    @Volatile private var closed = false
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * Opens the socket and returns once the gateway is ready for requests. Throws
     * [NotLoggedInException] when there is no session, [IOException] when the server cannot be reached.
     */
    suspend fun connect() {
        closed = false
        try {
            establish()
        } catch (e: NotLoggedInException) {
            _state.value = ConnectionState.Failed("Not logged in", needsLogin = true)
            throw e
        } catch (e: IOException) {
            _state.value = ConnectionState.Failed(e.message ?: "Connection failed", needsLogin = false)
            throw e
        }
    }

    private suspend fun establish() {
        _state.value = ConnectionState.Connecting
        val ticket = ticketProvider()
        val link = Link()
        current = link
        try {
            val url = baseUrl.resolve("api/ws")!!.newBuilder().addQueryParameter("ticket", ticket).build()
            link.webSocket = client.newWebSocket(Request.Builder().url(url).build(), listenerFor(link))
            try {
                withTimeout(READY_TIMEOUT_MS) { link.ready.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timed out waiting for gateway.ready")
            }
            call("client.capabilities", buildJsonObject { put("server_requests", true) })
        } catch (e: Throwable) {
            if (current === link) current = null
            link.webSocket?.cancel()
            throw e
        }
        if (closed) {
            link.webSocket?.cancel()
            throw IOException("Connection closed")
        }
        link.established = true
        _state.value = ConnectionState.Ready
        pingJob = scope.launch { pingForever() }
    }

    private suspend fun pingForever() {
        while (true) {
            delay(pingIntervalMs)
            try {
                call("ping")
            } catch (e: IOException) {
                // No answer means the socket is dead; cancelling surfaces it as a drop.
                current?.webSocket?.cancel()
            } catch (_: RpcException) {
            }
        }
    }

    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 30_000,
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

    suspend fun answer(requestId: String, result: JsonObject) {
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
        closed = true
        reconnectJob?.cancel()
        pingJob?.cancel()
        val link = current
        current = null
        link?.webSocket?.close(NORMAL_CLOSURE, null)
        failPending(IOException("Connection closed"))
        _state.value = ConnectionState.Disconnected
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
        if (current !== link) return
        current = null
        link.ready.completeExceptionally(cause)
        failPending(cause)
        pingJob?.cancel()
        // A link that never became ready is reported to whoever is establishing it.
        if (link.established && !closed) {
            _state.value = ConnectionState.Connecting
            reconnectJob = scope.launch { reconnect() }
        }
    }

    private suspend fun reconnect() {
        var attempt = 0
        while (!closed) {
            sleep(reconnectDelayMs(attempt++))
            if (closed) return
            try {
                establish()
                return
            } catch (_: NotLoggedInException) {
                _state.value = ConnectionState.Failed("Not logged in", needsLogin = true)
                return
            } catch (_: IOException) {
                _state.value = ConnectionState.Connecting
            }
        }
    }
}
