package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.runBlocking
import net.jkraemer.hermes.core.chat.ApprovalChoice
import net.jkraemer.hermes.core.chat.CardStatus
import net.jkraemer.hermes.core.chat.ChatController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ChatE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var client: E2eClient? = null
    private val createdDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        client?.close()
        createdDirs.forEach { it.deleteRecursively() }
    }

    private suspend fun newChat(): ChatController {
        val connected = E2eClient.connect(folder.newFile()).also { client = it }
        return connected.newChat().also { it.startNew() }
    }

    /** A fresh directory under HERMES_HOME, the only place both the host and the container see. */
    private fun freshDirectory(): File =
        File(E2eEnv.home, "e2e-approval-${UUID.randomUUID()}").also {
            check(it.mkdirs()) { "could not create $it" }
            createdDirs += it
        }

    @Test
    fun replyStreamsAndCompletes() = runBlocking {
        val chat = newChat()

        chat.sendAndAwaitReply("Reply with exactly the word: pong", "pong")
        Unit
    }

    @Test
    fun approvalRoundTrip() = runBlocking {
        val chat = newChat()
        val directory = freshDirectory()
        val (command, card) = chat.requestRemoval(directory)

        chat.answerGuarded(command, card, ApprovalChoice.DENY)

        val answered = chat.state.value.approval()!!
        assertEquals(CardStatus.Answered("Denied"), answered.status)
        chat.awaitState { !it.running } ?: failTurnNotEnded(chat)
        assertTrue("the denied command must not have run", directory.exists())
    }

    @Test
    fun approvalAllowOnceRuns() = runBlocking {
        val chat = newChat()
        val directory = freshDirectory()
        val (command, card) = chat.requestRemoval(directory)

        chat.answerGuarded(command, card, ApprovalChoice.ONCE)

        assertEquals(CardStatus.Answered("Allowed once"), chat.state.value.approval()!!.status)
        chat.awaitState { !it.running } ?: failTurnNotEnded(chat)
        assertFalse("the allowed command should have removed the directory", directory.exists())
    }

    @Test
    fun clarifyRoundTrip() = runBlocking {
        val chat = newChat()
        val card = chat.requestColourChoice()

        chat.answerClarify(card.requestId, mapOf(card.questions.first().qid to listOf("red")))

        assertEquals(CardStatus.Answered("Answered"), chat.state.value.clarify()!!.status)
        chat.awaitState { !it.running && it.hasFinishedReplyContaining("red") } ?: failTurnNotEnded(chat)
        Unit
    }
}
