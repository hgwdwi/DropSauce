package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import java.util.Locale

/**
 * Deterministic subset of Legado selectors. No JS, native bridge, or network.
 * Supports explicit CSS, common default selectors, XPath node selections,
 * basic JSONPath, and fallback/concatenation. Unsupported syntax fails closed.
 */
internal class LegadoStaticRule private constructor(
    private val branches: List<Branch>,
    private val operator: String?,
    private val list: Boolean,
    private val replacement: LegadoReplacement?,
) {
    private enum class Mode { CSS, DEFAULT, XPATH, JSON }
    private data class Branch(val mode: Mode, val expression: String, val extraction: String?)
    private data class Step(val selector: String, val index: Int?)
    val extraction: String? get() = branches.firstOrNull()?.extraction

    fun elements(root: Any): List<Any> = evaluate(root, false)

    fun values(root: Any): List<String> = evaluate(root, false).mapNotNull { it.asText() }.map { replacement?.apply(it) ?: it }

    fun body(root: Any): String = evaluate(root, true).mapNotNull { value ->
        when (value) {
            is Html -> replacement?.apply(value.value) ?: value.value
            else -> value.asText()?.let { "<p>" + Entities.escape(replacement?.apply(it) ?: it).replace("\n", "<br>") + "</p>" }
        }
    }.joinToString("\n")

    private data class Html(val value: String)

    private fun Any.asText(): String? = when (this) {
        is Html -> value
        is Element -> text()
        is JsonPrimitive -> contentOrNull
        is JsonNull -> null
        is JsonElement -> toString()
        is String -> this
        else -> null
    }

    private fun evaluate(root: Any, body: Boolean): List<Any> {
        val out = ArrayList<Any>()
        for (branch in branches) {
            val result = when (branch.mode) {
                Mode.JSON -> jsonPath(root, branch.expression)
                Mode.CSS, Mode.DEFAULT, Mode.XPATH -> {
                    val element = root as? Element ?: return emptyList()
                    val selected = when (branch.mode) {
                        Mode.CSS -> if (branch.expression.isEmpty()) listOf(element) else element.select(branch.expression)
                        Mode.XPATH -> element.selectXpath(branch.expression)
                        else -> defaultSelect(element, branch.expression)
                    }
                    if (list) selected else selected.map { node ->
                        when (branch.extraction) {
                            "html" -> Html(node.html())
                            "all" -> Html(node.outerHtml())
                            "ownText" -> node.ownText()
                            "textNodes" -> node.textNodes().joinToString("\n") { it.wholeText }
                            "text" -> if (body) LegadoTextExtractor.extract(node) else node.text()
                            null -> if (body) LegadoTextExtractor.extract(node) else node.text()
                            else -> node.attr(branch.extraction)
                        }
                    }
                }
            }.take(MAX_RESULTS)
            val usable = if (list) result else result.filter { !it.asText().isNullOrBlank() }
            if (operator == "||" && usable.isNotEmpty()) return usable
            out.addAll(usable.take(MAX_RESULTS - out.size))
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    companion object {
        private const val MAX_RESULTS = 10000
        private val blocked = listOf("@js:", "<js>", "{{", "@get:", "@put:", "%%", "@regex:")
        private val extractors = setOf("text", "ownText", "textNodes", "html", "all", "href", "src", "content", "title", "data-src", "data-original")
        private val jsonToken = Regex("""(?:\.([A-Za-z_][A-Za-z0-9_-]*)|\['([^']+)'\]|\["([^"]+)"\]|\[(-?\d+|\*)\])""")
        private val defaultStep = Regex("""(class|id|tag|text)\.([^@.]+)(?:\.(-?\d+))?""")
        private val xpathAttribute = Regex("""/(@[A-Za-z_][A-Za-z0-9_:-]*|text\(\))$""")

        fun parse(raw: String, list: Boolean): LegadoStaticRule {
            require(raw.length in 1..2048 && blocked.none { raw.contains(it, true) }) { "Unsupported dynamic rule" }
            val delimiter = raw.indexOf("##")
            require(!list || delimiter < 0) { "Replacement is not a list rule" }
            val replacement = if (delimiter >= 0) LegadoReplacement.parse(raw.substring(delimiter)) else null
            val selector = if (delimiter >= 0) raw.substring(0, delimiter).ifBlank { "all" } else raw
            val (parts, op) = split(selector)
            require(parts.size <= 16)
            val branches = parts.map { part ->
                val rule = part.trim()
                require(rule.isNotEmpty())
                when {
                    rule.startsWith("@json:", true) || rule.startsWith("$.") || rule.startsWith("$[") || rule == "$" -> {
                        val expression = if (rule.startsWith("@json:", true)) rule.substring(6).trim() else rule
                        validateJsonPath(expression)
                        Branch(Mode.JSON, expression, null)
                    }
                    rule.startsWith("@xpath:", true) || rule.startsWith("//") || rule.startsWith(".//") -> {
                        var expression = if (rule.startsWith("@xpath:", true)) rule.substring(7).trim() else rule
                        val suffix = xpathAttribute.find(expression)
                        val extraction = if (!list && suffix != null) {
                            expression = expression.substring(0, suffix.range.first).ifBlank { "." }
                            suffix.groupValues[1].let { if (it == "text()") "text" else it.substring(1) }
                        } else null
                        require(expression.isNotBlank() && expression.length <= 512)
                        Jsoup.parse("").selectXpath(expression)
                        Branch(Mode.XPATH, expression, extraction)
                    }
                    rule.startsWith("@css:", true) -> {
                        val expr = rule.substring(5).trim()
                        val at = expr.lastIndexOf('@')
                        val selector = if (list) expr else if (at >= 0) expr.substring(0, at).trim() else expr
                        val extraction = if (list) null else if (at >= 0) expr.substring(at + 1).trim() else "text"
                        require(list || extraction in extractors)
                        if (selector.isNotEmpty()) Jsoup.parse("").select(selector)
                        Branch(Mode.CSS, selector, extraction)
                    }
                    else -> {
                        val expr = rule.removePrefix("@@")
                        val steps = expr.split('@')
                        val extraction = if (list) null else steps.last().takeIf { it in extractors }
                            ?: error("Default rule requires a supported extraction")
                        val path = (if (list) steps else steps.dropLast(1)).joinToString("@")
                        parseDefault(path)
                        Branch(Mode.DEFAULT, path, extraction)
                    }
                }
            }
            return LegadoStaticRule(branches, op, list, replacement)
        }

        /** Split only top-level operators, not CSS attributes or quoted XPath predicates. */
        private fun split(raw: String): Pair<List<String>, String?> {
            val parts = ArrayList<String>()
            var quote: Char? = null
            var depth = 0
            var escaped = false
            var start = 0
            var operator: String? = null
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (quote != null) { if (ch == quote) quote = null }
                else if (ch == '\'' || ch == '"') quote = ch
                else if (ch == '[' || ch == '(') depth++
                else if (ch == ']' || ch == ')') depth--
                else if (depth == 0 && i + 1 < raw.length && raw.substring(i, i + 2) in setOf("&&", "||")) {
                    val op = raw.substring(i, i + 2)
                    require(operator == null || operator == op) { "Mixed rule operators unsupported" }
                    operator = op
                    parts.add(raw.substring(start, i))
                    i++
                    start = i + 1
                }
                require(depth >= 0)
                i++
            }
            require(quote == null && depth == 0)
            parts.add(raw.substring(start))
            return parts to operator
        }

        private fun parseDefault(expr: String): List<Step> {
            if (expr.isBlank()) return emptyList()
            return expr.split('@').map { step ->
                if (step == "children") return@map Step(":scope > *", null)
                val match = defaultStep.matchEntire(step) ?: error("Unsupported default selector: $step")
                val kind = match.groupValues[1]
                val value = match.groupValues[2]
                require(value.matches(Regex("""[\p{L}\p{N}_-]+"""))) { "Unsupported default selector value" }
                val selector = when (kind) {
                    "class" -> ".$value"
                    "id" -> "#$value"
                    "tag" -> value
                    else -> ":containsOwn($value)"
                }
                Step(selector, match.groupValues[3].toIntOrNull())
            }
        }

        private fun defaultSelect(root: Element, expression: String): List<Element> {
            var current = listOf(root)
            for (step in parseDefault(expression)) {
                current = current.flatMap { node ->
                    val matches = if (step.selector == ":scope > *") node.children().toList()
                        else node.select(step.selector).filterNot { it === node }
                    if (step.index == null) matches else {
                        val i = if (step.index < 0) matches.size + step.index else step.index
                        listOfNotNull(matches.getOrNull(i))
                    }
                }.take(MAX_RESULTS)
            }
            return current
        }

        private fun validateJsonPath(path: String) {
            require(path.startsWith("$"))
            var end = 1
            for (match in jsonToken.findAll(path, 1)) {
                require(match.range.first == end) { "Unsupported JSONPath segment" }
                end = match.range.last + 1
            }
            require(end == path.length) { "Unsupported JSONPath segment" }
        }

        private fun jsonPath(root: Any, path: String): List<JsonElement> {
            var current = listOfNotNull(root as? JsonElement)
            for (match in jsonToken.findAll(path, 1)) {
                val key = match.groupValues.drop(1).take(3).firstOrNull { it.isNotEmpty() }
                val index = match.groupValues[4]
                current = current.flatMap { node ->
                    when {
                        key != null -> listOfNotNull((node as? JsonObject)?.get(key))
                        index == "*" -> (node as? JsonArray)?.toList() ?: emptyList()
                        else -> {
                            val array = node as? JsonArray ?: return@flatMap emptyList()
                            val i = index.toIntOrNull() ?: return@flatMap emptyList()
                            listOfNotNull(array.getOrNull(if (i < 0) array.size + i else i))
                        }
                    }
                }.take(MAX_RESULTS)
            }
            return current.filterNot { it is JsonNull }
        }
    }
}
