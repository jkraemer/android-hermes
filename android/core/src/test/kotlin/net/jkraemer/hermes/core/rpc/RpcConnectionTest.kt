package net.jkraemer.hermes.core.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.core.auth.NotLoggedInException
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The server side of one WebSocket connection: sends gateway.ready, records client frames, answers via [onRequest]. */
private class FakeGatewaySocket(
    private val sendReady: Boolean,
    private val onRequest: FakeGatewaySocket.(id: JsonElement, method: String, params: JsonObject) -> Unit,
) : WebSocketListener() {
    val received = LinkedBlockingQueue<JsonObject>()
    val opened = CountDownLatch(1)
    lateinit var socket: WebSocket

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        opened.countDown()
        if (sendReady) sendReady()
    }

    fun sendReady() {
        send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"e1"}}}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = Json.parseToJsonElement(text.trimEnd('\n')).jsonObject
        received.put(frame)
        val method = frame["method"]?.jsonPrimitive?.content ?: return
        onRequest(frame.getValue("id"), method, frame["params"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }

    fun send(text: String) {
        socket.send(text)
    }

    fun reply(id: JsonElement, result: String = "{}") = send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")

    fun next(match: (JsonObject) -> Boolean): JsonObject {
        while (true) {
            val frame = received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("expected frame never arrived")
            if (match(frame)) return frame
        }
    }

    fun nextRequest(method: String) = next { it["method"]?.jsonPrimitive?.content == method }
}

class RpcConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var scopeJob: Job
    private lateinit var scope: CoroutineScope
    private val sockets = CopyOnWriteArrayList<FakeGatewaySocket>()
    private val connections = mutableListOf<RpcConnection>()
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scopeJob = Job()
        scope = CoroutineScope(Dispatchers.Default + scopeJob)
    }

    @After
    fun tearDown() {
        connections.forEach { it.close() }
        scopeJob.cancel()
        server.shutdown()
    }

    /** Queues one WebSocket upgrade; capabilities are answered unless [answerCapabilities] is false. */
    private fun enqueueSocket(
        answerCapabilities: Boolean = true,
        sendReady: Boolean = true,
        onRequest: FakeGatewaySocket.(id: JsonElement, method: String, params: JsonObject) -> Unit = { _, _, _ -> },
    ): FakeGatewaySocket {
        val socket = FakeGatewaySocket(sendReady) { id, method, params ->
            if (method == "client.capabilities" && answerCapabilities) reply(id) else onRequest(id, method, params)
        }
        sockets += socket
        server.enqueue(MockResponse().withWebSocketUpgrade(socket))
        return socket
    }

    private fun newConnection(
        pingIntervalMs: Long = 60_000,
        sleep: suspend (Long) -> Unit = { },
        ticketProvider: suspend () -> String = { "t1" },
        log: HermesLog = HermesLog.None,
    ): RpcConnection {
        val connection = RpcConnection(client, server.url("/"), ticketProvider, scope, pingIntervalMs, sleep, log)
        connections += connection
        return connection
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private suspend fun RpcConnection.awaitState(expected: ConnectionState) {
        state.first { it == expected }
    }

    @Test
    fun connectUsesTicketQuery() {
        enqueueSocket()
        val connection = newConnection()

        await { connection.connect() }

        assertEquals("/api/ws?ticket=t1", server.takeRequest().path)
    }

    @Test
    fun readyAfterGatewayReadyAndCapabilities() {
        val socket = enqueueSocket(answerCapabilities = false) { _, _, _ -> }
        val connection = newConnection()

        runBlocking {
            val connecting = scope.async { connection.connect() }
            val capabilities = socket.nextRequest("client.capabilities")
            assertEquals(buildJsonObject { put("server_requests", true) }, capabilities["params"])
            assertEquals(ConnectionState.Connecting, connection.state.value)

            socket.reply(capabilities.getValue("id"))
            withTimeout(5_000) { connecting.await() }

            assertEquals(ConnectionState.Ready, connection.state.value)
        }
    }

    @Test
    fun callCorrelatesById() {
        val pending = mutableMapOf<String, JsonElement>()
        enqueueSocket { id, method, _ ->
            synchronized(pending) {
                pending[method] = id
                if (pending.size == 2) {
                    reply(pending.getValue("second"), """{"which":"second"}""")
                    reply(pending.getValue("first"), """{"which":"first"}""")
                }
            }
        }
        val connection = newConnection()

        val (first, second) = await {
            connection.connect()
            val first = scope.async(start = CoroutineStart.UNDISPATCHED) { connection.call("first") }
            val second = scope.async { connection.call("second") }
            first.await() to second.await()
        }

        assertEquals("first", first.jsonObject.getValue("which").jsonPrimitive.content)
        assertEquals("second", second.jsonObject.getValue("which").jsonPrimitive.content)
    }

    @Test
    fun callErrorThrowsRpcException() {
        enqueueSocket { id, _, _ ->
            send("""{"jsonrpc":"2.0","id":$id,"error":{"code":4001,"message":"no such session"}}""")
        }
        val connection = newConnection()

        val error = await {
            connection.connect()
            try {
                connection.call("session.resume")
                null
            } catch (e: RpcException) {
                e.error
            }
        }

        assertEquals(RpcError(4001, "no such session"), error)
    }

    @Test
    fun callTimesOutWithIoException() {
        enqueueSocket()
        val connection = newConnection()

        await {
            connection.connect()
            try {
                connection.call("never.answered", timeoutMs = 50)
                fail("expected a timeout")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("never.answered"))
            }
        }
    }

    @Test
    fun eventsAreEmitted() {
        enqueueSocket { id, _, _ ->
            send(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"s1","seq":1}}""" + "\n" +
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","seq":2,"payload":{"text":"hi"}}}""",
            )
            reply(id)
        }
        val connection = newConnection()

        val events = await {
            connection.connect()
            val start = scope.async(start = CoroutineStart.UNDISPATCHED) { connection.events.first { it.type == "message.start" } }
            val delta = scope.async(start = CoroutineStart.UNDISPATCHED) { connection.events.first { it.type == "message.delta" } }
            connection.call("trigger")
            start.await() to delta.await()
        }

        assertEquals(RpcFrame.Event("message.start", "s1", 1, JsonObject(emptyMap())), events.first)
        assertEquals(RpcFrame.Event("message.delta", "s1", 2, buildJsonObject { put("text", "hi") }), events.second)
    }

    @Test
    fun supportedServerRequestIsEmittedAndAnswered() {
        val socket = enqueueSocket { id, _, _ ->
            send("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"command":"ls"}}""")
            reply(id)
        }
        val connection = newConnection()

        val request = await {
            connection.connect()
            val received = scope.async(start = CoroutineStart.UNDISPATCHED) { connection.serverRequests.first() }
            connection.call("trigger")
            received.await().also { connection.answer(it.id, buildJsonObject { put("choice", "once") }) }
        }

        assertEquals(RpcFrame.ServerRequest("srq-1", "approval", buildJsonObject { put("command", "ls") }), request)
        val answer = socket.next { it["id"]?.jsonPrimitive?.content == "srq-1" }
        assertEquals(buildJsonObject { put("choice", "once") }, answer["result"])
    }

    @Test
    fun rejectSendsErrorResponse() {
        val socket = enqueueSocket()
        val connection = newConnection()

        await {
            connection.connect()
            connection.reject("srq-2", -32000, "denied")
        }

        val answer = socket.next { it["id"]?.jsonPrimitive?.content == "srq-2" }
        assertEquals("denied", answer.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun unsupportedServerRequestIsRejected() {
        val socket = enqueueSocket()
        val connection = newConnection()
        await { connection.connect() }

        socket.send("""{"jsonrpc":"2.0","id":"srq-1","method":"sudo","params":{}}""")

        val answer = socket.next { it["id"]?.jsonPrimitive?.content == "srq-1" }
        val error = answer.getValue("error").jsonObject
        assertEquals("-32601", error.getValue("code").jsonPrimitive.content)
        assertEquals("not supported", error.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun pingsPeriodically() {
        val pings = AtomicInteger()
        enqueueSocket { id, method, _ ->
            if (method == "ping") pings.incrementAndGet()
            reply(id)
        }
        val connection = newConnection(pingIntervalMs = 100)

        await {
            connection.connect()
            while (pings.get() < 3) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun reconnectsWithFreshTicketAfterDrop() {
        val first = enqueueSocket()
        enqueueSocket()
        val tickets = mutableListOf("t1", "t2")
        val delays = CopyOnWriteArrayList<Long>()
        val connection = newConnection(sleep = { delays += it }, ticketProvider = { tickets.removeAt(0) })

        await {
            connection.connect()
            first.socket.close(1001, "going away")
            connection.awaitState(ConnectionState.Connecting)
            connection.awaitState(ConnectionState.Ready)
        }

        assertEquals("/api/ws?ticket=t1", server.takeRequest().path)
        assertEquals("/api/ws?ticket=t2", server.takeRequest().path)
        assertEquals(listOf(1_000L), delays.toList())
    }

    @Test
    fun logsStateChangesAcrossADropWithoutTheTicket() {
        val first = enqueueSocket()
        enqueueSocket()
        val tickets = mutableListOf("ticket-s3cr3t-1", "ticket-s3cr3t-2")
        val lines = CopyOnWriteArrayList<String>()
        val connection = newConnection(ticketProvider = { tickets.removeAt(0) }, log = { lines += it })

        await {
            connection.connect()
            first.socket.close(1001, "going away")
            while (lines.size < 4) kotlinx.coroutines.delay(10)
            connection.close()
        }

        assertEquals(
            listOf("connection: Connecting", "connection: Ready", "connection: Connecting", "connection: Ready", "connection: Disconnected"),
            lines.toList(),
        )
        assertTrue(lines.none { "s3cr3t" in it })
    }

    @Test
    fun logsWhyAConnectFailed() {
        server.enqueue(MockResponse().setResponseCode(503))
        val lines = CopyOnWriteArrayList<String>()
        val connection = newConnection(log = { lines += it })

        await { runCatching { connection.connect() } }

        assertEquals("connection: Connecting", lines[0])
        assertTrue(lines.toString(), lines[1].startsWith("connection: Failed: ") && "503" in lines[1])
        assertEquals(2, lines.size)
    }

    @Test
    fun aConnectFailureNeverLogsTheTicketNorShowsItInTheState() {
        // A failure message that quotes the upgrade URL carries the ticket in its query.
        val leaking = OkHttpClient.Builder()
            .addInterceptor { chain -> throw IOException("failed to reach ${chain.request().url}") }
            .build()
        val lines = CopyOnWriteArrayList<String>()
        val connection = RpcConnection(leaking, server.url("/"), { "ticket-s3cr3t" }, scope, 60_000, { }, { lines += it })
        connections += connection

        await { runCatching { connection.connect() } }

        assertEquals("connection: Connecting", lines[0])
        assertTrue(lines.toString(), lines[1].startsWith("connection: Failed: java.io.IOException: failed to reach "))
        assertTrue(lines.toString(), "ticket=…" in lines[1])
        assertTrue(lines.toString(), lines.none { "s3cr3t" in it })
        assertTrue((connection.state.value as ConnectionState.Failed).reason.let { "s3cr3t" !in it && "ticket=…" in it })
    }

    @Test
    fun aConnectFailureLogsTheTicketOnlyAsAnEllipsisEvenWithoutTheQueryKey() {
        val lines = CopyOnWriteArrayList<String>()
        val connection = RpcConnection(
            OkHttpClient.Builder().addInterceptor { throw IOException("rejected ticket-s3cr3t here") }.build(),
            server.url("/"), { "ticket-s3cr3t" }, scope, 60_000, { }, { lines += it },
        )
        connections += connection

        await { runCatching { connection.connect() } }

        assertTrue(lines.toString(), lines.none { "s3cr3t" in it })
    }

    @Test
    fun logsALostLoginAsNeedingLogin() {
        val lines = CopyOnWriteArrayList<String>()
        val connection = newConnection(ticketProvider = { throw NotLoggedInException() }, log = { lines += it })

        await { runCatching { connection.connect() } }

        assertEquals(listOf("connection: Connecting", "connection: Failed (login needed): Not logged in"), lines.toList())
    }

    @Test
    fun retriesWithBackoffWhileTheServerRefusesTheUpgrade() {
        val first = enqueueSocket()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        enqueueSocket()
        val delays = CopyOnWriteArrayList<Long>()
        val connection = newConnection(sleep = { delays += it })

        await {
            connection.connect()
            first.socket.close(1001, "going away")
            // Wait for the reconnect that finally succeeds: Ready after at least three sleeps.
            while (delays.size < 3) kotlinx.coroutines.delay(10)
            connection.awaitState(ConnectionState.Ready)
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays.toList())
    }

    @Test
    fun reconnectDelayDoublesUpToThirtySeconds() {
        val delays = (0..7).map { reconnectDelayMs(it) }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun notLoggedInStopsRetrying() {
        var calls = 0
        val connection = newConnection(ticketProvider = {
            calls++
            throw NotLoggedInException()
        })

        await {
            try {
                connection.connect()
                fail("expected NotLoggedInException")
            } catch (_: NotLoggedInException) {
            }
        }

        assertEquals(ConnectionState.Failed("Not logged in", needsLogin = true), connection.state.value)
        assertEquals(1, calls)
    }

    @Test
    fun notLoggedInDuringReconnectStopsRetrying() {
        val first = enqueueSocket()
        var calls = 0
        val connection = newConnection(ticketProvider = {
            if (++calls == 1) "t1" else throw NotLoggedInException()
        })

        await {
            connection.connect()
            first.socket.close(1001, "going away")
            connection.state.first { it is ConnectionState.Failed }
        }

        assertEquals(ConnectionState.Failed("Not logged in", needsLogin = true), connection.state.value)
        assertEquals(2, calls)
    }

    @Test
    fun pendingCallFailsOnDrop() {
        val socket = enqueueSocket()
        val connection = newConnection(sleep = { awaitCancellation() })

        val failure = await {
            connection.connect()
            val call = scope.async { runCatching { connection.call("hang") }.exceptionOrNull() }
            socket.nextRequest("hang")
            socket.socket.close(1001, "going away")
            call.await()
        }

        assertTrue("was $failure", failure is IOException)
    }

    @Test
    fun callWithoutConnectionFails() {
        val connection = newConnection()

        val failure = await { runCatching { connection.call("ping") }.exceptionOrNull() }

        assertTrue("was $failure", failure is IOException)
    }

    @Test
    fun closeStopsAllWork() {
        val socket = enqueueSocket()
        val connection = newConnection(pingIntervalMs = 100)
        await { connection.connect() }

        connection.close()

        assertEquals(ConnectionState.Disconnected, connection.state.value)
        assertTrue(scopeJob.children.none { it.isActive })
        assertTrue(scopeJob.isActive)
        // Give a wrongly surviving ping or reconnect loop time to show itself.
        Thread.sleep(100)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun capabilitiesWaitForGatewayReady() {
        val socket = enqueueSocket(sendReady = false)
        val connection = newConnection()

        runBlocking {
            val connecting = scope.async { connection.connect() }
            socket.opened.await(5, TimeUnit.SECONDS)
            // Give a client that ignores the missing gateway.ready time to misbehave.
            Thread.sleep(150)
            assertTrue(socket.received.isEmpty())
            assertEquals(ConnectionState.Connecting, connection.state.value)

            socket.sendReady()
            withTimeout(5_000) { connecting.await() }

            assertEquals(ConnectionState.Ready, connection.state.value)
        }
    }

    @Test
    fun reconnectSurvivesNonIoTicketFailure() {
        val first = enqueueSocket()
        enqueueSocket()
        val calls = AtomicInteger()
        val connection = newConnection(ticketProvider = {
            when (calls.incrementAndGet()) {
                1 -> "t1"
                2 -> throw IllegalStateException("ws-ticket request failed with 503")
                else -> "t3"
            }
        })

        await {
            connection.connect()
            first.socket.close(1001, "going away")
            connection.awaitState(ConnectionState.Connecting)
            connection.awaitState(ConnectionState.Ready)
        }

        assertEquals(3, calls.get())
    }

    @Test
    fun connectWithUnexpectedTicketFailureEndsFailed() {
        val connection = newConnection(ticketProvider = { throw IllegalStateException("boom") })

        val failure = await { runCatching { connection.connect() }.exceptionOrNull() }

        assertTrue("was $failure", failure is IllegalStateException)
        assertEquals(ConnectionState.Failed("boom", needsLogin = false), connection.state.value)
    }

    @Test
    fun failedInitialConnectIsNotRetriedInTheBackground() {
        server.enqueue(MockResponse().setResponseCode(503))
        val delays = CopyOnWriteArrayList<Long>()
        val connection = newConnection(sleep = { delays += it })

        val failure = await { runCatching { connection.connect() }.exceptionOrNull() }
        Thread.sleep(100)

        assertTrue("was $failure", failure is IOException)
        val state = connection.state.value
        assertTrue("was $state", state is ConnectionState.Failed && !state.needsLogin)
        assertTrue(delays.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun connectCanBeRetriedAfterFailure() {
        server.enqueue(MockResponse().setResponseCode(503))
        enqueueSocket()
        val connection = newConnection()

        await {
            assertTrue(runCatching { connection.connect() }.isFailure)
            connection.connect()
        }

        assertEquals(ConnectionState.Ready, connection.state.value)
    }

    @Test
    fun dropRightAfterCapabilitiesIsNotLeftReady() {
        enqueueSocket(answerCapabilities = false) { id, _, _ ->
            reply(id)
            socket.close(1001, "going away")
        }
        val connection = newConnection(sleep = { awaitCancellation() })

        await {
            if (runCatching { connection.connect() }.isSuccess) {
                connection.awaitState(ConnectionState.Connecting)
            } else {
                assertTrue(connection.state.value is ConnectionState.Failed)
            }
        }
    }

    @Test
    fun closeWhileAwaitingGatewayReadyReturnsPromptly() {
        val pings = AtomicInteger()
        val socket = enqueueSocket(sendReady = false) { id, method, _ ->
            if (method == "ping") pings.incrementAndGet()
            reply(id)
        }
        val connection = newConnection(pingIntervalMs = 20)

        val failure = await {
            val connecting = scope.async { runCatching { connection.connect() }.exceptionOrNull() }
            socket.opened.await(5, TimeUnit.SECONDS)
            connection.close()
            connecting.await()
        }
        Thread.sleep(100)

        assertTrue("was $failure", failure is IOException)
        assertEquals(ConnectionState.Disconnected, connection.state.value)
        assertEquals(0, pings.get())
    }

    @Test
    fun pingFailureTriggersReconnect() {
        enqueueSocket { id, method, _ -> if (method != "ping") reply(id) }
        enqueueSocket()
        val connection = newConnection(pingIntervalMs = 100)

        await {
            connection.connect()
            connection.awaitState(ConnectionState.Connecting)
            connection.awaitState(ConnectionState.Ready)
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun connectWhileReadyIsANoOp() {
        val pings = AtomicInteger()
        enqueueSocket { id, method, _ ->
            if (method == "ping") pings.incrementAndGet()
            reply(id)
        }
        val connection = newConnection(pingIntervalMs = 100)

        await {
            connection.connect()
            connection.connect()
        }
        connection.close()
        val afterClose = pings.get()
        Thread.sleep(250)

        assertEquals(1, server.requestCount)
        assertEquals(afterClose, pings.get())
    }

    @Test
    fun connectDuringReconnectJoinsTheLoop() {
        val first = enqueueSocket()
        enqueueSocket()
        val gate = CompletableDeferred<Unit>()
        val connection = newConnection(sleep = { gate.await() })

        runBlocking {
            withTimeout(5_000) {
                connection.connect()
                first.socket.close(1001, "going away")
                connection.awaitState(ConnectionState.Connecting)
                val joined = scope.async { connection.connect() }
                Thread.sleep(100)
                assertEquals(1, server.requestCount)

                gate.complete(Unit)
                joined.await()
            }
        }

        assertEquals(ConnectionState.Ready, connection.state.value)
        assertEquals(2, server.requestCount)
    }
}
