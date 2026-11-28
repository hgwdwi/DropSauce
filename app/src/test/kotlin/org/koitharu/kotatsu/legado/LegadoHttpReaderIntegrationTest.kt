package org.koitharu.kotatsu.legado

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real reader HTTP stack against a local deterministic server.
 * No public website, credentials, or external network access is involved.
 */
class LegadoHttpReaderIntegrationTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchSendsGetAndParsesBookResult() = runBlocking {
        server.enqueue(htmlResponse())
        val reader = LegadoHttpReader(source())

        val books = reader.search("first")

        assertEquals(1, books.size)
        assertEquals("Example Novel", books.single().title)
        assertEquals(server.url("/book/1").toString(), books.single().url)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/search?q=first", request.path)
    }

    @Test
    fun responseCookieIsSentOnTheNextRequest() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("Set-Cookie", "sid=abc123; Path=/; HttpOnly")
                .body(BOOK_HTML)
                .build(),
        )
        server.enqueue(htmlResponse())
        val reader = LegadoHttpReader(source())

        reader.search("first")
        reader.search("second")

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertNull(first.getHeader("Cookie"))
        assertEquals("sid=abc123", second.getHeader("Cookie"))
    }

    @Test
    fun sameOriginRedirectIsFollowed() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/landing")
                .build(),
        )
        server.enqueue(htmlResponse())
        val reader = LegadoHttpReader(source())

        val books = reader.search("redirect")

        assertEquals("Example Novel", books.single().title)
        assertEquals("/search?q=redirect", server.takeRequest().path)
        assertEquals("/landing", server.takeRequest().path)
    }

    @Test
    fun post302RedirectSwitchesToGet() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/results")
                .build(),
        )
        server.enqueue(htmlResponse())
        val reader = LegadoHttpReader(source("""/search,{"method":"POST","body":"q={{key}}"}"""))

        reader.search("hello")

        val post = server.takeRequest()
        val redirected = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("q=hello", post.body.readUtf8())
        assertEquals("GET", redirected.method)
        assertEquals("/results", redirected.path)
    }

    @Test
    fun post307RedirectPreservesMethodAndBody() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(307)
                .addHeader("Location", "/retry")
                .build(),
        )
        server.enqueue(htmlResponse())
        val reader = LegadoHttpReader(source("""/search,{"method":"POST","body":"q={{key}}"}"""))

        reader.search("hello")

        val post = server.takeRequest()
        val redirected = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("q=hello", post.body.readUtf8())
        assertEquals("POST", redirected.method)
        assertEquals("q=hello", redirected.body.readUtf8())
        assertEquals("/retry", redirected.path)
    }

    @Test
    fun crossOriginRedirectIsRejectedBeforeSecondRequest() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://other.example/collect")
                .build(),
        )
        val reader = LegadoHttpReader(source())

        val failure = runCatching { reader.search("blocked") }.exceptionOrNull()

        assertTrue("Expected cross-origin redirect rejection, got $failure", failure is IllegalArgumentException)
        assertEquals(1, server.requestCount)
    }

    private fun source(searchUrl: String = "/search?q={{key}}"): LegadoSourcePreflight.Inspection {
        val json = buildJsonObject {
            put("bookSourceName", "Local Mock Source")
            put("bookSourceUrl", server.url("/").toString())
            put("searchUrl", searchUrl)
            put(
                "ruleSearch",
                buildJsonObject {
                    put("bookList", "@css:.book")
                    put("name", "@css:h2@text")
                    put("bookUrl", "@css:a@href")
                },
            )
            put(
                "ruleToc",
                buildJsonObject {
                    put("chapterList", "@css:.chapter")
                    put("chapterName", "@css:a@text")
                    put("chapterUrl", "@css:a@href")
                },
            )
            put("ruleContent", buildJsonObject { put("content", "@css:#content@html") })
        }
        return LegadoSourcePreflight.inspect(json.toString()).single()
    }

    private fun htmlResponse(): MockResponse = MockResponse.Builder()
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(BOOK_HTML)
        .build()

    private companion object {
        const val BOOK_HTML =
            """<div class="book"><h2>Example Novel</h2><a href="/book/1">Read</a></div>"""
    }
}
