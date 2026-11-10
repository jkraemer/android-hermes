package net.jkraemer.hermes.push

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.jkraemer.hermes.core.HermesLog
import net.jkraemer.hermes.core.push.NotificationRule
import net.jkraemer.hermes.core.push.PushAction
import net.jkraemer.hermes.core.push.PushEvent
import net.jkraemer.hermes.core.push.decide
import net.jkraemer.hermes.core.push.pushEventFrom
import net.jkraemer.hermes.settings.NeedsAnswerStore
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * What [HermesPushService] does with the distributor's events; the service itself needs the connector's KeyStore code.
 * [openSessionId] is the stored id of the chat on screen and [fullOutput] fetches a cron run's complete report.
 * The connector normally calls [onMessage] on its own thread while it holds a wake lock for about 10 s, so off the
 * main thread a push is handled to the end before [onMessage] returns (the report fetch is bounded by
 * [fullOutputTimeout]). The first message after the process started arrives on the main thread, after the wake lock
 * is gone: there it is handled in [scope], best effort, and [onMessage] returns at once.
 */
class PushHandler(
    private val registration: PushRegistration,
    private val needsAnswer: NeedsAnswerStore,
    private val chatsNeedingAnswer: MutableStateFlow<Set<String>>,
    private val notifications: Notifications,
    private val ruleFor: (jobId: String) -> NotificationRule,
    private val openSessionId: () -> String?,
    private val fullOutput: suspend (sessionId: String) -> String?,
    private val scope: CoroutineScope,
    private val log: HermesLog = HermesLog.None,
    private val clock: () -> Instant = Instant::now,
    private val onMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val fullOutputTimeout: kotlin.time.Duration = 8.seconds,
) {
    /** The endpoint is sent even when it is temporary: the distributor may take long to get a permanent one. */
    fun onNewEndpoint(endpoint: PushEndpoint) {
        val keys = endpoint.pubKeySet
        if (keys == null) {
            registration.onRegistrationFailed("The distributor gave no encryption keys")
        } else {
            registration.onEndpoint(endpoint.url, keys.pubKey, keys.auth)
        }
    }

    fun onRegistrationFailed(reason: FailedReason) = registration.onRegistrationFailed(reason.name)

    fun onUnregistered() = registration.onUnregistered()

    /**
     * Shows or cancels the notification the push [content] asks for, and keeps "Needs answer" current. Anyone who knows
     * the topic URL can post to it, so what the connector could not decrypt is dropped.
     */
    fun onMessage(content: ByteArray, decrypted: Boolean) {
        if (!decrypted) return log.log("push message dropped: not decrypted")
        val event = pushEventFrom(content) ?: return log.log("push message dropped: not a known event")
        if (onMainThread()) scope.launch { handle(event) } else runBlocking { handle(event) }
    }

    private suspend fun handle(event: PushEvent) {
        when (event) {
            is PushEvent.Approval -> needsAnswer.add(event.sessionId, event.requestId, clock() + APPROVAL_TIMEOUT)
            is PushEvent.Clarify -> needsAnswer.add(event.sessionId, event.requestId, clock() + NeedsAnswerStore.CLARIFY_TIMEOUT)
            // Hermes blocks a turn on one human-input request at a time, so a resolved one leaves nothing else open in the session.
            is PushEvent.Resolved -> {
                needsAnswer.clearSession(event.sessionId)
                chatsNeedingAnswer.update { it - event.sessionId }
            }
            else -> Unit
        }
        // The report is only needed for a rule that looks into it, and a slow server must not hold the notification back.
        val action = decide(event, ruleFor, openSessionId()) { withTimeoutOrNull(fullOutputTimeout) { fullOutput(it) } }
        when (action) {
            is PushAction.Show -> notifications.show(action.notification)
            is PushAction.Cancel -> notifications.cancel(action.key)
            PushAction.Nothing -> Unit
        }
    }

    private companion object {
        // How long Hermes waits for an approval before it gives up: its default timeout.
        val APPROVAL_TIMEOUT: Duration = Duration.ofSeconds(300)
    }
}
