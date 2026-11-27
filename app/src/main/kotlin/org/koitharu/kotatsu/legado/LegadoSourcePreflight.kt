package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Offline Legado 3 source inspection, not a general AnalyzeRule implementation.
 * Only an explicit @css: subset is accepted. No scripts, requests or credentials
 * are executed or used at this stage. Unknown rules remain in the original JSON.
 */
internal object LegadoSourcePreflight {

    private const val MAX_JSON_CHARS = 4 * 1024 * 1024
    private const val MAX_SOURCES = 2000

    data class Inspection(
        val name: String,
        val url: String,
        val enabled: Boolean,
        val issues: List<String>,
        val original: JsonObject,
    ) {
        val supportedSubset: Boolean get() = issues.isEmpty()
    }

    private val required = linkedMapOf(
        "ruleSearch" to listOf("bookList", "name", "bookUrl"),
        "ruleToc" to listOf("chapterList", "chapterName", "chapterUrl"),
        "ruleContent" to listOf("content"),
    )
    private val optional = mapOf(
        "ruleSearch" to setOf("author", "intro", "lastChapter", "kind", "wordCount", "coverUrl"),
        "ruleBookInfo" to setOf("name", "author", "intro", "lastChapter", "kind", "wordCount", "coverUrl", "tocUrl"),
    )
    private val scriptFields = setOf(
        "jsLib", "loginUrl", "loginUi", "loginCheckJs", "coverDecodeJs",
        "header", "bookUrlPattern", "exploreUrl", "exploreScreen", "ruleReview",
        "ruleExplore", "concurrentRate",
    )

    fun inspect(input: String): List<Inspection> {
        require(input.length <= MAX_JSON_CHARS) { "Source JSON exceeds size limit" }
        val root = Json.parseToJsonElement(input.removePrefix("\uFEFF"))
        val entries = when (root) {
            is JsonObject -> listOf(root)
            is JsonArray -> root.mapIndexed { index, value ->
                requireNotNull(value as? JsonObject) { "Source $index is not an object" }
            }
            else -> error("Expected source object or array")
        }
        require(entries.size in 1..MAX_SOURCES) { "Invalid source count" }
        val seen = HashSet<String>()
        return entries.map { item ->
            val name = item.string("bookSourceName")
            val url = item.string("bookSourceUrl")
            val issues = ArrayList<String>()
            if (name.isBlank()) issues += "Missing bookSourceName"
            if (!httpUrl(url)) issues += "Invalid bookSourceUrl"
            if (!seen.add(url)) issues += "Duplicate bookSourceUrl"
            if (item.string("bookSourceType").ifBlank { "0" } != "0") issues += "Not a text novel source"
            val search = item.string("searchUrl")
            // Accept only simple HTTP GET URL templates. Advanced Legado URL
            // syntax (POST options, JS, variables) needs a separate implementation.
            val sample = search.replace("{{key}}", "test").replace("{{page}}", "1")
            if (!search.contains("{{key}}") || sample.any { it in "{}<>\n\r" } ||
                sample.contains("@js:", ignoreCase = true) || sample.contains(",") ||
                !httpUrl(runCatching { URI(url).resolve(sample).toString() }.getOrDefault(""))) {
                issues += "Unsupported searchUrl"
            }
            for (field in scriptFields) {
                if (item[field]?.toString()?.let { it !in listOf("null", "\"\"", "{}", "[]") } == true) {
                    issues += "Unsupported $field"
                }
            }
            for ((group, fields) in required) {
                val rules = item[group] as? JsonObject
                for (field in fields) {
                    val value = rules?.string(field).orEmpty()
                    if (value.isBlank()) issues += "Missing $group.$field"
                    else if (runCatching { parseRule(value, field in setOf("bookList", "chapterList")) }.isFailure) {
                        issues += "Unsupported $group.$field"
                    }
                }
            }
            // Optional rule groups must not silently execute unsupported syntax.
            for (group in listOf("ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent")) {
                val rules = item[group] as? JsonObject ?: continue
                for ((field, value) in rules) {
                    if (field in required[group].orEmpty()) continue
                    if (value.toString() in listOf("null", "\"\"")) continue
                    if (field in optional[group].orEmpty()) {
                        val parsed = runCatching { parseRule(rules.string(field), false) }.getOrNull()
                        val allowed = when (field) {
                            "tocUrl" -> setOf("href", "content")
                            "coverUrl" -> setOf("src", "href", "content")
                            else -> setOf("text", "html", "content")
                        }
                        if (parsed == null || parsed.extraction !in allowed) issues += "Unsupported $group.$field"
                    } else issues += "Optional rule not implemented: $group.$field"
                }
            }
            Inspection(name, url, item.string("enabled") != "false", issues, item)
        }
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun httpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    internal data class CssRule(val selector: String, val extraction: String?) {
        fun elements(root: Element): List<Element> = root.select(selector)
        fun values(root: Element): List<String> = elements(root).map { element ->
            when (extraction) {
                "text" -> element.text()
                "html" -> element.html()
                null -> error("List rule cannot extract text")
                else -> element.attr(extraction)
            }
        }
    }

    internal fun parseRule(rule: String, list: Boolean): CssRule {
        require(rule.startsWith("@css:", ignoreCase = true)) { "Explicit CSS rule required" }
        val raw = rule.substring(5).trim()
        require(raw.length in 1..512)
        require(listOf("@js:", "<js>", "{{", "##", "&&", "||", "%%").none {
            raw.contains(it, ignoreCase = true)
        }) { "Composite or scripted rule unsupported" }
        val separator = raw.lastIndexOf('@')
        val selector = if (list) raw else raw.substring(0, separator.coerceAtLeast(0)).trim()
        val extraction = if (list) null else raw.substring(separator + 1).trim()
        require(selector.isNotBlank())
        if (!list) require(separator > 0 && extraction in setOf("text", "html", "href", "src", "content"))
        // Validate selector syntax against an inert DOM.
        org.jsoup.Jsoup.parse("").select(selector)
        return CssRule(selector, extraction)
    }
}
