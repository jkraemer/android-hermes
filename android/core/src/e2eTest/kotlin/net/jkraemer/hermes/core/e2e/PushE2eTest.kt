package net.jkraemer.hermes.core.e2e

import com.google.crypto.tink.apps.webpush.WebPushHybridDecrypt
import com.google.crypto.tink.subtle.EllipticCurves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jkraemer.hermes.core.chat.ApprovalChoice
import net.jkraemer.hermes.core.chat.ChatController
import net.jkraemer.hermes.core.cron.isSilent
import net.jkraemer.hermes.core.push.NotSubscribedException
import net.jkraemer.hermes.core.push.PushEvent
import net.jkraemer.hermes.core.push.pushEventFrom
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

private const val PUSH_TIMEOUT_MS = 60_000L
private const val POLL_INTERVAL_MS = 1_000L

/** One message ntfy holds for a topic; [body] is what was published, [encoding] how ntfy carried it. */
private class NtfyMessage(val encoding: String?, val body: ByteArray)

/**
 * A UnifiedPush subscription on the e2e ntfy, with its own topic and WebPush keys, as the app's connector has on
 * the phone. Reads what was pushed to the topic by polling ntfy and decrypts it.
 */
private class NtfyInbox {
    private val http = OkHttpClient()
    private val random = SecureRandom()
    private val topic = "up" + (1..12).map { TOPIC_CHARS[random.nextInt(TOPIC_CHARS.length)] }.joinToString("")
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val authSecret = ByteArray(16).also(random::nextBytes)
    private val publicKey = keys.public as ECPublicKey
    private val decrypter = WebPushHybridDecrypt.Builder()
        .withAuthSecret(authSecret)
        .withRecipientPublicKey(publicKey)
        .withRecipientPrivateKey(keys.private as ECPrivateKey)
        .build()

    /** Where Hermes pushes to, as the Hermes container reaches ntfy. */
    val endpoint = "${E2eEnv.ntfyUrlFromHermes}/$topic?up=1"
    val p256dh: String = base64Url(EllipticCurves.pointEncode(EllipticCurves.CurveType.NIST_P256, EllipticCurves.PointFormatType.UNCOMPRESSED, publicKey.w))
    val auth: String = base64Url(authSecret)

    /** Every message on the topic so far, oldest first. */
    suspend fun messages(): List<NtfyMessage> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${E2eEnv.ntfyUrl}/$topic/json?poll=1").build()
        val lines = http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "polling ntfy failed with ${response.code}" }
            response.body!!.string().lines().filter { it.isNotBlank() }
        }
        lines.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["event"]?.jsonPrimitive?.content == "message" }
            .map { fields ->
                val encoding = fields["encoding"]?.jsonPrimitive?.content
                val message = fields.getValue("message").jsonPrimitive.content
                NtfyMessage(encoding, if (encoding == "base64") Base64.getDecoder().decode(message) else message.toByteArray())
            }
    }

    /** Every event pushed to the topic so far, oldest first. */
    suspend fun events(): List<PushEvent> = messages().map { message ->
        val plaintext = decrypter.decrypt(message.body, null)
        pushEventFrom(plaintext) ?: throw AssertionError("not a push event: ${plaintext.decodeToString()}")
    }

    /** Polls until an event matching [predicate] was pushed and returns it. */
    suspend fun awaitEvent(predicate: (PushEvent) -> Boolean): PushEvent {
        val deadline = System.currentTimeMillis() + PUSH_TIMEOUT_MS
        while (true) {
            val events = events()
            events.firstOrNull(predicate)?.let { return it }
            if (System.currentTimeMillis() > deadline) throw AssertionError("no matching push within ${PUSH_TIMEOUT_MS / 1000} s; pushed: $events")
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val TOPIC_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Pushes from the hermes-app plugin in the e2e Hermes, through the local ntfy, decrypted as the phone would.
 *
 * A pushed approval or question carries the id Hermes gives the human-input request, not the id of the dashboard
 * protocol's request behind the chat's card; the two are made independently. The resolved push carries the same
 * id as the push it settles.
 */
class PushE2eTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val inbox = NtfyInbox()
    private var client: E2eClient? = null
    private val createdJobs = mutableListOf<String>()

    @After
    fun tearDown(): Unit = runBlocking {
        client?.let { app ->
            app.deleteCronJobs(createdJobs)
            // The plugin has one subscription for all of Hermes; later tests must not push to this test's topic.
            app.push.unsubscribe()
        }
        client?.close()
    }

    /** A client whose Hermes pushes to [inbox]. */
    private suspend fun subscribed(): E2eClient = E2eClient.connect(folder.newFile()).also {
        client = it
        it.push.subscribe(inbox.endpoint, inbox.p256dh, inbox.auth)
    }

    private suspend fun E2eClient.newSession(): ChatController = newChat().also { it.startNew() }

    private fun ChatController.storedId(): String = state.value.storedSessionId ?: throw AssertionError("no stored session id")

    private suspend fun ChatController.awaitEndOfTurn() {
        awaitState { !it.running } ?: failTurnNotEnded(this)
    }

    @Test
    fun testPushArrivesEncrypted() = runBlocking {
        val app = subscribed()

        app.push.sendTest()

        assertEquals(PushEvent.Test, inbox.awaitEvent { it == PushEvent.Test })
        val message = inbox.messages().single()
        assertEquals("ntfy did not carry the push as binary", "base64", message.encoding)
        assertNull("the pushed body is readable as an event", pushEventFrom(message.body))
        assertFalse("the pushed body contains event JSON", message.body.toString(Charsets.ISO_8859_1).contains("\"type\""))
    }

    @Test
    fun approvalPushAndResolved() = runBlocking {
        val app = subscribed()
        val chat = app.newSession()
        // Never created: the request is denied, so nothing would be removed either way.
        val directory = File(E2eEnv.home, "e2e-push-${UUID.randomUUID()}")

        val (command, card) = chat.requestRemoval(directory)

        val approval = inbox.awaitEvent { it is PushEvent.Approval } as PushEvent.Approval
        assertEquals(PushEvent.Approval(chat.storedId(), approval.requestId, card.command), approval)
        chat.answerGuarded(command, card, ApprovalChoice.DENY)
        assertEquals(PushEvent.Resolved(chat.storedId(), approval.requestId), inbox.awaitEvent { it is PushEvent.Resolved })
        chat.awaitEndOfTurn()
    }

    @Test
    fun clarifyPush() = runBlocking {
        val app = subscribed()
        val chat = app.newSession()

        val card = chat.requestColourChoice()

        val clarify = inbox.awaitEvent { it is PushEvent.Clarify } as PushEvent.Clarify
        assertEquals(PushEvent.Clarify(chat.storedId(), clarify.requestId, card.questions.first().question), clarify)
        chat.answerClarify(card.requestId, mapOf(card.questions.first().qid to listOf("red")))
        assertEquals(PushEvent.Resolved(chat.storedId(), clarify.requestId), inbox.awaitEvent { it is PushEvent.Resolved })
        chat.awaitEndOfTurn()
    }

    @Test
    fun replyPushOnlyForFollowedSessions() = runBlocking {
        val app = subscribed()
        val followed = app.newSession()
        val unfollowed = app.newSession()
        app.push.follow(followed.storedId())

        // The unfollowed chat goes first: one sender sends in order, so its push would come before the followed one's.
        unfollowed.sendAndAwaitReply("Reply with exactly the word: pong", "pong")
        unfollowed.awaitEndOfTurn()
        followed.sendAndAwaitReply("Reply with exactly the word: pong", "pong")
        followed.awaitEndOfTurn()

        val reply = inbox.awaitEvent { it is PushEvent.Reply } as PushEvent.Reply
        assertEquals(followed.storedId(), reply.sessionId)
        assertTrue("reply push text: ${reply.text}", reply.text.contains("pong", ignoreCase = true))
        assertEquals(listOf(reply), inbox.events().filterIsInstance<PushEvent.Reply>())
    }

    @Test
    fun cronPushAndSilence() = runBlocking {
        val app = subscribed()
        val name = uniqueName("e2e-push-report")
        val job = app.createCronJob(name, REPORTING_JOB_PROMPT).also { createdJobs += it }

        val run = app.runOnce(job)

        val report = app.sessions.finalReply(run.sessionId)
        if (report?.contains("cron-ok") != true) throw AssertionError("job did not report as prompted (model misstep): $report")
        val pushed = inbox.awaitEvent { it is PushEvent.Cron } as PushEvent.Cron
        assertEquals(PushEvent.Cron(run.sessionId, job, name, pushed.text, failed = false), pushed)
        assertTrue("cron push text: ${pushed.text}", pushed.text.contains("cron-ok"))

        val silentJob = app.createCronJob(uniqueName("e2e-push-silent"), SILENT_JOB_PROMPT).also { createdJobs += it }
        val silentRun = app.runOnce(silentJob)
        val silentReply = app.sessions.finalReply(silentRun.sessionId)
        if (silentReply == null || !isSilent(silentReply)) throw AssertionError("job did not stay silent as prompted (model misstep): $silentReply")
        delay(30_000)
        assertEquals("pushes after the silent run", listOf<PushEvent>(pushed), inbox.events())
    }

    @Test
    fun unsubscribedGetsNothing() = runBlocking {
        val app = subscribed()

        app.push.unsubscribe()

        val error = runCatching { app.push.sendTest() }.exceptionOrNull()
        assertTrue("a test push without a subscription ended with $error", error is NotSubscribedException)
    }
}
