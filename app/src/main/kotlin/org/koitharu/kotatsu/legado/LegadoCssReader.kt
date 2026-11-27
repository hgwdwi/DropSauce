package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.net.URI
import java.net.URLEncoder

/**
 * Pure HTML parsing for the explicit, script-free Legado CSS subset.
 * The caller supplies fetched pages. This class does not perform networking.
 * Supports a distinct same-origin TOC URL extracted from the book detail page.
 */
internal class LegadoCssReader(private val source: LegadoSourcePreflight.Inspection) {

    init {
        require(source.supportedSubset && source.enabled) { "Source is disabled or unsupported" }
    }

    data class Book(val title: String, val url: String, val author: String? = null, val intro: String? = null, val lastChapter: String? = null, val kind: String? = null, val wordCount: String? = null, val coverUrl: String? = null)
    data class Details(val book: Book, val tocUrl: String)
    data class Chapter(val title: String, val url: String)

    fun searchUrl(query: String, page: Int = 1): String {
        require(query.isNotBlank() && query.length <= 256)
        require(page in 1..10000)
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val template = source.original.string("searchUrl")
        return resolve(source.url, template.replace("{{key}}", encoded).replace("{{page}}", page.toString()))
            ?: error("Invalid search URL")
    }

    fun searchResults(html: String, pageUrl: String): List<Book> {
        val doc = document(html, pageUrl)
        val rows = rule("ruleSearch", "bookList", true).elements(doc)
        val names = rule("ruleSearch", "name")
        val urls = rule("ruleSearch", "bookUrl")
        return rows.asSequence().mapNotNull { row ->
            val name = first(names, row) ?: return@mapNotNull null
            val url = first(urls, row)?.let { resolve(pageUrl, it) } ?: return@mapNotNull null
            Book(name, url,
                metadata("ruleSearch", "author", row), metadata("ruleSearch", "intro", row),
                metadata("ruleSearch", "lastChapter", row), metadata("ruleSearch", "kind", row),
                metadata("ruleSearch", "wordCount", row),
                optionalValue("ruleSearch", "coverUrl", row)?.let { resolve(pageUrl, it) },
            )
        }.distinctBy { it.url }.take(MAX_ROWS).toList()
    }

    fun details(book: Book, html: String, pageUrl: String): Details {
        val doc = document(html, pageUrl)
        val tocRule = optionalRule("ruleBookInfo", "tocUrl")
        val toc = if (tocRule == null) pageUrl else {
            val link = first(tocRule, doc) ?: error("Directory link not found")
            resolve(pageUrl, link) ?: error("Unsupported directory link")
        }
        return Details(book.copy(
            title = metadata("ruleBookInfo", "name", doc) ?: book.title,
            author = metadata("ruleBookInfo", "author", doc) ?: book.author,
            intro = metadata("ruleBookInfo", "intro", doc) ?: book.intro,
            lastChapter = metadata("ruleBookInfo", "lastChapter", doc) ?: book.lastChapter,
            kind = metadata("ruleBookInfo", "kind", doc) ?: book.kind,
            wordCount = metadata("ruleBookInfo", "wordCount", doc) ?: book.wordCount,
            coverUrl = optionalValue("ruleBookInfo", "coverUrl", doc)?.let { resolve(pageUrl, it) } ?: book.coverUrl,
        ), toc)
    }

    private fun optionalRule(group: String, field: String): LegadoSourcePreflight.CssRule? =
        (source.original[group] as? JsonObject)?.string(field)?.takeIf { it.isNotBlank() }
            ?.let { LegadoSourcePreflight.parseRule(it, false) }

    private fun optionalValue(group: String, field: String, root: Element): String? =
        optionalRule(group, field)?.let { first(it, root) }

    private fun metadata(group: String, field: String, root: Element): String? {
        val rule = optionalRule(group, field) ?: return null
        val value = first(rule, root) ?: return null
        return (if (rule.extraction == "html") Jsoup.parseBodyFragment(value).text() else value)
            .take(2000).takeIf { it.isNotBlank() }
    }

    fun chapters(html: String, pageUrl: String): List<Chapter> {
        val doc = document(html, pageUrl)
        val rows = rule("ruleToc", "chapterList", true).elements(doc)
        val names = rule("ruleToc", "chapterName")
        val urls = rule("ruleToc", "chapterUrl")
        return rows.asSequence().mapNotNull { row ->
            val name = first(names, row) ?: return@mapNotNull null
            val url = first(urls, row)?.let { resolve(pageUrl, it) } ?: return@mapNotNull null
            Chapter(name, url)
        }.distinctBy { it.url }.take(MAX_ROWS).toList()
    }

    fun chapterHtml(html: String, pageUrl: String): String {
        val doc = document(html, pageUrl)
        val extraction = rule("ruleContent", "content")
        val selected = extraction.elements(doc)
        require(selected.isNotEmpty()) { "Content selector found no matches" }
        // Remove active and extraneous elements before extracting body text.
        selected.forEach { it.select("script,style,noscript,iframe,object,embed,form").remove() }
        val fragment = when (extraction.extraction) {
            "html" -> selected.joinToString("\n") { it.html() }
            "text" -> selected.joinToString("\n") {
                // TextNode / Element.text() would flatten all paragraphs.
                "<p>" + org.jsoup.nodes.Entities.escape(LegadoTextExtractor.extract(it)).replace("\n", "<br>") + "</p>"
            }
            else -> error("Body rule must extract text or HTML")
        }
        val cleaned = Jsoup.clean(fragment, pageUrl, Safelist.none().addTags(
            "p", "br", "div", "span", "h1", "h2", "h3", "h4", "strong", "b",
            "em", "i", "u", "blockquote", "hr", "ul", "ol", "li", "ruby", "rt", "rp",
        ), Document.OutputSettings().prettyPrint(false))
        require(Jsoup.parseBodyFragment(cleaned).text().isNotBlank()) { "Chapter has no readable text" }
        return cleaned
    }

    private fun document(html: String, pageUrl: String): Element {
        require(html.length <= MAX_HTML_CHARS) { "HTML exceeds size limit" }
        require(resolve(source.url, pageUrl) != null) { "Invalid page URL" }
        return Jsoup.parse(html, pageUrl)
    }

    private fun rule(group: String, field: String, list: Boolean = false): LegadoSourcePreflight.CssRule {
        val json = source.original[group] as? JsonObject ?: error("Missing $group")
        return LegadoSourcePreflight.parseRule(json.string(field), list)
    }

    private fun first(rule: LegadoSourcePreflight.CssRule, row: Element): String? =
        rule.values(row).firstOrNull { it.isNotBlank() }?.trim()

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
