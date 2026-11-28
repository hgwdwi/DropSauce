package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.JsonElement
import org.jsoup.nodes.Element

/** Static interpolation and explicitly restricted JavaScript expressions. */
internal class LegadoDynamicRule private constructor(
    private val prefix: LegadoStaticRule?,
    private val expression: LegadoPureExpression?,
    private val parts: List<Part>,
) {
    private data class Part(val literal: String?, val rule: LegadoStaticRule?, val expr: LegadoPureExpression?)

    fun evaluate(root: Any, vars: LegadoVariables): List<String> {
        fun raw(): String = when (root) {
            is Element -> root.outerHtml()
            is JsonElement -> root.toString()
            else -> root.toString()
        }
        if (expression != null) {
            val values = prefix?.values(root, vars) ?: listOf(raw())
            require(values.size <= 128)
            return values.map { expression.evaluate(it, vars) }
        }
        val out = StringBuilder()
        for (part in parts) {
            out.append(part.literal ?: part.rule?.values(root, vars)?.firstOrNull()
                ?: part.expr?.evaluate(raw(), vars).orEmpty())
            require(out.length <= 65536)
        }
        return listOf(out.toString())
    }

    companion object {
        fun recognizes(raw: String): Boolean =
            raw.contains("{{") || raw.contains("@js:", true) || raw.contains("<js>", true)

        fun parse(raw: String): LegadoDynamicRule {
            require(raw.length in 1..2048)
            val js = raw.indexOf("@js:", ignoreCase = true)
            val tag = raw.indexOf("<js>", ignoreCase = true)
            if (js >= 0 || tag >= 0) {
                val start = if (js >= 0) js else tag
                val before = raw.substring(0, start).trim()
                val code = if (js >= 0) raw.substring(start + 4) else {
                    require(raw.endsWith("</js>", true))
                    raw.substring(start + 4, raw.length - 5)
                }
                require(!recognizes(before))
                return LegadoDynamicRule(before.takeIf { it.isNotBlank() }?.let {
                    LegadoStaticRule.parse(it, false)
                }, LegadoPureExpression.parse(code), emptyList())
            }
            val parts = ArrayList<Part>()
            var offset = 0
            while (offset < raw.length) {
                val start = raw.indexOf("{{", offset)
                if (start < 0) {
                    parts.add(Part(raw.substring(offset), null, null))
                    break
                }
                if (start > offset) parts.add(Part(raw.substring(offset, start), null, null))
                var end = raw.indexOf("}}", start + 2)
                require(end >= 0)
                // @get:{key}}} contains an inner closing brace.
                if (raw.substring(start + 2, end).startsWith("@get:{") && raw.getOrNull(end + 2) == '}') end++
                val inner = raw.substring(start + 2, end).trim()
                require(inner.isNotBlank() && !recognizes(inner))
                val static = runCatching { LegadoStaticRule.parse(inner, false) }.getOrNull()
                parts.add(Part(null, static, if (static == null) LegadoPureExpression.parse(inner) else null))
                offset = end + 2
                require(parts.size <= 32)
            }
            return LegadoDynamicRule(null, null, parts)
        }
    }
}
