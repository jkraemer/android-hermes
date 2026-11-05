package net.jkraemer.hermes.core.e2e

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
    val user: String get() = value("HERMES_USER")
    val password: String get() = value("HERMES_PASSWORD")

    private fun value(key: String) = requireNotNull(values[key]) { "$key missing from the e2e environment file" }
}
