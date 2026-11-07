package net.jkraemer.hermes.core.sessions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.bodyOf
import net.jkraemer.hermes.core.chat.cronRunEnd
import net.jkraemer.hermes.core.chat.isOwnUserMessage
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID

@Serializable
private data class SessionRow(
    val id: String,
    @SerialName("resolved_id") val resolvedId: String? = null,
    val title: String? = null,
    val preview: String? = null,
    val source: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val snippet: String? = null,
) {
    fun toSummary(): SessionSummary {
        val started = startedAt ?: 0.0
        return SessionSummary(
            id = resolvedId ?: id,
            title = title,
            preview = preview,
            source = source.orEmpty(),
            startedAt = started.toInstant(),
            lastActive = (lastActive ?: started).toInstant(),
            messageCount = messageCount,
            pinned = pinned,
            archived = archived,
        )
    }

    fun toHit() = SearchHit(toSummary(), snippet?.let(::parseSnippet))

    private fun Double.toInstant(): Instant = Instant.ofEpochMilli((this * 1000).toLong())
}

@Serializable
private data class ListResponse(val sessions: List<SessionRow>, val total: Int = 0)

@Serializable
private data class SearchResponse(val results: List<SessionRow>)

/** A transcript message; [content] is a list of parts for a message that is not plain text. */
@Serializable
private data class MessageRow(
    val role: String? = null,
    val text: String? = null,
    val content: JsonElement? = null,
    @SerialName("display_kind") val displayKind: String? = null,
) {
    /** What the transcript shows, as the chat reads it: [text], else plain-text [content]. */
    val displayText: String?
        get() = text ?: (content as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Whether the user wrote it, rather than Hermes in the user's place. */
    val isUsersOwn: Boolean get() = role == "user" && isOwnUserMessage(displayKind)
}

@Serializable
private data class MessagesResponse(val messages: List<MessageRow>)

/** Session list, search and sidebar updates. [client] must carry the dashboard session cookies. */
class SessionsApi(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonMediaType = "application/json".toMediaType()
    private val excludedSources = EXCLUDED_SOURCES.joinToString(",")

    /** Cron runs are listed through their jobs, so the list leaves them out; search still finds them. */
    private val unlistedSources = (EXCLUDED_SOURCES + "cron").joinToString(",")

    suspend fun list(offset: Int = 0, limit: Int = 50): SessionPage = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegments("api/sessions")
            .addQueryParameter("order", "recent")
            .addQueryParameter("archived", "exclude")
            .addQueryParameter("exclude_sources", unlistedSources)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val response = json.decodeFromString<ListResponse>(get(url))
        SessionPage(response.sessions.map { it.toSummary() }, response.total)
    }

    suspend fun search(query: String, limit: Int = 20): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val url = baseUrl.newBuilder().addPathSegments("api/sessions/search")
                .addQueryParameter("q", query)
                .addQueryParameter("exclude_sources", excludedSources)
                .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                .build()
            json.decodeFromString<SearchResponse>(get(url)).results.map { it.toHit() }
        }
    }

    /**
     * Hermes fences every session mutation with a request id and the session's current
     * revision, so this reads the revision first and sends only the given fields besides.
     */
    suspend fun update(id: String, title: String? = null, pinned: Boolean? = null, archived: Boolean? = null) =
        withContext(Dispatchers.IO) {
            val snapshotUrl = baseUrl.newBuilder()
                .addPathSegments("api/sessions").addPathSegment(id).addPathSegment("mutation-snapshot").build()
            val revision = Json.parseToJsonElement(get(snapshotUrl))
                .jsonObject.getValue("runtime_revision").jsonPrimitive.int
            val body = buildJsonObject {
                put("request_id", UUID.randomUUID().toString())
                put("expected_revision", revision)
                title?.let { put("title", it) }
                pinned?.let { put("pinned", it) }
                archived?.let { put("archived", it) }
            }.toString()
            val url = baseUrl.newBuilder().addPathSegments("api/sessions").addPathSegment(id).build()
            val request = Request.Builder().url(url).patch(body.toRequestBody(jsonMediaType)).build()
            client.bodyOf(request)
            Unit
        }

    /**
     * A cron run's report: the text of its last assistant message before the user's first own message (the first
     * one is the job's prompt), so a later answer to the user does not count. Null when there is none. The latest
     * few messages are the whole transcript of a short run; a longer one is read from its start, since an answer
     * to the user can fill the latest page with tool calls alone.
     */
    suspend fun finalReply(id: String): String? = withContext(Dispatchers.IO) {
        val latest = messages(id, "latest", FINAL_REPLY_PAGE, offset = 0)
        val run = if (latest.size < FINAL_REPLY_PAGE) latest.take(cronRunEnd(latest, MessageRow::isUsersOwn)) else runFromStart(id)
        run.lastOrNull { it.role == "assistant" && !it.displayText.isNullOrBlank() }?.displayText
    }

    /** The run's messages from the start of its session up to the user's first own message, read page by page. */
    private fun runFromStart(id: String): List<MessageRow> {
        val read = mutableListOf<MessageRow>()
        while (true) {
            val page = messages(id, "oldest", RUN_PAGE, offset = read.size)
            read += page
            val end = cronRunEnd(read, MessageRow::isUsersOwn)
            if (end < read.size || page.size < RUN_PAGE) return read.take(end)
        }
    }

    /** A page of the session's messages, in chronological order; images are left out, as only text is read. */
    private fun messages(id: String, order: String, limit: Int, offset: Int): List<MessageRow> {
        val url = baseUrl.newBuilder().addPathSegments("api/sessions").addPathSegment(id).addPathSegment("messages")
            .addQueryParameter("order", order)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("inline_images", "false")
            .build()
        return json.decodeFromString<MessagesResponse>(get(url)).messages
    }

    private fun get(url: HttpUrl): String = client.bodyOf(Request.Builder().url(url).build())

    private companion object {
        const val FINAL_REPLY_PAGE = 10
        const val RUN_PAGE = 50
    }
}
