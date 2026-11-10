package net.jkraemer.hermes.core.push

import kotlin.coroutines.cancellation.CancellationException

enum class Channel { CRON, APPROVALS, REPLIES }

/** A notification to show. The notification with the same [key] is replaced, so one thing never shows twice. */
data class PushNotification(val key: String, val channel: Channel, val title: String, val text: String, val sessionId: String?)

sealed interface PushAction {
    data class Show(val notification: PushNotification) : PushAction

    /** Remove the notification with this key. */
    data class Cancel(val key: String) : PushAction
    data object Nothing : PushAction
}

private fun sessionKey(sessionId: String) = "session:$sessionId"
private fun requestKey(requestId: String) = "request:$requestId"

/**
 * What to do about [event]. [ruleFor] gives a cron job's rule; [openSessionId] is the chat on screen, whose replies
 * need no notification. [fullOutput] fetches a cron run's complete report for a [NotificationRule.Match]; when it
 * cannot, the rule is tested against the preview.
 */
suspend fun decide(
    event: PushEvent,
    ruleFor: (jobId: String) -> NotificationRule,
    openSessionId: String?,
    fullOutput: suspend (sessionId: String) -> String?,
): PushAction = when (event) {
    is PushEvent.Cron -> {
        val show = when (val rule = ruleFor(event.jobId)) {
            NotificationRule.Off -> false
            NotificationRule.Always -> true
            is NotificationRule.Match -> rule.allows(fullOutputOrNull(event.sessionId, fullOutput) ?: event.text)
        }
        if (show) {
            val title = if (event.failed) "${event.jobName} failed" else event.jobName
            PushAction.Show(PushNotification(sessionKey(event.sessionId), Channel.CRON, title, event.text, event.sessionId))
        } else PushAction.Nothing
    }
    is PushEvent.Approval ->
        PushAction.Show(PushNotification(requestKey(event.requestId), Channel.APPROVALS, "Approval needed", event.text, event.sessionId))
    is PushEvent.Clarify ->
        PushAction.Show(PushNotification(requestKey(event.requestId), Channel.APPROVALS, "Hermes has a question", event.text, event.sessionId))
    is PushEvent.Resolved -> PushAction.Cancel(requestKey(event.requestId))
    is PushEvent.Reply ->
        if (event.sessionId == openSessionId) PushAction.Nothing
        else PushAction.Show(PushNotification(sessionKey(event.sessionId), Channel.REPLIES, "Hermes replied", event.text, event.sessionId))
    PushEvent.Test -> PushAction.Show(PushNotification("test", Channel.REPLIES, "Hermes", "Notifications work.", null))
}

private suspend fun fullOutputOrNull(sessionId: String, fullOutput: suspend (String) -> String?): String? =
    try {
        fullOutput(sessionId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
