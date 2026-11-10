package net.jkraemer.hermes.core.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the plugin tells the app, decrypted from one push message. */
sealed interface PushEvent {
    /** A cron run finished with a report; [text] is only its preview. [failed] when the run reported a failure. */
    data class Cron(val sessionId: String, val jobId: String, val jobName: String, val text: String, val failed: Boolean) : PushEvent

    /** Hermes wants permission to run [text], a command. */
    data class Approval(val sessionId: String, val requestId: String, val text: String) : PushEvent

    /** Hermes asks the question [text]. */
    data class Clarify(val sessionId: String, val requestId: String, val text: String) : PushEvent

    /** The approval or question [requestId] was answered, wherever that happened. */
    data class Resolved(val sessionId: String, val requestId: String) : PushEvent

    /** Hermes finished a reply in an app chat; [text] is only its preview. */
    data class Reply(val sessionId: String, val text: String) : PushEvent

    /** Asked for from the app to check that notifications arrive. */
    data object Test : PushEvent
}

private val json = Json { isLenient = true; ignoreUnknownKeys = true }

private const val PROTOCOL_VERSION = 1

/** The event in a push payload, or null when it is not valid UTF-8 JSON of a known event of this protocol version. */
fun pushEventFrom(bytes: ByteArray): PushEvent? = runCatching {
    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    val fields = json.parseToJsonElement(text).jsonObject
    if (fields.string("v") != PROTOCOL_VERSION.toString()) return null
    fields.event()
}.getOrNull()

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.event(): PushEvent? {
    val sessionId = string("session_id")
    val requestId = string("request_id")
    val text = string("text")
    return when (string("type")) {
        "cron" -> {
            val jobId = string("job_id")
            if (sessionId == null || jobId == null || text == null) null
            else PushEvent.Cron(sessionId, jobId, string("job_name") ?: jobId, text, this["failed"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        "approval" -> if (sessionId == null || requestId == null || text == null) null else PushEvent.Approval(sessionId, requestId, text)
        "clarify" -> if (sessionId == null || requestId == null || text == null) null else PushEvent.Clarify(sessionId, requestId, text)
        "resolved" -> if (sessionId == null || requestId == null) null else PushEvent.Resolved(sessionId, requestId)
        "reply" -> if (sessionId == null || text == null) null else PushEvent.Reply(sessionId, text)
        "test" -> PushEvent.Test
        else -> null
    }
}
