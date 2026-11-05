package net.jkraemer.hermes.core.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTest {
    // Redirects stay visible so an unauthenticated request cannot be mistaken for a served page.
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    @Test
    fun providersEndpointAnswers() {
        client.newCall(Request.Builder().url("${E2eEnv.url}/api/auth/providers").build()).execute().use { response ->
            assertEquals(200, response.code)
            val providers = Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("providers").jsonArray
            val names = providers.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue("providers were $names", "basic" in names)
        }
    }

    @Test
    fun authGateIsEngaged() {
        client.newCall(Request.Builder().url("${E2eEnv.url}/api/sessions").build()).execute().use { response ->
            assertNotEquals(200, response.code)
            val location = response.header("Location")
            assertTrue(
                "expected 401 or a redirect to /login, got ${response.code} -> $location",
                response.code == 401 || (response.isRedirect && location!!.contains("/login")),
            )
        }
    }
}
