package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.options.ModelRef
import net.jkraemer.hermes.core.options.ModelSwitch
import net.jkraemer.hermes.core.options.runModelCommand
import net.jkraemer.hermes.core.options.setReasoning
import net.jkraemer.hermes.core.options.setYolo
import net.jkraemer.hermes.core.options.switchModel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OptionsE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clients = mutableListOf<E2eClient>()
    private val config = File(E2eEnv.home, "config.yaml")

    @After
    fun tearDown() {
        clients.forEach { it.close() }
    }

    private suspend fun connect() = E2eClient.connect(folder.newFile()).also { clients += it }

    /** The default model and provider, and a model of that provider other than the default; `model.options` as Hermes sends it. */
    private data class Models(val default: ModelRef, val other: String)

    private suspend fun models(app: E2eClient): Models {
        val result = app.rpc.call("model.options", buildJsonObject { put("include_unconfigured", false) }).jsonObject
        val default = ModelRef(result.text("provider")!!, result.text("model")!!)
        val provider = (result["providers"] as JsonArray).map { it.jsonObject }.single { it.text("slug") == default.provider }
        val other = (provider["models"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.first { it != default.model }
        return Models(default, other)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** The session as a second connection sees it after resuming [stored]. */
    private suspend fun reopened(stored: String): ChatController =
        connect().newChat().also { it.open(stored) }

    private suspend fun ChatController.cheapTurn() {
        sendAndAwaitReply("Reply with exactly the word: pong", "pong")
    }

    private fun ChatController.stored(): String = state.value.storedSessionId ?: throw AssertionError("no stored session id")

    /** Runs [block] and asserts it left Hermes' config.yaml byte-identical. */
    private suspend fun configUnchanged(block: suspend () -> Unit) {
        val before = config.readBytes()
        block()
        assertArrayEquals("config.yaml changed", before, config.readBytes())
    }

    @Test
    fun modelSwitchStaysInSession() = runBlocking {
        configUnchanged {
            val app = connect()
            val models = models(app)
            val chat = app.newChat().also { it.startNew() }
            chat.cheapTurn()

            val switch = chat.switchModel(ModelRef(models.default.provider, models.other))

            assertTrue("not applied: $switch", switch is ModelSwitch.Applied)
            assertEquals(models.other, chat.state.value.options?.model)

            val created = app.rpc.call("session.create").jsonObject
            assertEquals(models.default.model, created["info"]?.jsonObject?.text("model"))

            assertEquals(models.other, reopened(chat.stored()).state.value.options?.model)
        }
    }

    @Test
    fun reasoningAndYoloStayInSession() = runBlocking {
        configUnchanged {
            val app = connect()
            val chat = app.newChat().also { it.startNew() }
            chat.cheapTurn()

            chat.setReasoning("low")
            chat.setYolo(true)

            assertEquals("low", chat.state.value.options?.reasoning)
            assertEquals(true, chat.state.value.options?.yolo)
            val reopened = reopened(chat.stored())
            assertEquals("low", reopened.state.value.options?.reasoning)
            assertEquals(true, reopened.state.value.options?.yolo)

            val fresh = app.newChat().also { it.startNew() }.also { it.cheapTurn() }
            assertEquals("", fresh.state.value.options?.reasoning)
            assertEquals(false, fresh.state.value.options?.yolo)
        }
    }

    @Test
    fun optionsOnResumedSessionWithoutTurn() = runBlocking {
        val first = connect()
        val chat = first.newChat().also { it.startNew() }.also { it.cheapTurn() }
        val stored = chat.stored()
        first.close()
        clients.remove(first)

        val app = connect()
        val resumed = app.newChat().also { it.open(stored) }
        resumed.setReasoning("high")
        app.close()
        clients.remove(app)
        delay(ORPHAN_REAP_WAIT_MS)

        val observed = reopened(stored).state.value.options?.reasoning
        assertEquals("Hermes keeps an option set before the agent is built in memory only", "high", observed)
    }

    @Test
    fun typedGlobalModelChangesDefault() = runBlocking {
        val app = connect()
        val models = models(app)
        val chat = app.newChat().also { it.startNew() }
        val provider = models.default.provider
        try {
            val switch = chat.runModelCommand("${models.other} --provider $provider --global")

            assertTrue("not applied: $switch", switch is ModelSwitch.Applied)
            assertEquals("global", (switch as ModelSwitch.Applied).scope)
            assertTrue("config.yaml default not changed", defaultModelInConfig() == models.other)
        } finally {
            chat.runModelCommand("${models.default.model} --provider $provider --global")
            assertEquals("default model not restored", models.default.model, defaultModelInConfig())
        }
    }

    /** `model.default` of config.yaml. */
    private fun defaultModelInConfig(): String? {
        val lines = config.readLines()
        val start = lines.indexOfFirst { it.trimEnd() == "model:" }
        if (start < 0) return null
        return lines.drop(start + 1).takeWhile { it.startsWith(" ") }
            .firstOrNull { it.trim().startsWith("default:") }
            ?.substringAfter("default:")?.trim()?.trim('"', '\'')
    }

    private companion object {
        /** Hermes reaps a session whose connection dropped after about this long. */
        const val ORPHAN_REAP_WAIT_MS = 25_000L
    }
}
