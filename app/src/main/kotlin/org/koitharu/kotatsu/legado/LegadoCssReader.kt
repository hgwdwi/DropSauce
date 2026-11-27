package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.net.URI

/**
 * Pure HTML parsing for the explicit, script-free Legado CSS subset.
 * The caller supplies fetched pages. This class does not perform networking.
 * Supports a distinct same-origin TOC URL extracted from the book detail page.
 */
internal class LegadoCssReader(private val source: LegadoSourcePreflight.Inspection) {

    init {
        require(source.supportedSubset && source.enabled) { "Source is disabled or unsupported" }
    }

    data class Book(val title: String, val url: String, val author: String? = null, val intro: String? = null, val lastChapter: String? = null, val kind: String? = null, val wordCount: String? = null, val coverUrl: String? = null, val variables: Map<String, String> = emptyMap())
    data class Details(val book: Book, val tocUrl: String)
    data class Chapter(val title: String, val url: String, val variables: Map<String, String> = emptyMap())

    fun searchRequest(query: String, page: Int = 1): LegadoRequestRule.Spec =
        LegadoRequestRule.search(source.original, query, page)

    fun searchUrl(query: String, page: Int = 1): String = searchRequest(query, page).url

    fun searchResults(html: String, pageUrl: String): List<Book> {
        val doc = document(html, pageUrl)
        val rows = rule("ruleSearch", "bookList", true).elements(doc)
        val names = rule("ruleSearch", "name")
        val urls = rule("ruleSearch", "bookUrl")
        return rows.asSequence().mapNotNull { row ->
            val vars = LegadoVariables()
            val name = first(names, row, vars) ?: return@mapNotNull null
            val url = first(urls, row, vars)?.let { resolve(pageUrl, it) } ?: return@mapNotNull null
            Book(name, url,
                metadata("ruleSearch", "author", row, vars), metadata("ruleSearch", "intro", row, vars),
                metadata("ruleSearch", "lastChapter", row, vars), metadata("ruleSearch", "kind", row, vars),
                metadata("ruleSearch", "wordCount", row, vars),
                optionalValue("ruleSearch", "coverUrl", row, vars)?.let { resolve(pageUrl, it) },
                vars.snapshot(),
            )
        }.distinctBy { it.url }.take(MAX_ROWS).toList()
    }

    fun details(book: Book, html: String, pageUrl: String): Details {
        val doc = document(html, pageUrl)
        val vars = LegadoVariables(book.variables)
        val updated = book.copy(
            title = metadata("ruleBookInfo", "name", doc, vars) ?: book.title,
            author = metadata("ruleBookInfo", "author", doc, vars) ?: book.author,
            intro = metadata("ruleBookInfo", "intro", doc, vars) ?: book.intro,
            lastChapter = metadata("ruleBookInfo", "lastChapter", doc, vars) ?: book.lastChapter,
            kind = metadata("ruleBookInfo", "kind", doc, vars) ?: book.kind,
            wordCount = metadata("ruleBookInfo", "wordCount", doc, vars) ?: book.wordCount,
            coverUrl = optionalValue("ruleBookInfo", "coverUrl", doc, vars)?.let { resolve(pageUrl, it) } ?: book.coverUrl,
        )
        val tocRule = optionalRule("ruleBookInfo", "tocUrl")
        val toc = if (tocRule == null) pageUrl else {
            val link = first(tocRule, doc, vars) ?: error("Directory link not found")
            resolve(pageUrl, link) ?: error("Unsupported directory link")
        }
        return Details(updated.copy(variables = vars.snapshot()), toc)
    }

    private fun optionalRule(group: String, field: String): LegadoStaticRule? =
        (source.original[group] as? JsonObject)?.string(field)?.takeIf { it.isNotBlank() }
            ?.let { LegadoStaticRule.parse(it, false) }

    private fun optionalValue(group: String, field: String, root: Any, vars: LegadoVariables): String? =
        optionalRule(group, field)?.let { first(it, root, vars) }

    private fun metadata(group: String, field: String, root: Any, vars: LegadoVariables): String? {
        val rule = optionalRule(group, field) ?: return null
        val value = first(rule, root, vars) ?: return null
        return (if (rule.extraction == "html") Jsoup.parseBodyFragment(value).text() else value)
            .take(2000).takeIf { it.isNotBlank() }
    }

    fun nextDirectoryUrls(html: String, pageUrl: String): List<String> = nextUrls("ruleToc", "nextTocUrl", html, pageUrl)

    fun nextContentUrls(html: String, pageUrl: String): List<String> = nextUrls("ruleContent", "nextContentUrl", html, pageUrl)

    private fun nextUrls(group: String, field: String, html: String, pageUrl: String): List<String> {
        val rule = optionalRule(group, field) ?: return emptyList()
        return rule.values(document(html, pageUrl)).filter { it.isNotBlank() }.map { link ->
            resolve(pageUrl, link) ?: error("Unsupported pagination URL")
        }.distinct().take(100)
    }

    fun reverseDirectory(): Boolean = (source.original["ruleToc"] as? JsonObject)?.string("chapterList")?.startsWith("-") == true

    fun cleanCombinedContent(html: String): String {
        val rule = (source.original["ruleContent"] as? JsonObject)?.string("replaceRegex").orEmpty()
        if (rule.isBlank()) return html
        val text = LegadoTextExtractor.extract(Jsoup.parseBodyFragment(html).body())
        val cleaned = LegadoReplacement.parse(rule).apply(text)
        require(cleaned.isNotBlank()) { "Content is empty after replacement" }
        return "<p>" + org.jsoup.nodes.Entities.escape(cleaned).replace("\n", "<br>") + "</p>"
    }

    fun chapters(html: String, pageUrl: String, bookVariables: Map<String, String> = emptyMap()): List<Chapter> {
        val doc = document(html, pageUrl)
        val rows = rule("ruleToc", "chapterList", true).elements(doc)
        val names = rule("ruleToc", "chapterName")
        val urls = rule("ruleToc", "chapterUrl")
        return rows.asSequence().mapNotNull { row ->
            val vars = LegadoVariables(bookVariables)
            val name = first(names, row, vars) ?: return@mapNotNull null
            val url = first(urls, row, vars)?.let { resolve(pageUrl, it) } ?: return@mapNotNull null
            Chapter(name, url, vars.snapshot())
        }.distinctBy { it.url }.take(MAX_ROWS).toList()
    }

    fun chapterHtml(html: String, pageUrl: String, variables: Map<String, String> = emptyMap()): String {
        val doc = document(html, pageUrl)
        val extraction = rule("ruleContent", "content")
        if (doc is Element) doc.select("script,style,noscript,iframe,object,embed,form").remove()
        val fragment = extraction.body(doc, LegadoVariables(variables))
        require(fragment.isNotBlank()) { "Content selector found no matches" }
        val cleaned = Jsoup.clean(fragment, pageUrl, Safelist.none().addTags(
            "p", "br", "div", "span", "h1", "h2", "h3", "h4", "strong", "b",
            "em", "i", "u", "blockquote", "hr", "ul", "ol", "li", "ruby", "rt", "rp",
        ), Document.OutputSettings().prettyPrint(false))
        require(Jsoup.parseBodyFragment(cleaned).text().isNotBlank()) { "Chapter has no readable text" }
        return cleaned
    }

    private fun document(html: String, pageUrl: String): Any {
        require(html.length <= MAX_HTML_CHARS) { "HTML exceeds size limit" }
        require(resolve(source.url, pageUrl) != null) { "Invalid page URL" }
        val trimmed = html.trimStart().removePrefix("\uFEFF")
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return Json.parseToJsonElement(trimmed)
        return Jsoup.parse(html, pageUrl)
    }

    private fun rule(group: String, field: String, list: Boolean = false): LegadoStaticRule {
        val json = source.original[group] as? JsonObject ?: error("Missing $group")
        return LegadoStaticRule.parse(if (field == "chapterList") json.string(field).removePrefix("-") else json.string(field), list)
    }

    private fun first(rule: LegadoStaticRule, row: Any, vars: LegadoVariables = LegadoVariables()): String? =
        rule.values(row, vars).firstOrNull { it.isNotBlank() }?.trim()

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    /**
     * Keep navigation on the configured origin. Cross-origin links and
     * non-HTTP schemes are not part of this first compatibility subset.
     */
    private fun resolve(base: String, link: String): String? = runCatching {
        val root = URI(source.url)
        val url = URI(base).resolve(link.trim()).normalize()
        require(url.scheme.equals(root.scheme, true) && url.host.equals(root.host, true))
        require(port(url) == port(root) && url.userInfo == null)
        require(url.scheme.lowercase() in setOf("http", "https"))
        url.toASCIIString().substringBefore('#')
    }.getOrNull()

    private fun port(uri: URI): Int =
        if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    companion object {
        private const val MAX_ROWS = 10000
        private const val MAX_HTML_CHARS = 4 * 1024 * 1024
    }
}
