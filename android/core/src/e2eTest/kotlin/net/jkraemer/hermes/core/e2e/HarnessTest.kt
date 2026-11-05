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
import java.io.File
import java.util.Properties

/** Values the e2e setup script writes to the file named by HERMES_E2E_ENV. */
object E2eEnv {
    private val values: Map<String, String> by lazy {
        val path = requireNotNull(System.getenv("HERMES_E2E_ENV")) { "HERMES_E2E_ENV is not set" }
        val file = File(path)
        check(file.isFile) { "e2e environment file $path is missing; run e2e/hermes-e2e.sh up" }
        Properties().apply { file.reader().use { load(it) } }.entries.associate { it.key.toString() to it.value.toString() }
    }

    val url: String get() = value("HERMES_URL")

    private fun value(key: String) = requireNotNull(values[key]) { "$key missing from the e2e environment file" }
}

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
