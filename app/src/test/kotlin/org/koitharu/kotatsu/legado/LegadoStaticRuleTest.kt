package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class LegadoStaticRuleTest {
    @Test fun defaultChainsAndRelativeExtraction() {
        val root = Jsoup.parse("<div class='book'><a href='/one'>甲</a><a href='/two'>乙</a></div>")
        assertEquals(listOf("/two"), LegadoStaticRule.parse("class.book@tag.a.-1@href", false).values(root))
        val item = LegadoStaticRule.parse("class.book", true).elements(root).single()
        assertEquals(listOf("甲", "乙"), LegadoStaticRule.parse("tag.a@text", false).values(item))
    }

    @Test fun xpathSelectionAndAttribute() {
        val root = Jsoup.parse("<div class='book'><a href='/one'>甲</a></div>")
        val row = LegadoStaticRule.parse("@XPath://div[@class='book']", true).elements(root).single()
        assertEquals(listOf("/one"), LegadoStaticRule.parse("@XPath:.//a/@href", false).values(row))
    }

    @Test fun jsonPathListAndRelativeFields() {
        val root = Json.parseToJsonElement("""{"books":[{"name":"甲","url":"/one"},{"name":"乙","url":"/two"}]}""")
        val rows = LegadoStaticRule.parse("@Json:$.books[*]", true).elements(root)
        assertEquals(2, rows.size)
        assertEquals(listOf("乙"), LegadoStaticRule.parse("$.name", false).values(rows[1]))
        assertEquals(listOf("/two"), LegadoStaticRule.parse("$.books[-1].url", false).values(root))
    }

    @Test fun fallbackAndConcatenation() {
        val root = Jsoup.parse("<h1>甲</h1><h2>乙</h2>")
        assertEquals(listOf("甲"), LegadoStaticRule.parse("@css:.absent@text||@css:h1@text", false).values(root))
        assertEquals(listOf("甲", "乙"), LegadoStaticRule.parse("@css:h1@text&&@css:h2@text", false).values(root))
    }

    @Test fun linearRegexReplacementAndPagination() = kotlinx.coroutines.runBlocking {
        val root = Jsoup.parse("<p>小说广告123</p>")
        assertEquals(listOf("小说"), LegadoStaticRule.parse("@css:p@text##广告[0-9]+##", false).values(root))
        val pages = LegadoPagination.collect("one", limit = 3,
            fetch = { it }, extract = { page, _ -> page },
            next = { _, url -> if (url == "one") listOf("two") else listOf("one") })
        assertEquals(listOf("one", "two"), pages)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafePaginationOverflow() = kotlinx.coroutines.runBlocking {
        LegadoPagination.collect("1", limit = 2, fetch = { it },
            extract = { page, _ -> page }, next = { _, url -> listOf((url.toInt() + 1).toString()) })
        Unit
    }

    @Test fun interleavesRuleResults() {
        val root = Jsoup.parse("<i>甲</i><b>乙</b><i>丙</i><b>丁</b>")
        assertEquals(listOf("甲", "乙", "丙", "丁"), LegadoStaticRule.parse("@css:i@text%%@css:b@text", false).values(root))
    }

    @Test fun localVariablesAreIsolated() {
        val root = Jsoup.parse("<a href='/one'>甲</a>")
        val scope = LegadoVariables()
        assertEquals(listOf("/one"), LegadoStaticRule.parse("""@put:{"url":"@css:a@href"}@get:{url}""", false).values(root, scope))
        assertEquals(listOf("/one"), LegadoStaticRule.parse("@get:{url}", false).values(root, scope))
        assertTrue(LegadoStaticRule.parse("@get:{url}", false).values(root, LegadoVariables()).isEmpty())
    }

    @Test fun restrictedExpressionAndTemplate() {
        val root = Jsoup.parse("<a href='/a'>  中文  </a>")
        assertEquals(listOf("中文"), LegadoStaticRule.parse("@css:a@text@js:result.trim()", false).values(root))
        val vars = LegadoVariables()
        vars.put("id", "42")
        assertEquals(listOf("/book/42"), LegadoStaticRule.parse("/book/{{@get:{id}}}", false).values(root, vars))
        assertEquals(listOf("42"), LegadoStaticRule.parse("@js:java.get('id')", false).values(root, vars))
    }

    @Test fun rejectsArbitraryScripts() {
        for (rule in listOf("@js:java.ajax('https://example.com')", "@js:while(true){}", "@js:result.constructor('x')", "@js:Packages.java.lang.System", "@js:eval('1')")) {
            assertTrue(rule, runCatching { LegadoStaticRule.parse(rule, false) }.isFailure)
        }
    }

    @Test fun jsonPathSlicesUnionsAndRecursiveFields() {
        val root = Json.parseToJsonElement("""{"books":[{"title":"甲"},{"title":"乙"},{"title":"丙"},{"title":"丁"}]}""")
        assertEquals(listOf("甲", "丙"), LegadoStaticRule.parse("$.books[0:4:2].title", false).values(root))
        assertEquals(listOf("丁", "甲"), LegadoStaticRule.parse("$.books[-1,0].title", false).values(root))
        assertEquals(listOf("甲", "乙", "丙", "丁"), LegadoStaticRule.parse("$..title", false).values(root))
        assertTrue(runCatching { LegadoStaticRule.parse("$.books[?(@.title)]", false) }.isFailure)
    }
}
