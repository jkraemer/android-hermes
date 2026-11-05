package net.jkraemer.hermes.core.auth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import java.io.File

interface CookieStore {
    fun load(): List<Cookie>
    fun save(cookies: List<Cookie>)
}

/** Keeps cookies as JSON in [file]; writes go to a temp file first so a crash never leaves a torn file. */
class FileCookieStore(private val file: File) : CookieStore {
    @Serializable
    private class StoredCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    )

    override fun load(): List<Cookie> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        // A corrupt file must not stop the app from starting; the next save replaces it.
        return try {
            Json.decodeFromString<List<StoredCookie>>(text).map { it.toCookie() }
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    override fun save(cookies: List<Cookie>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(Json.encodeToString(cookies.map { it.toStored() }))
        java.nio.file.Files.move(
            temp.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun Cookie.toStored() =
        StoredCookie(name, value, expiresAt, domain, path, secure, httpOnly, hostOnly)

    private fun StoredCookie.toCookie(): Cookie {
        val builder = Cookie.Builder().name(name).value(value).expiresAt(expiresAt).path(path)
        if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }
}
