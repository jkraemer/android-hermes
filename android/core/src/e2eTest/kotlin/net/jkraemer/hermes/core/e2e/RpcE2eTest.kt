package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RpcE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var client: E2eClient? = null

    @After
    fun tearDown() {
        client?.close()
    }

    private suspend fun connect(): RpcConnection =
        E2eClient.connect(folder.newFile()).also { client = it }.rpc

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

    @Test
    fun eventsSinceReplaysEventFramesParams() = runBlocking {
        val rpc = connect()
        val created = rpc.call("session.create").jsonObject
        val live = created.getValue("session_id").jsonPrimitive.content
        rpc.call("prompt.submit", buildJsonObject {
            put("session_id", live)
            put("text", "Reply with exactly the word: pong")
        })

        // The turn produces events whether or not a model answers (an error ends it).
        var result = rpc.call("session.events.since", sinceParams(live)).jsonObject
        withTimeout(30_000) {
            while (result.getValue("events").jsonArray.isEmpty()) {
                delay(250)
                result = rpc.call("session.events.since", sinceParams(live)).jsonObject
            }
        }

        val events = result.getValue("events").jsonArray.map { it.jsonObject }
        assertTrue(events.all { it["type"]!!.jsonPrimitive.isString && it["session_id"]!!.jsonPrimitive.content == live })
        assertTrue(events.all { it["seq"]!!.jsonPrimitive.long > 0 })
        assertFalse(result.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(result.getValue("open_requests").jsonArray.isEmpty())
    }

    private fun sinceParams(live: String) = buildJsonObject {
        put("session_id", live)
        put("last_seen", 0)
    }
}
