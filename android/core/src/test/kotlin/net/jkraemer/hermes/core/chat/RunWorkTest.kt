package net.jkraemer.hermes.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunWorkTest {
    private val prompt = ChatItem.User("[IMPORTANT: You are running as a scheduled cron job.]\n\n/attention-check")
    private fun said(text: String) = ChatItem.Assistant(text, streaming = false)
    private fun tools(vararg names: String) = ChatItem.Tools(names.map { ToolCall(it, it, null, done = true, failed = false) })

    @Test
    fun everythingBeforeTheFinalReplyIsWork() {
        val items = listOf(prompt, said("Let me check."), said(""), tools("read_file", "search"), said("No new mails."), tools("write_file"), said("[SILENT]"))

        // The prompt, two messages with text and three tool calls; the empty message only carried the calls.
        assertEquals(RunWork(end = 6, steps = 6), cronRunWork(items))
    }

    @Test
    fun theFinalReplyIsTheLastTextBeforeTheUserFirstAnswers() {
        val items = listOf(prompt, tools("search"), said("Two new mails."), ChatItem.User("thanks"), said("You're welcome."))

        assertEquals(RunWork(end = 2, steps = 2), cronRunWork(items))
    }

    @Test
    fun aRunThatWentStraightToItsReplyHasOnlyThePromptAsWork() {
        assertEquals(RunWork(end = 1, steps = 1), cronRunWork(listOf(prompt, said("All quiet."))))
    }

    @Test
    fun aRunWithoutAReplyYetIsAllWork() {
        assertEquals(RunWork(end = 3, steps = 2), cronRunWork(listOf(prompt, said(""), tools("search"))))
    }

    @Test
    fun aMarkerHermesWroteAsAUserRowDoesNotEndTheRun() {
        val items = listOf(prompt, said("Checking."), ChatItem.User("[Note: model switched]", displayKind = "model_switch"), said("Two new mails."))

        assertEquals(RunWork(end = 3, steps = 3), cronRunWork(items))
    }

    @Test
    fun aPromptShownAsASkillInvocationIsThePrompt() {
        val items = listOf(ChatItem.User("/attention-check", displayKind = "skill_invocation"), said("Two new mails."), ChatItem.User("thanks"), said("Welcome."))

        assertEquals(RunWork(end = 1, steps = 1), cronRunWork(items))
    }

    @Test
    fun anEmptyChatHasNoWork() {
        assertNull(cronRunWork(emptyList()))
    }
}
