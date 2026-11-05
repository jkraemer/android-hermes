package net.jkraemer.hermes.core.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RpcFrameTest {
    @Test
    fun parsesResponse() {
        val frame = parseFrame("""{"jsonrpc":"2.0","id":7,"result":{"ok":true}}""")

        assertEquals(RpcFrame.Response(7, buildJsonObject { put("ok", true) }, null), frame)
    }

    @Test
    fun parsesErrorResponse() {
        val frame = parseFrame("""{"jsonrpc":"2.0","id":8,"error":{"code":-32601,"message":"nope"}}""")

        assertEquals(RpcFrame.Response(8, null, RpcError(-32601, "nope")), frame)
    }

    @Test
    fun parsesEventWithSeq() {
        val frame = parseFrame(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","seq":12,"payload":{"text":"hi"}}}""",
        )

        assertEquals(
            RpcFrame.Event("message.delta", "s1", 12, buildJsonObject { put("text", "hi") }),
            frame,
        )
    }

    @Test
    fun eventWithoutPayloadParses() {
        val frame = parseFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}""")

        assertEquals(RpcFrame.Event("gateway.ready", null, null, JsonObject(emptyMap())), frame)
    }

    @Test
    fun parsesServerRequest() {
        val frame = parseFrame(
            """{"jsonrpc":"2.0","id":"srq-0123456789ab","method":"approval","params":{"command":"ls"}}""",
        )

        assertEquals(
            RpcFrame.ServerRequest("srq-0123456789ab", "approval", JsonObject(mapOf("command" to JsonPrimitive("ls")))),
            frame,
        )
    }

    @Test
    fun unknownFramesAreIgnored() {
        assertNull(parseFrame("""{"foo":1}"""))
        assertNull(parseFrame("not json"))
        assertNull(parseFrame("""{"jsonrpc":"2.0","method":"event","params":{}}"""))
        assertNull(parseFrame("[1,2]"))
        assertNull(parseFrame(""))
    }
}
