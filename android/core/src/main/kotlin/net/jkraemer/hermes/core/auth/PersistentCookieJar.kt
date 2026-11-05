package net.jkraemer.hermes.core.auth

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** A cookie jar that writes every change through to [store] and ignores expired cookies. */
class PersistentCookieJar(private val store: CookieStore) : CookieJar {
    private var cookies: List<Cookie> = store.load().filterNot { it.isExpired() }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val replaced = cookies.map { Triple(it.name, it.domain, it.path) }.toSet()
        this.cookies = (this.cookies.filterNot { Triple(it.name, it.domain, it.path) in replaced } + cookies)
            .filterNot { it.isExpired() }
        store.save(this.cookies)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { !it.isExpired() && it.matches(url) }

    @Synchronized
    fun clear() {
        cookies = emptyList()
        store.save(cookies)
    }

    private fun Cookie.isExpired() = expiresAt < System.currentTimeMillis()
}
