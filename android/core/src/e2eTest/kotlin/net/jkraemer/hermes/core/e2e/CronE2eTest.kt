package net.jkraemer.hermes.core.e2e

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jkraemer.hermes.core.cron.FinalReplies
import net.jkraemer.hermes.core.cron.feedEntries
import net.jkraemer.hermes.core.cron.isSilent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CronE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var client: E2eClient? = null
    private val createdJobs = mutableListOf<String>()

    @After
    fun tearDown(): Unit = runBlocking {
        client?.deleteCronJobs(createdJobs)
        client?.close()
    }

    private suspend fun connect(): E2eClient = E2eClient.connect(folder.newFile()).also { client = it }

    /** Creates a daily job that runs [prompt] and returns its id; the test deletes it afterwards. */
    private suspend fun E2eClient.createJob(name: String, prompt: String): String =
        createCronJob(name, prompt).also { createdJobs += it }

    @Test
    fun aRunThatHadNothingToReportEndsSilent() = runBlocking {
        val app = connect()
        val name = uniqueName("e2e-silent")
        val job = app.createJob(name, SILENT_JOB_PROMPT)

        assertTrue("job $job not listed: ${app.cron.jobs()}", app.cron.jobs().any { it.id == job && it.name == name })
        val run = app.runOnce(job)

        assertFalse("run $run is still owned by the scheduler", run.running)
        assertFalse(run.archived)
        val reply = app.sessions.finalReply(run.sessionId)
        assertTrue("reply of ${run.sessionId} is not silent: $reply", reply != null && isSilent(reply))
    }

    @Test
    fun aRunThatReportedSomethingEndsWithItsReport() = runBlocking {
        val app = connect()
        val job = app.createJob(
            uniqueName("e2e-report"),
            REPORTING_JOB_PROMPT,
        )

        val run = app.runOnce(job)

        val reply = app.sessions.finalReply(run.sessionId)
        assertTrue("reply of ${run.sessionId} lacks the report: $reply", reply?.contains("cron-ok") == true)
        assertFalse("reply of ${run.sessionId} counts as silent: $reply", isSilent(reply!!))
        assertTrue("the job's lastRunAt is not set", app.cron.jobs().single { it.id == job }.lastRunAt != null)
    }

    @Test
    fun archivingARunMarksItArchivedInTheJobsRuns() = runBlocking {
        val app = connect()
        val job = app.createJob(uniqueName("e2e-archive"), SILENT_JOB_PROMPT)
        val run = app.runOnce(job)

        app.sessions.update(run.sessionId, archived = true)

        assertTrue("run ${run.sessionId} is not archived: ${app.cron.runs(job)}", app.cron.runs(job).runs.single().archived)
        assertTrue(
            "archived run ${run.sessionId} is still in the session list",
            app.sessions.list(limit = 100).sessions.none { it.id == run.sessionId },
        )
    }

    @Test
    fun aReplyInARunsChatLeavesTheRunsReportAsItsFeedEntry() = runBlocking {
        val app = connect()
        val job = app.createJob(
            uniqueName("e2e-reply"),
            REPORTING_JOB_PROMPT,
        )
        val run = app.runOnce(job)

        // What the feed's composer does: open the run's session and send the reply there.
        val chat = app.newChat()
        chat.open(run.sessionId)
        chat.sendAndAwaitReply("Reply with exactly the word: reply-ok", "reply-ok")
        chat.awaitState { !it.running } ?: throw AssertionError("the reply's turn did not end: ${chat.state.value.lastAgentOutput()}")

        val runs = app.cron.runs(job).runs
        val replies = FinalReplies(app.sessions::finalReply)
        val entries = feedEntries(runs, runs.associate { it.sessionId to replies.of(it) })
        assertEquals("feed of $job: $entries", listOf(run.sessionId), entries.map { it.sessionId })
        assertTrue("the entry is not the run's report: ${entries.single().reply}", entries.single().reply.contains("cron-ok"))
        assertFalse("the entry is the answer to the reply: ${entries.single().reply}", entries.single().reply.contains("reply-ok"))
    }

    @Test
    fun anAnswerWithManyToolCallsAfterAReplyLeavesTheRunsReport() = runBlocking {
        val app = connect()
        val job = app.createJob(
            uniqueName("e2e-tools"),
            REPORTING_JOB_PROMPT,
        )
        val run = app.runOnce(job)
        val chat = app.newChat()
        chat.open(run.sessionId)

        // Each command needs the one before it, so the agent cannot batch them into one step.
        chat.send(
            "Use the terminal tool to run `echo 1`. Then, one call at a time, run echo with the number the previous " +
                "command printed plus one, until you have run `echo 6`. After that, answer with exactly the word: done-ok",
        )
        chat.awaitState { it.hasFinishedReplyContaining("done-ok") && !it.running } ?: failMisstep(chat)

        // The answer alone fills the latest page of the session, so the report can only be found from the start.
        val latest = Json.parseToJsonElement(app.rest("GET", "api/sessions/${run.sessionId}/messages?order=latest&limit=10&inline_images=false"))
            .jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
        if (latest.size < 10 || latest.any { it["role"]?.jsonPrimitive?.content == "user" && (it["display_kind"] ?: JsonNull) == JsonNull }) {
            failMisstep(chat)
        }
        val reply = app.sessions.finalReply(run.sessionId)
        assertTrue("the run's report is not its final reply: $reply", reply?.contains("cron-ok") == true)
    }
}
