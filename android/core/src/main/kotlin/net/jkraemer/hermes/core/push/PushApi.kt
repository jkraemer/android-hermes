package net.jkraemer.hermes.core.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.jkraemer.hermes.core.bodyOf
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** The dashboard has no hermes-app plugin routes: the plugin is not installed or not enabled. */
class PluginMissingException : Exception("The hermes-app plugin is not installed on the Hermes server")

/** The plugin has no subscription to push to. */
class NotSubscribedException : Exception("The hermes-app plugin has no push subscription")

@Serializable
private data class SubscriptionBody(val endpoint: String, val p256dh: String, val auth: String)

/** The hermes-app plugin's push routes. [client] must carry the dashboard session cookies. */
class PushApi(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    /** Makes the plugin push to [endpoint], encrypted for the keys [p256dh] and [auth], replacing any earlier subscription. */
    suspend fun subscribe(endpoint: String, p256dh: String, auth: String) {
        val body = Json.encodeToString(SubscriptionBody(endpoint, p256dh, auth)).toRequestBody(JSON)
        send(Request.Builder().url(url("subscription")).put(body).build())
    }

    suspend fun unsubscribe() {
        send(Request.Builder().url(url("subscription")).delete().build())
    }

    /** Tells the plugin the session is an app chat, so its replies are pushed. */
    suspend fun follow(sessionId: String) {
        send(Request.Builder().url(url("followed", sessionId)).put(EMPTY).build())
    }

    /** Asks the plugin to push a test event; [NotSubscribedException] when there is no subscription to push to. */
    suspend fun sendTest() {
        send(Request.Builder().url(url("test")).post(EMPTY).build()) { if (it == 409) throw NotSubscribedException() }
    }

    private fun url(vararg segments: String): HttpUrl =
        baseUrl.newBuilder().addPathSegments("api/plugins/hermes-app").apply { segments.forEach(::addPathSegment) }.build()

    private suspend fun send(request: Request, onFailure: (Int) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            client.bodyOf(request) {
                if (it == 404) throw PluginMissingException()
                onFailure(it)
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY: RequestBody = ByteArray(0).toRequestBody()
    }
}
