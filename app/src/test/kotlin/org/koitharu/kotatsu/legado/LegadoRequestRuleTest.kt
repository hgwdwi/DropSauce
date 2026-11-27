package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LegadoRequestRuleTest {
    private fun source(search: String, header: String = "{}"): JsonObject =
        buildJsonObject {
            put("bookSourceUrl", "https://example.com")
            put("searchUrl", search)
            put("header", Json.parseToJsonElement(header))
        }

    @Test fun getAndPage() {
        val r = LegadoRequestRule.search(source("/search?q={{key}}&p={{page}}"), "书 名", 2)
        assertEquals("https://example.com/search?q=%E4%B9%A6%20%E5%90%8D&p=2", r.url)
        assertEquals("GET", r.method)
        assertNull(r.body)
    }

    @Test fun staticPostWithLegacyEncodingAndHeaders() {
        val r = LegadoRequestRule.search(source(
            """/search,{"method":"POST","body":"q={{key}}&p={{page}}","charset":"GBK","headers":{"Accept":"application/json"}}""",
            """{"User-Agent":"Fixture"}"""
        ), "中文", 3)
        assertEquals("POST", r.method)
        assertEquals("q=%D6%D0%CE%C4&p=3", r.body)
        assertEquals("Fixture", r.headers["user-agent"])
        assertEquals("application/json", r.headers["accept"])
        assertEquals("GBK", r.charset?.name())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCrossOrigin() {
        LegadoRequestRule.search(source("https://other.example/?q={{key}}"), "x", 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialHeaders() {
        LegadoRequestRule.search(source("/?q={{key}}", """{"Authorization":"secret"}"""), "x", 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDynamicOptions() {
        LegadoRequestRule.search(source("""/search?q={{key}},{"webView":true}"""), "x", 1)
    }
}
