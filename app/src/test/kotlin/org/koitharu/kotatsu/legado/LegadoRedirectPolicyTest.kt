package org.koitharu.kotatsu.legado

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LegadoRedirectPolicyTest {
    private val origin = "https://example.com/".toHttpUrl()
    private val current = "https://example.com/search".toHttpUrl()

    @Test fun post302SwitchesToGet() {
        val target = LegadoRedirectPolicy.resolve(origin, current, 302, "/results", "POST", "q=abc")
        assertEquals("https://example.com/results", target.url.toString())
        assertEquals("GET", target.method)
        assertNull(target.body)
    }

    @Test fun post307PreservesMethodAndBody() {
        val target = LegadoRedirectPolicy.resolve(origin, current, 307, "/results", "POST", "q=abc")
        assertEquals("POST", target.method)
        assertEquals("q=abc", target.body)
    }

    @Test fun relativeAndQueryRedirectsResolveAgainstCurrentUrl() {
        val relative = LegadoRedirectPolicy.resolve(origin, current, 301, "page/2", "GET", null)
        val query = LegadoRedirectPolicy.resolve(origin, current, 303, "?page=2", "GET", null)
        assertEquals("https://example.com/page/2", relative.url.toString())
        assertEquals("https://example.com/search?page=2", query.url.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCrossOriginRedirects() {
        LegadoRedirectPolicy.resolve(origin, current, 302, "https://other.example/login", "GET", null)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingLocation() {
        LegadoRedirectPolicy.resolve(origin, current, 302, null, "GET", null)
    }
}
