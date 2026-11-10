package net.jkraemer.hermes.push

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jkraemer.hermes.core.push.PushApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import net.jkraemer.hermes.core.push.NotificationRule
import net.jkraemer.hermes.core.push.PushNotification
import net.jkraemer.hermes.settings.NeedsAnswerStore
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.milliseconds

private object NoDistributor : PushDistributor {
    override fun useCurrentOrDefault(activity: Activity, onResult: (Boolean) -> Unit) = onResult(false)
    override fun pick(activity: Activity, onResult: (Boolean) -> Unit) = onResult(false)
    override fun register(context: Context, messageForDistributor: String) = Unit
}

/** Records what was shown and cancelled, as `show key CHANNEL title text session` and `cancel key`. */
private class RecordingNotifications : Notifications {
    val calls = mutableListOf<String>()

    override fun show(notification: PushNotification) {
        with(notification) { calls += "show $key $channel $title $text $sessionId" }
    }

    override fun cancel(key: String) {
        calls += "cancel $key"
    }
}

@RunWith(RobolectricTestRunner::class)
class PushHandlerTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registration = PushRegistration({ PushApi(server.url("/"), OkHttpClient()) }, NoDistributor, { true }, scope)
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("push-handler-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private var now = Instant.parse("2026-11-10T12:00:00Z")
    private val needsAnswer = NeedsAnswerStore(prefs) { now }
    private val chatsNeedingAnswer = MutableStateFlow(emptySet<String>())
    private val notifications = RecordingNotifications()
    private val logged = mutableListOf<String>()
    private var openSessionId: String? = null
    private var rule: NotificationRule = NotificationRule.Always
    private var fullReport: String? = null

    // Unconfined runs onMessage's work to completion before it returns, as long as nothing suspends for real.
    private val handler = PushHandler(
        registration, needsAnswer, chatsNeedingAnswer, notifications, { rule }, { openSessionId }, { fullReport }, CoroutineScope(Dispatchers.Unconfined),
        log = { logged += it }, clock = { now }, onMainThread = { false },
    )

    private fun approval(requestId: String) = """{"v":1,"type":"approval","session_id":"s1","request_id":"$requestId","text":"rm x"}"""

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun aNewEndpointIsSentWithItsKeys() {
        server.enqueue(MockResponse().setResponseCode(204))

        handler.onNewEndpoint(PushEndpoint("https://ntfy.sh/up1?up=1", PublicKeySet("pub", "auth"), temporary = false))

        runBlocking { withTimeout(10_000) { registration.status.first { it == PushStatus.On } } }
        assertEquals("""{"endpoint":"https://ntfy.sh/up1?up=1","p256dh":"pub","auth":"auth"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun aTemporaryEndpointIsStillSent() {
        server.enqueue(MockResponse().setResponseCode(204))

        handler.onNewEndpoint(PushEndpoint("https://ntfy.sh/up1?up=1", PublicKeySet("pub", "auth"), temporary = true))

        runBlocking { withTimeout(10_000) { registration.status.first { it == PushStatus.On } } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anEndpointWithoutKeysCannotReceiveEncryptedPushes() {
        handler.onNewEndpoint(PushEndpoint("https://ntfy.sh/up1?up=1", null, temporary = false))

        assertEquals(PushStatus.Failed("The distributor gave no encryption keys"), registration.status.value)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFailedRegistrationShowsItsReason() {
        handler.onRegistrationFailed(FailedReason.NETWORK)

        assertEquals(PushStatus.Failed("NETWORK"), registration.status.value)
    }

    @Test
    fun unregisteringIsNoDistributor() {
        server.enqueue(MockResponse().setResponseCode(204))

        handler.onUnregistered()

        runBlocking { withTimeout(10_000) { registration.status.first { it == PushStatus.NoDistributor } } }
    }

    @Test
    fun anUndecryptedMessageIsDropped() {
        handler.onMessage(approval("a1").toByteArray(), decrypted = false)

        assertEquals(emptyList<String>(), notifications.calls)
        assertEquals(emptySet<String>(), needsAnswer.sessions.value)
        assertEquals(listOf("push message dropped: not decrypted"), logged)
    }

    @Test
    fun aMessageThatIsNoKnownEventIsDropped() {
        handler.onMessage("""{"v":1,"type":"nope"}""".toByteArray(), decrypted = true)
        handler.onMessage("not json".toByteArray(), decrypted = true)

        assertEquals(emptyList<String>(), notifications.calls)
    }

    @Test
    fun anApprovalIsShownAndTheSessionNeedsAnAnswer() {
        handler.onMessage(approval("a1").toByteArray(), decrypted = true)

        assertEquals(listOf("show request:a1 APPROVALS Approval needed rm x s1"), notifications.calls)
        assertEquals(setOf("s1"), needsAnswer.sessions.value)
    }

    @Test
    fun aClarifyIsShownAndTheSessionNeedsAnAnswer() {
        handler.onMessage("""{"v":1,"type":"clarify","session_id":"s1","request_id":"c1","text":"Which?"}""".toByteArray(), decrypted = true)

        assertEquals(listOf("show request:c1 APPROVALS Hermes has a question Which? s1"), notifications.calls)
        assertEquals(setOf("s1"), needsAnswer.sessions.value)
    }

    @Test
    fun needsAnswerFollowsPushes() {
        handler.onMessage(approval("a1").toByteArray(), decrypted = true)
        handler.onMessage("""{"v":1,"type":"resolved","session_id":"s1","request_id":"a1"}""".toByteArray(), decrypted = true)

        assertEquals(listOf("show request:a1 APPROVALS Approval needed rm x s1", "cancel request:a1"), notifications.calls)
        assertEquals(emptySet<String>(), needsAnswer.sessions.value)
    }

    @Test
    fun aResolvedPushClearsItsWholeSessionFromNeedsAnswer() {
        chatsNeedingAnswer.value = setOf("s1", "s2")
        handler.onMessage(approval("a1").toByteArray(), decrypted = true)

        handler.onMessage("""{"v":1,"type":"resolved","session_id":"s1","request_id":"other-id"}""".toByteArray(), decrypted = true)

        assertEquals(emptySet<String>(), needsAnswer.sessions.value)
        assertEquals(setOf("s2"), chatsNeedingAnswer.value)
    }

    @Test
    fun offTheMainThreadAPushIsHandledBeforeOnMessageReturns() {
        val cancelled = CoroutineScope(SupervisorJob()).also { it.cancel() }
        val offMain = PushHandler(registration, needsAnswer, chatsNeedingAnswer, notifications, { rule }, { openSessionId }, { fullReport }, cancelled, onMainThread = { false })

        offMain.onMessage(approval("a1").toByteArray(), decrypted = true)

        assertEquals(listOf("show request:a1 APPROVALS Approval needed rm x s1"), notifications.calls)
    }

    @Test
    fun onTheMainThreadAPushIsHandledInTheAppScope() {
        val onMain = PushHandler(registration, needsAnswer, chatsNeedingAnswer, notifications, { rule }, { openSessionId }, { fullReport }, scope, onMainThread = { true })

        onMain.onMessage(approval("a1").toByteArray(), decrypted = true)

        runBlocking { withTimeout(10_000) { while (notifications.calls.isEmpty()) delay(10) } }
        assertEquals(listOf("show request:a1 APPROVALS Approval needed rm x s1"), notifications.calls)
    }

    @Test
    fun anApprovalExpiresAfterFiveMinutesAndAQuestionAfterAnHour() {
        handler.onMessage(approval("a1").toByteArray(), decrypted = true)
        handler.onMessage("""{"v":1,"type":"clarify","session_id":"s2","request_id":"c1","text":"Which?"}""".toByteArray(), decrypted = true)

        now = now.plusSeconds(299)
        needsAnswer.dropExpired()
        assertEquals(setOf("s1", "s2"), needsAnswer.sessions.value)
        now = now.plusSeconds(2)
        needsAnswer.dropExpired()
        assertEquals(setOf("s2"), needsAnswer.sessions.value)
        now = now.plusSeconds(3600 - 301)
        needsAnswer.dropExpired()
        assertEquals(emptySet<String>(), needsAnswer.sessions.value)
    }

    @Test
    fun aReplyIsShownUnlessItsChatIsOnScreen() {
        val reply = """{"v":1,"type":"reply","session_id":"s1","text":"Done."}""".toByteArray()

        handler.onMessage(reply, decrypted = true)
        openSessionId = "s1"
        handler.onMessage(reply, decrypted = true)

        assertEquals(listOf("show session:s1 REPLIES Hermes replied Done. s1"), notifications.calls)
    }

    @Test
    fun aCronReportFollowsItsJobsRule() {
        val cron = """{"v":1,"type":"cron","session_id":"s9","job_id":"j1","job_name":"Nightly","text":"preview"}""".toByteArray()

        handler.onMessage(cron, decrypted = true)
        rule = NotificationRule.Off
        handler.onMessage(cron, decrypted = true)

        assertEquals(listOf("show session:s9 CRON Nightly preview s9"), notifications.calls)
    }

    @Test
    fun aMatchRuleTestsTheFullReport() {
        rule = NotificationRule.Match("ALERT")
        fullReport = "all fine ... ALERT disk full"

        handler.onMessage("""{"v":1,"type":"cron","session_id":"s9","job_id":"j1","job_name":"Nightly","text":"all fine"}""".toByteArray(), decrypted = true)

        assertEquals(listOf("show session:s9 CRON Nightly all fine s9"), notifications.calls)
    }

    @Test
    fun aTestEventShowsANotificationThatOpensNoSession() {
        handler.onMessage("""{"v":1,"type":"test"}""".toByteArray(), decrypted = true)

        assertEquals(listOf("show test REPLIES Hermes Notifications work. null"), notifications.calls)
    }

    @Test
    fun aReportThatTakesTooLongToFetchFallsBackToThePreview() {
        rule = NotificationRule.Match("ALERT")
        fullReport = null
        val slow = PushHandler(registration, needsAnswer, chatsNeedingAnswer, notifications, { rule }, { openSessionId }, { awaitCancellation() }, scope, onMainThread = { true }, fullOutputTimeout = 50.milliseconds)

        slow.onMessage("""{"v":1,"type":"cron","session_id":"s9","job_id":"j1","job_name":"Nightly","text":"ALERT in preview"}""".toByteArray(), decrypted = true)

        runBlocking { withTimeout(10_000) { while (notifications.calls.isEmpty()) delay(10) } }
        assertEquals(listOf("show session:s9 CRON Nightly ALERT in preview s9"), notifications.calls)
    }
}
