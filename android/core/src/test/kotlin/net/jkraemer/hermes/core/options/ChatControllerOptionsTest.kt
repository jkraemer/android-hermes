package net.jkraemer.hermes.core.options

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.chat.FakePeer
import net.jkraemer.hermes.core.chat.obj
import net.jkraemer.hermes.core.rpc.ConnectionState
import net.jkraemer.hermes.core.rpc.RpcError
import net.jkraemer.hermes.core.rpc.RpcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

private val NOW = Instant.parse("2026-01-01T00:00:00Z")

private const val INFO = """{"model":"a","provider":"p","reasoning_effort":"","fast":false,"yolo":false}"""

private fun resume(info: String = INFO) =
    """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":false,"open_requests":[],"info":$info}"""

private val BASE = SessionOptions("a", "p", "", fast = false, yolo = false)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerOptionsTest {
    /** A chat resumed on [info]; [configSet] answers every `config.set` call. */
    private fun test(
        info: String = INFO,
        configSet: String = """{"key":"x","value":"x"}""",
        block: suspend (FakePeer, ChatController) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val peer = FakePeer()
        peer.on("session.resume", resume(info))
        peer.on("config.set", configSet)
        val chat = ChatController(peer, backgroundScope) { NOW }
        chat.open("stored1")
        block(peer, chat)
    }

    private fun FakePeer.configCalls() = calls.filter { it.first == "config.set" }.map { it.second }

    private fun options(chat: ChatController) = chat.state.value.options

    @Test
    fun optionsFollowSessionInfo() = test { peer, chat ->
        assertEquals(BASE, options(chat))

        peer.event("session.info", """{"model":"b","provider":"p","reasoning_effort":"low","fast":true,"yolo":false}""", seq = 1)
        assertEquals(SessionOptions("b", "p", "low", fast = true, yolo = false), options(chat))

        peer.event("session.info", """{"model":"other"}""", seq = 2, session = "live2")
        assertEquals("b", options(chat)?.model)
    }

    @Test
    fun lazyInfoLeavesOptionsAsTheyWere() = test { peer, chat ->
        peer.event("session.info", """{"lazy":true}""", seq = 1)

        assertEquals(BASE, options(chat))
    }

    @Test
    fun switchModelIsSessionScoped() = test(configSet = """{"key":"model","value":"gpt-5","scope":"session"}""") { peer, chat ->
        val switch = chat.switchModel(ModelRef("openrouter", "gpt-5"))

        assertEquals(ModelSwitch.Applied(null, "gpt-5", "session"), switch)
        assertEquals(
            listOf(obj("""{"key":"model","session_id":"live1","value":"gpt-5 --provider openrouter --session"}""")),
            peer.configCalls(),
        )
        assertEquals(BASE.copy(model = "gpt-5", provider = "openrouter"), options(chat))
    }

    @Test
    fun switchModelReportsTheWarning() = test(configSet = """{"value":"gpt-5","warning":"no pricing","scope":"session"}""") { _, chat ->
        assertEquals(ModelSwitch.Applied("no pricing", "gpt-5", "session"), chat.switchModel(ModelRef("openrouter", "gpt-5")))
    }

    @Test
    fun confirmationRequiredAppliesNothing() = test(configSet = """{"confirm_required":true,"confirm_message":"costly","value":"gpt-5"}""") { peer, chat ->
        val switch = chat.switchModel(ModelRef("openrouter", "gpt-5"))

        assertEquals(ModelSwitch.NeedsConfirmation("costly"), switch)
        assertEquals(BASE, options(chat))

        chat.switchModel(ModelRef("openrouter", "gpt-5"), confirmed = true)
        assertEquals(
            listOf(
                obj("""{"key":"model","session_id":"live1","value":"gpt-5 --provider openrouter --session"}"""),
                obj("""{"key":"model","session_id":"live1","value":"gpt-5 --provider openrouter --session","confirm_expensive_model":true}"""),
            ),
            peer.configCalls(),
        )
    }

    @Test
    fun deferredSwitchIsReported() = test(configSet = """{"deferred":true,"value":"gpt-5"}""") { _, chat ->
        val switch = chat.switchModel(ModelRef("openrouter", "gpt-5"))

        assertEquals(ModelSwitch.Deferred(null, "gpt-5"), switch)
        assertEquals("gpt-5", options(chat)?.model)
    }

    @Test
    fun reasoningFastYoloParams() = test { peer, chat ->
        // Each answer differs from what was asked wherever it can, so the options must come from the answer.
        peer.on("config.set", """{"key":"reasoning","value":"medium"}""")
        chat.setReasoning("high")
        peer.on("config.set", """{"key":"fast","value":"ultrafast"}""")
        chat.setFast(true)
        peer.on("config.set", """{"key":"yolo","value":"1","scope":"session"}""")
        chat.setYolo(true)

        assertEquals(
            listOf(
                obj("""{"key":"reasoning","session_id":"live1","value":"high"}"""),
                obj("""{"key":"fast","session_id":"live1","value":"fast"}"""),
                obj("""{"key":"yolo","session_id":"live1","value":"1"}"""),
            ),
            peer.configCalls(),
        )
        assertEquals(BASE.copy(reasoning = "medium", fast = true, yolo = true), options(chat))

        peer.on("config.set", """{"key":"fast","value":"normal"}""")
        chat.setFast(false)
        peer.on("config.set", """{"key":"yolo","value":"0","scope":"session"}""")
        chat.setYolo(false)
        assertEquals(
            listOf(
                obj("""{"key":"fast","session_id":"live1","value":"normal"}"""),
                obj("""{"key":"yolo","session_id":"live1","value":"0"}"""),
            ),
            peer.configCalls().takeLast(2),
        )
        assertEquals(BASE.copy(reasoning = "medium"), options(chat))
    }

    @Test
    fun refusedOptionChangeLeavesOptionsUnchanged() = test { peer, chat ->
        peer.on("config.set") { throw RpcException(RpcError(4001, "stale session")) }

        try {
            chat.setYolo(true)
            fail("expected RpcException")
        } catch (e: RpcException) {
            assertEquals(4001, e.error.code)
        }
        assertEquals(BASE, options(chat))
    }

    @Test
    fun displayWordsAreRejected() = test { peer, chat ->
        try {
            chat.setReasoning("show")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(peer.configCalls().isEmpty())
    }

    @Test
    fun typedModelGoesVerbatim() = test(configSet = """{"value":"gpt-5","scope":"global"}""") { peer, chat ->
        val switch = chat.runModelCommand("gpt-5 --global")

        assertEquals(ModelSwitch.Applied(null, "gpt-5", "global"), switch)
        assertEquals(listOf(obj("""{"key":"model","session_id":"live1","value":"gpt-5 --global"}""")), peer.configCalls())
        assertEquals("gpt-5", options(chat)?.model)
        assertEquals("p", options(chat)?.provider)
    }

    @Test
    fun typedModelTakesTheProviderArgument() = test(configSet = """{"value":"gpt-5","scope":"session"}""") { _, chat ->
        chat.runModelCommand("gpt-5 --provider openai")

        assertEquals(BASE.copy(model = "gpt-5", provider = "openai"), options(chat))
    }

    @Test
    fun typedModelConfirmation() = test(configSet = """{"confirm_required":true,"confirm_message":"costly"}""") { peer, chat ->
        assertEquals(ModelSwitch.NeedsConfirmation("costly"), chat.runModelCommand("gpt-5"))
        chat.runModelCommand("gpt-5", confirmed = true)

        assertEquals(
            listOf(
                obj("""{"key":"model","session_id":"live1","value":"gpt-5"}"""),
                obj("""{"key":"model","session_id":"live1","value":"gpt-5","confirm_expensive_model":true}"""),
            ),
            peer.configCalls(),
        )
    }

    @Test
    fun typedReasoningScopes() = test(configSet = """{"value":"high"}""") { peer, chat ->
        assertEquals(OptionChange("high", true), chat.runOptionCommand("reasoning", "high --global"))
        assertEquals(OptionChange("high", false), chat.runOptionCommand("reasoning", "high"))

        assertEquals(
            listOf(
                obj("""{"key":"reasoning","session_id":"live1","value":"high","scope":"global"}"""),
                obj("""{"key":"reasoning","session_id":"live1","value":"high"}"""),
            ),
            peer.configCalls(),
        )
        assertEquals("high", options(chat)?.reasoning)
    }

    @Test
    fun typedReasoningDisplayWordLeavesTheLevel() = test(configSet = """{"value":"show"}""") { _, chat ->
        assertEquals(OptionChange("show", false), chat.runOptionCommand("reasoning", "show"))

        assertEquals(BASE, options(chat))
    }

    @Test
    fun typedYoloGlobal() = test(configSet = """{"value":"1","scope":"global"}""") { peer, chat ->
        chat.runOptionCommand("yolo", "on --global")

        assertEquals(listOf(obj("""{"key":"yolo","session_id":"live1","value":"on","scope":"global"}""")), peer.configCalls())
        assertEquals(true, options(chat)?.yolo)
    }

    @Test
    fun typedFastGlobalWritesDefaultThenSession() = test(configSet = """{"value":"fast"}""") { peer, chat ->
        chat.runOptionCommand("fast", "on --global")
        chat.runOptionCommand("fast", "on")

        assertEquals(
            listOf(
                obj("""{"key":"fast","value":"on"}"""),
                obj("""{"key":"fast","session_id":"live1","value":"on"}"""),
                obj("""{"key":"fast","session_id":"live1","value":"on"}"""),
            ),
            peer.configCalls(),
        )
        assertEquals(true, options(chat)?.fast)
    }

    @Test
    fun reattachTakesOptionsFromTheActivateInfo() = test { peer, chat ->
        peer.on("session.activate", """{"session_id":"live1","messages":[],"running":false,"info":{"model":"c","provider":"p"}}""")
        peer.state.value = ConnectionState.Connecting
        peer.state.value = ConnectionState.Ready

        assertEquals("c", options(chat)?.model)
    }

    private fun dropAndReturn(peer: FakePeer) {
        peer.state.value = ConnectionState.Connecting
        peer.state.value = ConnectionState.Ready
    }

    @Test
    fun lazyActivateInfoKeepsOptions() = test(configSet = """{"key":"yolo","value":"1","scope":"session"}""") { peer, chat ->
        chat.setYolo(true)
        peer.on("session.activate", """{"session_id":"live1","messages":[],"running":false,"info":{"lazy":true,"model":"a","provider":"p"}}""")

        dropAndReturn(peer)

        assertEquals(BASE.copy(yolo = true), options(chat))
    }

    @Test
    fun lazyActivateInfoIsTakenWhenTheChatHasNoOptions() = test(info = """{"lazy":true}""") { peer, chat ->
        peer.on("session.activate", """{"session_id":"live1","messages":[],"running":false,"info":{"lazy":true,"model":"a","provider":"p"}}""")

        dropAndReturn(peer)

        assertEquals(BASE, options(chat))
    }

    @Test
    fun reopenTakesWhateverTheInfoSays() = test(configSet = """{"key":"yolo","value":"1","scope":"session"}""") { peer, chat ->
        chat.setYolo(true)
        peer.on("session.activate") { throw RpcException(RpcError(4001, "unknown session")) }
        peer.on("session.resume", resume("""{"lazy":true,"model":"a","provider":"p"}"""))

        dropAndReturn(peer)

        assertEquals(BASE, options(chat))
    }

    @Test
    fun yoloStaysOnWhileApprovalsAreOff() = test(info = """{"model":"a","provider":"p","reasoning_effort":"","fast":false,"yolo":true,"approval_mode":"off"}""") { peer, chat ->
        peer.on("config.set", """{"key":"yolo","value":"0","scope":"session"}""")
        chat.setYolo(false)
        assertEquals(true, options(chat)?.yolo)

        chat.runOptionCommand("yolo", "off")
        assertEquals(true, options(chat)?.yolo)
    }

    @Test
    fun yoloTurnsOffWhenApprovalsAreNotOff() = test(info = """{"model":"a","provider":"p","reasoning_effort":"","fast":false,"yolo":true,"approval_mode":"manual"}""") { peer, chat ->
        peer.on("config.set", """{"key":"yolo","value":"0","scope":"session"}""")
        chat.setYolo(false)

        assertEquals(false, options(chat)?.yolo)
    }
}
