package net.jkraemer.hermes.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface LoginResult {
    data object Ok : LoginResult
    data object BadCredentials : LoginResult
    data object RateLimited : LoginResult
    data class Unreachable(val cause: Throwable) : LoginResult
}

class NotLoggedInException : Exception("The Hermes session is missing or expired")

/**
 * Dashboard session handling. [client] must use the [PersistentCookieJar] that holds the
 * session cookies.
 */
class HermesAuth(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("provider", "basic")
            put("username", username)
            put("password", password)
        }.toString()
        val request = Request.Builder().url(baseUrl.resolve("auth/password-login")!!)
            .post(body.toRequestBody(jsonMediaType)).build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> LoginResult.Ok
                    401 -> LoginResult.BadCredentials
                    429 -> LoginResult.RateLimited
                    else -> LoginResult.Unreachable(IOException("Unexpected login response ${response.code}"))
                }
            }
        } catch (e: IOException) {
            LoginResult.Unreachable(e)
        }
    }

    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.resolve("api/auth/me")!!).build()
        client.newCall(request).execute().use { it.code == 200 }
    }

    /** Throws [NotLoggedInException] when the session is gone. */
    suspend fun mintWsTicket(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.resolve("api/auth/ws-ticket")!!)
            .post("".toRequestBody(null)).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw NotLoggedInException()
            check(response.isSuccessful) { "ws-ticket request failed with ${response.code}" }
            Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("ticket").jsonPrimitive.content
        }
    }

    fun logout() {
        (client.cookieJar as PersistentCookieJar).clear()
    }
}
