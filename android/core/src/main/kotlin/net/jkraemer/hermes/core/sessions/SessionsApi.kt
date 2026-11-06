package net.jkraemer.hermes.core.sessions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.jkraemer.hermes.core.auth.NotLoggedInException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID

private const val LAST_MESSAGES_LIMIT = 5

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

    private fun Double.toInstant(): Instant = Instant.ofEpochMilli((this * 1000).toLong())
}

@Serializable
private data class ListResponse(val sessions: List<SessionRow>, val total: Int = 0)

@Serializable
private data class SearchResponse(val results: List<SessionRow>)

/** Session list, search and sidebar updates. [client] must carry the dashboard session cookies. */
class SessionsApi(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonMediaType = "application/json".toMediaType()
    private val excludedSources = EXCLUDED_SOURCES.joinToString(",")

    suspend fun list(offset: Int = 0, limit: Int = 50): SessionPage = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().addPathSegments("api/sessions")
            .addQueryParameter("order", "recent")
            .addQueryParameter("archived", "exclude")
            .addQueryParameter("exclude_sources", excludedSources)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val response = json.decodeFromString<ListResponse>(get(url))
        SessionPage(response.sessions.map { it.toSummary() }, response.total)
    }

    suspend fun search(query: String, limit: Int = 20): List<SessionSummary> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val url = baseUrl.newBuilder().addPathSegments("api/sessions/search")
                .addQueryParameter("q", query)
                .addQueryParameter("exclude_sources", excludedSources)
                .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                .build()
            json.decodeFromString<SearchResponse>(get(url)).results.map { it.toSummary() }
        }
    }

    /**
     * The text of the session's most recent assistant reply, or null when it has none. Only the
     * newest few messages are fetched: the reply is at the end, behind at most some tool traffic.
     */
    suspend fun lastAssistantText(id: String): String? = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/sessions").addPathSegment(id).addPathSegment("messages")
            .addQueryParameter("order", "latest")
            .addQueryParameter("limit", LAST_MESSAGES_LIMIT.toString())
            .build()
        Json.parseToJsonElement(get(url)).jsonObject.getValue("messages").jsonArray
            .map { it.jsonObject }
            .lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" && !it.text().isNullOrBlank() }
            ?.text()
    }

    private fun JsonObject.text(): String? = (this["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content

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
            execute(request)
            Unit
        }

    private fun get(url: HttpUrl): String = execute(Request.Builder().url(url).build())

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw NotLoggedInException()
            check(response.isSuccessful) { "${request.method} ${request.url.encodedPath} failed with ${response.code}" }
            response.body!!.string()
        }
}
