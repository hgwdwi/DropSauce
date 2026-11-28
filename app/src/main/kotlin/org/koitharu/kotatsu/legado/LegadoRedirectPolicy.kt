package org.koitharu.kotatsu.legado

import okhttp3.HttpUrl

/** Redirect policy shared by the reader and unit tests. */
internal object LegadoRedirectPolicy {
    data class Target(val url: HttpUrl, val method: String, val body: String?)

    fun isSameOrigin(origin: HttpUrl, url: HttpUrl): Boolean =
        url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port &&
            url.username.isEmpty() && url.password.isEmpty()

    fun resolve(
        origin: HttpUrl,
        current: HttpUrl,
        status: Int,
        location: String?,
        method: String,
        body: String?,
    ): Target {
        require(status in REDIRECT_CODES) { "Unsupported redirect status" }
        val next = location?.let(current::resolve) ?: error("Redirect response has no valid Location")
        require(isSameOrigin(origin, next)) { "Cross-origin redirect is not supported" }
        val switchToGet = status == 303 || ((status == 301 || status == 302) && method == "POST")
        return Target(next, if (switchToGet) "GET" else method, if (switchToGet) null else body)
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
