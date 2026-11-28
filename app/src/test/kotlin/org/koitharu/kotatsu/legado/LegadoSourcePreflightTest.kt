package org.koitharu.kotatsu.legado

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class LegadoSourcePreflightTest {

    private val source = """
        {
          "bookSourceName":"Example",
          "bookSourceUrl":"https://example.com",
          "searchUrl":"/search?q={{key}}",
          "ruleSearch":{"bookList":"@css:.book","name":"@css:h2@text","bookUrl":"@css:a@href"},
          "ruleToc":{"chapterList":"@css:.chapter","chapterName":"@css:a@text","chapterUrl":"@css:a@href"},
          "ruleContent":{"content":"@css:#content@html"}
        }
    """.trimIndent()

    @Test fun acceptsExplicitCssSubset() {
        val result = LegadoSourcePreflight.inspect(source).single()
        assertTrue(result.issues.toString(), result.supportedSubset)
        assertEquals("Example", result.name)
    }

    @Test fun flagsScriptsAndDuplicates() {
        val unsafe = source.replace("\"bookSourceName\"", "\"jsLib\":\"alert(1)\",\"bookSourceName\"")
        val result = LegadoSourcePreflight.inspect("[$unsafe,$source]")
        assertFalse(result[0].supportedSubset)
        assertTrue(result[0].issues.contains("Unsupported jsLib"))
        assertTrue(result[1].issues.contains("Duplicate bookSourceUrl"))
    }

    @Test fun extractsCssFromInertHtml() {
        val root = Jsoup.parse("<div class='book'><h2>中文书名</h2><a href='/book/1'>阅读</a></div>")
        val book = LegadoSourcePreflight.parseRule("@css:.book", true).elements(root).single()
        assertEquals(listOf("中文书名"), LegadoSourcePreflight.parseRule("@css:h2@text", false).values(book))
        assertEquals(listOf("/book/1"), LegadoSourcePreflight.parseRule("@css:a@href", false).values(book))
    }

    @Test fun rejectsUnsupportedRuleSyntax() {
        val invalid = source.replace("@css:#content@html", "@js:eval('1')")
        assertTrue(LegadoSourcePreflight.inspect(invalid).single().issues.contains("Unsupported ruleContent.content"))
    }

    @Test fun acceptsRestrictedAjaxForRuntimeEvaluation() {
        val candidate = source.replace("@css:#content@html", "@js:java.ajax('/chapter')")
        val inspection = LegadoSourcePreflight.inspect(candidate).single()
        assertTrue(inspection.issues.toString(), inspection.supportedSubset)
    }

    @Test fun rejectsNonHttpSource() {
        val invalid = source.replace("https://example.com", "file:///sdcard")
        assertFalse(LegadoSourcePreflight.inspect(invalid).single().supportedSubset)
    }
}
