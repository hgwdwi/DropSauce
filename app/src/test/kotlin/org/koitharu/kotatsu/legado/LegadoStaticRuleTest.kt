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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsScripts() {
        LegadoStaticRule.parse("@js:java.get('x')", false)
    }
}
