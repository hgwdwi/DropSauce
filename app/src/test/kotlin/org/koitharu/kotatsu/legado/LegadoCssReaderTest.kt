package org.koitharu.kotatsu.legado

import org.junit.Assert.*
import org.junit.Test

class LegadoCssReaderTest {

    private fun reader(bodyRule: String = "@css:#content@html"): LegadoCssReader {
        val json = """
            {
              "bookSourceName":"Fixture",
              "bookSourceUrl":"https://example.com",
              "searchUrl":"/search?q={{key}}&page={{page}}",
              "ruleSearch":{"bookList":"@css:.book","name":"@css:h2@text","bookUrl":"@css:a@href"},
              "ruleToc":{"chapterList":"@css:.chapter","chapterName":"@css:a@text","chapterUrl":"@css:a@href"},
              "ruleContent":{"content":"$bodyRule"}
            }
        """.trimIndent()
        return LegadoCssReader(LegadoSourcePreflight.inspect(json).single())
    }

    @Test fun encodesChineseSearchAndPagination() {
        assertEquals("https://example.com/search?q=%E4%B9%A6%20%E5%90%8D&page=2", reader().searchUrl("书 名", 2))
    }

    @Test fun parsesBooksAndRejectsUnsafeOrForeignUrls() {
        val html = """
            <div class="book"><h2>第一本书</h2><a href="/book/1">阅读</a></div>
            <div class="book"><h2>重复</h2><a href="/book/1">阅读</a></div>
            <div class="book"><h2>外链</h2><a href="//outside.example/book">阅读</a></div>
            <div class="book"><h2>危险链接</h2><a href="javascript:alert(1)">阅读</a></div>
            <div class="book"><h2>缺失链接</h2></div>
        """.trimIndent()
        assertEquals(listOf(LegadoCssReader.Book("第一本书", "https://example.com/book/1")),
            reader().searchResults(html, "https://example.com/search"))
    }

    @Test fun preservesChapterOrderAndResolvesRelativeLinks() {
        val html = """<li class="chapter"><a href="1.html">第一章</a></li>
            <li class="chapter"><a href="2.html">第二章</a></li>"""
        assertEquals(listOf("第一章", "第二章"), reader().chapters(html, "https://example.com/book/").map { it.title })
        assertEquals("https://example.com/book/1.html", reader().chapters(html, "https://example.com/book/").first().url)
    }

    @Test fun cleansActiveContentAndKeepsParagraphs() {
        val html = """<div id="content"><p onclick="bad()">第一段</p>
            <script>bad()</script><p>第二段<br>下一行</p><iframe src="https://outside.example"></iframe></div>"""
        val result = reader().chapterHtml(html, "https://example.com/book/1.html")
        assertTrue(result.contains("<p>第一段</p>"))
        assertTrue(result.contains("第二段"))
        assertFalse(result.contains("bad()"))
        assertFalse(result.contains("iframe"))
    }

    @Test fun textModePreservesExplicitLineBreaks() {
        val result = reader("@css:#content@text").chapterHtml(
            """<div id="content">第一行<br>第二行</div>""", "https://example.com/book/1.html")
        assertTrue(result.contains("第一行<br>") || result.contains("第一行<br />"))
        assertTrue(result.contains("第二行"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyChapter() {
        reader().chapterHtml("<div id='content'><script>bad()</script></div>", "https://example.com/book/1.html")
    }
}
