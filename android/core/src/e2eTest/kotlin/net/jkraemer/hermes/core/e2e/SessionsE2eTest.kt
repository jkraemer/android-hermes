package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.chat.ChatItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.TimeUnit

class SessionsE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var client: E2eClient? = null

    @After
    fun tearDown() {
        client?.close()
    }

    private suspend fun connect(): E2eClient = E2eClient.connect(folder.newFile()).also { client = it }

    /** Sends [prompt] and waits for the turn to end, whatever the agent answered. */
    private suspend fun ChatController.sendAndAwaitEndOfTurn(prompt: String) {
        send(prompt)
        awaitState { !it.running } ?: throw AssertionError("the turn did not end: ${state.value.lastAgentOutput()}")
    }

    /** Runs the Hermes CLI with the e2e Hermes home and returns its combined output. */
    private suspend fun runCli(vararg args: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(E2eEnv.cli) + args)
            .redirectErrorStream(true)
            .apply { environment()["HERMES_HOME"] = E2eEnv.home }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(MODEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "hermes ${args.toList()} did not finish" }
        assertEquals("hermes ${args.toList()} failed: $output", 0, process.exitValue())
        output
    }

    /** A word no earlier session of this long-lived Hermes home contains, so search finds only this run's session. */
    private fun uniqueWord() = "pong" + UUID.randomUUID().toString().replace("-", "").take(10)

    @Test
    fun appSessionIsListedAndSearchable() = runBlocking {
        val app = connect()
        val chat = app.newChat().also { it.startNew() }
        val word = uniqueWord()
        chat.sendAndAwaitReply("Reply with exactly the word: $word", word)
        val id = chat.state.value.storedSessionId!!

        assertTrue("session $id not listed", app.sessions.list().sessions.any { it.id == id })
        assertTrue("session $id not found by search", app.sessions.search(word).any { it.session.id == id })
    }

    @Test
    fun contentSearchHitCarriesASnippetWithTheMatch() = runBlocking {
        val app = connect()
        val chat = app.newChat().also { it.startNew() }
        val word = uniqueWord()
        chat.sendAndAwaitReply("Reply with exactly the word: $word", word)
        val id = chat.state.value.storedSessionId!!

        val hit = app.sessions.search(word).single { it.session.id == id }

        val snippet = hit.snippet ?: throw AssertionError("search hit for $id has no snippet")
        assertTrue("no matched span in $snippet", snippet.matches.isNotEmpty())
        assertTrue(
            "matched spans ${snippet.matches.map { snippet.text.substring(it) }} do not include $word",
            snippet.matches.any { snippet.text.substring(it).contains(word, ignoreCase = true) },
        )
    }

    // The app starts a chat (session.create) at every launch and "+", whether or not anything is sent.
    // That is only harmless while Hermes keeps such a draft out of the session list.
    @Test
    fun aNewChatWithoutAMessageLeavesNoRowInTheSessionList() = runBlocking {
        val app = connect()
        val before = app.sessions.list(limit = 100).sessions.map { it.id }.toSet()
        val chat = app.newChat().also { it.startNew() }
        val id = chat.state.value.storedSessionId!!

        val after = app.sessions.list(limit = 100).sessions
        val hits = app.sessions.search(id)

        assertEquals("a draft produced rows: ${after.filter { it.id !in before }}", before, after.map { it.id }.toSet())
        assertTrue("draft $id found by search: $hits", hits.none { it.session.id == id })
    }

    @Test
    fun appSessionIsVisibleToCli() = runBlocking {
        val app = connect()
        val chat = app.newChat().also { it.startNew() }
        chat.sendAndAwaitEndOfTurn("Reply with exactly the word: pong")
        val id = chat.state.value.storedSessionId!!

        val output = runCli("sessions", "list", "--limit", "50")

        assertTrue("session $id missing from the CLI listing:\n$output", output.contains(id))
    }

    @Test
    fun updatePinsSessionOnRealHermes() = runBlocking {
        val app = connect()
        val chat = app.newChat().also { it.startNew() }
        val id = chat.state.value.storedSessionId!!
        if (app.sessions.list().sessions.none { it.id == id }) chat.sendAndAwaitEndOfTurn("Reply with exactly the word: pong")

        app.sessions.update(id, pinned = true)

        val row = app.sessions.list().sessions.single { it.id == id }
        assertTrue("session $id should be pinned", row.pinned)
    }

    @Test
    fun cliSessionIsOpenableInApp() = runBlocking {
        val app = connect()
        val before = app.sessions.list(limit = 100).sessions.map { it.id }.toSet()

        runCli("chat", "-q", "Reply with exactly: cli-ok", "-Q")

        val created = app.sessions.list(limit = 100).sessions.filter { it.id !in before }
        assertEquals("expected exactly one new session, got $created", 1, created.size)
        val chat = app.newChat()
        chat.open(created.single().id)
        val transcript = chat.state.value.items
        assertTrue("transcript lacks cli-ok: $transcript", transcript.any { it is ChatItem.Assistant && it.markdown.contains("cli-ok") })

        chat.sendAndAwaitReply("Reply with exactly: app-ok", "app-ok")
        Unit
    }
}
