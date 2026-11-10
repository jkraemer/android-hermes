package net.jkraemer.hermes.core.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.jkraemer.hermes.core.commands.CommandOutcome
import net.jkraemer.hermes.core.commands.commandCatalogFrom
import net.jkraemer.hermes.core.commands.parseSlashCommand
import net.jkraemer.hermes.core.commands.runCommand
import net.jkraemer.hermes.core.rpc.RpcError
import net.jkraemer.hermes.core.rpc.RpcException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private val NOW = Instant.parse("2026-01-01T00:00:00Z")

private const val SESSION = """{"session_id":"live1","stored_session_id":"stored1","messages":[],"running":false,"open_requests":[],
    "info":{"model":"a","provider":"p","reasoning_effort":"","fast":false,"yolo":false}}"""

private val CATALOG = commandCatalogFrom(
    obj(
        """
        {
          "canon": {
            "/title": "/title", "/new": "/new", "/reset": "/new", "/stop": "/stop", "/context": "/context",
            "/compress": "/compress", "/model": "/model", "/reasoning": "/reasoning", "/fast": "/fast", "/yolo": "/yolo",
            "/undo": "/undo", "/loop": "/loop", "/a": "/a", "/b": "/b"
          },
          "skills": {"/arxiv": {"usage": 1, "origin": "bundled"}}
        }
        """.trimIndent(),
    ),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerCommandsTest {
    private fun test(block: suspend (FakePeer, ChatController) -> Unit) = runTest(UnconfinedTestDispatcher()) {
        val peer = FakePeer()
        peer.on("session.create", SESSION)
        peer.on("prompt.submit", "{}")
        val chat = ChatController(peer, backgroundScope) { NOW }
        chat.startNew()
        block(peer, chat)
    }

    private suspend fun ChatController.run(text: String) = runCommand(parseSlashCommand(text)!!, CATALOG)

    private fun FakePeer.methods() = calls.map { it.first }.filter { it !in setOf("session.create", "session.events.since", "session.activate") }

    private fun ChatController.items() = state.value.items

    private fun refused(code: Int, message: String): suspend (JsonObject) -> Nothing = { throw RpcException(RpcError(code, message)) }

    @Test
    fun titleIsSetDirectly() = test { peer, chat ->
        peer.on("session.title", """{"title":"Trip plans"}""")
        assertEquals(CommandOutcome.Done, chat.run("/title Trip plans"))
        assertEquals(listOf("session.title"), peer.methods())
        assertEquals(obj("""{"session_id":"live1","title":"Trip plans"}"""), peer.calls.last().second)
    }

    @Test
    fun titleWithoutNameShowsUsage() = test { peer, chat ->
        assertEquals(CommandOutcome.Done, chat.run("/title"))
        assertEquals(listOf(ChatItem.Notice("Usage: /title <name>")), chat.items())
        assertTrue(peer.methods().isEmpty())
    }

    @Test
    fun newAndStopAreLocal() = test { peer, chat ->
        peer.on("session.interrupt", "{}")
        assertEquals(CommandOutcome.NewChat, chat.run("/new"))
        assertEquals(CommandOutcome.NewChat, chat.run("/reset"))
        assertTrue(peer.methods().isEmpty())
        assertEquals(CommandOutcome.Done, chat.run("/stop"))
        assertEquals(listOf("session.interrupt"), peer.methods())
    }

    @Test
    fun bareOptionCommandsOpenTheSheet() = test { peer, chat ->
        for (name in listOf("/model", "/reasoning", "/fast", "/yolo")) assertEquals(name, CommandOutcome.OpenOptions, chat.run(name))
        assertTrue(peer.methods().isEmpty())
    }

    @Test
    fun typedModelRuns() = test { peer, chat ->
        peer.on("config.set", """{"key":"model","value":"gpt-5","scope":"global"}""")
        assertEquals(CommandOutcome.Done, chat.run("/model gpt-5 --global"))
        assertEquals("gpt-5 --global", peer.calls.last().second["value"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(listOf(ChatItem.Notice("Model: gpt-5 (global)")), chat.items())

        peer.on("config.set", """{"key":"model","value":"gpt-5","deferred":true}""")
        chat.run("/model gpt-5")
        assertEquals(ChatItem.Notice("Model: gpt-5 from the next message"), chat.items().last())
    }

    @Test
    fun costlyModelAsksForConfirmation() = test { peer, chat ->
        peer.on("config.set", """{"key":"model","confirm_required":true,"confirm_message":"costly"}""")
        assertEquals(CommandOutcome.ConfirmModel("gpt-5 --global", "costly"), chat.run("/model gpt-5 --global"))
        assertTrue(chat.items().isEmpty())
    }

    @Test
    fun typedOptionsRun() = test { peer, chat ->
        peer.on("config.set") { params ->
            obj("""{"key":"${(params["key"] as kotlinx.serialization.json.JsonPrimitive).content}","value":"${
                mapOf("reasoning" to "high", "yolo" to "1", "fast" to "fast")[(params["key"] as kotlinx.serialization.json.JsonPrimitive).content]
            }"}""")
        }
        chat.run("/reasoning high")
        chat.run("/yolo on --global")
        chat.run("/fast on")
        assertEquals(
            listOf(
                ChatItem.Notice("Reasoning: high"),
                ChatItem.Notice("YOLO: 1 (global default)"),
                ChatItem.Notice("Fast mode: fast"),
            ),
            chat.items(),
        )
    }

    @Test
    fun execOutputBecomesNotice() = test { peer, chat ->
        peer.on("slash.exec", """{"output":"Context: 12k tokens"}""")
        assertEquals(CommandOutcome.Done, chat.run("/context"))
        assertEquals(ChatItem.Notice("Context: 12k tokens"), chat.items().last())
        assertEquals(obj("""{"session_id":"live1","command":"context"}"""), peer.calls.last().second)
    }

    @Test
    fun execCommandCarriesItsArgument() = test { peer, chat ->
        peer.on("slash.exec", """{"output":"ok"}""")
        chat.run("/compress focus on the API")
        assertEquals(obj("""{"session_id":"live1","command":"compress focus on the API"}"""), peer.calls.last().second)
    }

    @Test
    fun warningIsShownAfterOutput() = test { peer, chat ->
        peer.on("slash.exec", """{"output":"x","warning":"y"}""")
        chat.run("/context")
        assertEquals(listOf(ChatItem.Notice("x"), ChatItem.Notice("y")), chat.items())
    }

    @Test
    fun skillFallsBackToDispatch() = test { peer, chat ->
        peer.on("slash.exec", refused(4018, "skill command: use command.dispatch for /arxiv"))
        peer.on("command.dispatch", """{"type":"skill","message":"LONG SKILL PROMPT","display":"/arxiv llm agents","name":"arxiv"}""")
        assertEquals(CommandOutcome.Done, chat.run("/arxiv llm agents"))
        assertEquals(listOf("slash.exec", "command.dispatch", "prompt.submit"), peer.methods())
        assertEquals(obj("""{"session_id":"live1","name":"arxiv","arg":"llm agents"}"""), peer.calls.first { it.first == "command.dispatch" }.second)
        assertEquals(obj("""{"session_id":"live1","text":"LONG SKILL PROMPT"}"""), peer.calls.last().second)
        assertEquals(listOf(ChatItem.User("/arxiv llm agents", displayKind = "skill_invocation")), chat.items())
    }

    @Test
    fun sendDirectiveShowsNoticeAndSubmits() = test { peer, chat ->
        peer.on("slash.exec", """{"type":"send","message":"m","notice":"n"}""")
        chat.run("/context")
        assertEquals(listOf(ChatItem.Notice("n"), ChatItem.User("m")), chat.items())
        assertEquals(obj("""{"session_id":"live1","text":"m"}"""), peer.calls.last().second)
    }

    @Test
    fun sendDirectiveShowsItsDisplayInsteadOfTheMessage() = test { peer, chat ->
        peer.on("slash.exec", """{"type":"send","message":"LONG PROMPT","display":"/context"}""")
        chat.run("/context")
        assertEquals(listOf(ChatItem.User("/context")), chat.items())
        assertEquals(obj("""{"session_id":"live1","text":"LONG PROMPT"}"""), peer.calls.last().second)
    }

    @Test
    fun blankSendDisplayFallsBackToTheMessage() = test { peer, chat ->
        peer.on("slash.exec", """{"type":"send","message":"m","display":"  "}""")
        chat.run("/context")
        assertEquals(listOf(ChatItem.User("m")), chat.items())
    }

    @Test
    fun blankSkillDisplayFallsBackToTheTypedCommand() = test { peer, chat ->
        peer.on("slash.exec", refused(4018, "skill command"))
        peer.on("command.dispatch", """{"type":"skill","message":"LONG SKILL PROMPT","display":" ","name":"arxiv"}""")
        chat.run("/arxiv llm agents")
        assertEquals(listOf(ChatItem.User("/arxiv llm agents", displayKind = "skill_invocation")), chat.items())
    }

    @Test
    fun aliasIsFollowed() = test { peer, chat ->
        peer.on("slash.exec") { params ->
            if ((params["command"] as kotlinx.serialization.json.JsonPrimitive).content == "context please") obj("""{"type":"alias","target":"compress"}""")
            else obj("""{"output":"compressed"}""")
        }
        chat.run("/context please")
        assertEquals(obj("""{"session_id":"live1","command":"compress please"}"""), peer.calls.last().second)
        assertEquals(listOf(ChatItem.Notice("compressed")), chat.items())
    }

    @Test
    fun aliasLoopStopsAfterThreeHops() = test { peer, chat ->
        peer.on("slash.exec", """{"type":"alias","target":"loop"}""")
        assertEquals(CommandOutcome.Done, chat.run("/loop"))
        assertEquals(4, peer.methods().size)
        assertEquals(listOf(ChatItem.Notice("Command alias loop: /loop")), chat.items())
    }

    @Test
    fun prefillIsReturned() = test { peer, chat ->
        peer.on("slash.exec", """{"type":"prefill","message":"last message"}""")
        assertEquals(CommandOutcome.Prefill("last message"), chat.run("/undo"))
    }

    @Test
    fun refusedCommandShowsError() = test { peer, chat ->
        peer.on("slash.exec", refused(4018, "skill command"))
        peer.on("command.dispatch", refused(4009, "session busy — wait"))
        assertEquals(CommandOutcome.Done, chat.run("/arxiv x"))
        assertEquals(ChatItem.Error("session busy — wait"), chat.items().last())
    }

    @Test
    fun failedSkillSubmitShowsOneError() = test { peer, chat ->
        peer.on("slash.exec", refused(4018, "skill command"))
        peer.on("command.dispatch", """{"type":"skill","message":"P","display":"/arxiv x","name":"arxiv"}""")
        peer.on("prompt.submit", refused(4009, "session busy"))
        chat.run("/arxiv x")
        assertEquals(listOf(ChatItem.User("/arxiv x", "skill_invocation"), ChatItem.Error("session busy")), chat.items())
    }

    @Test
    fun noticesSurviveLaterEvents() = test { peer, chat ->
        peer.on("slash.exec", """{"output":"hello"}""")
        chat.run("/context")
        peer.event("message.delta", """{"text":"hi"}""", seq = 1)
        assertEquals(listOf(ChatItem.Notice("hello"), ChatItem.Assistant("hi", streaming = true)), chat.items())
    }
}
