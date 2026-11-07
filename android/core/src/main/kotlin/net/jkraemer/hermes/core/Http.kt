package net.jkraemer.hermes.core

import net.jkraemer.hermes.core.auth.NotLoggedInException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Runs [request] against the dashboard and returns the response body. 401 and 403 mean the login is gone
 * ([NotLoggedInException]); any other failure status throws [IllegalStateException]. The response is always
 * closed, so its connection goes back to the pool.
 */
internal fun OkHttpClient.bodyOf(request: Request): String =
    newCall(request).execute().use { response ->
        if (response.code == 401 || response.code == 403) throw NotLoggedInException()
        check(response.isSuccessful) { "${request.method} ${request.url.encodedPath} failed with ${response.code}" }
        response.body!!.string()
    }
