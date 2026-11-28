package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/** Static URL templates. No scripts, credentials, arbitrary methods or WebView. */
internal object LegadoRequestRule {
    data class Spec(
        val url: String,
        val method: String = "GET",
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val charset: Charset? = null,
    )
    private val allowedHeaders = setOf("user-agent", "accept", "accept-language", "referer", "origin", "content-type", "x-requested-with")
    private val charsets = setOf("UTF-8", "GB18030", "GBK", "GB2312", "BIG5", "SHIFT_JIS", "EUC-JP")
    private val options = setOf("method", "body", "headers", "charset")

    fun search(source: JsonObject, query: String, page: Int): Spec {
        require(query.isNotBlank() && query.length <= 256 && page in 1..10000)
        val base = source.string("bookSourceUrl")
        val template = source.string("searchUrl")
        require(template.length in 1..8192)
        val index = Regex(""",\s*\{""").find(template)?.range?.first ?: -1
        val urlTemplate = if (index < 0) template else template.substring(0, index).trim()
        val opt = if (index < 0) JsonObject(emptyMap())
            else Json.parseToJsonElement(template.substring(index + 1).trim()) as? JsonObject
                ?: error("URL options must be an object")
        require(opt.keys.all { it in options }) { "Unsupported request option" }
        val method = opt.string("method").ifBlank { "GET" }.uppercase(Locale.ROOT)
        require(method in setOf("GET", "POST"))
        val encoding = opt.string("charset").ifBlank { "UTF-8" }.uppercase(Locale.ROOT)
        require(encoding in charsets && Charset.isSupported(encoding))
        val charset = Charset.forName(encoding)
        val encoded = URLEncoder.encode(query, charset.name()).replace("+", "%20")
        fun expand(value: String): String {
            val expanded = value.replace("{{key}}", encoded).replace(
                Regex("""\{\{\s*page\s*(?:([+\-*/%])\s*(\d{1,5}))?\s*\}\}""")
            ) { match ->
                val number = match.groupValues[2].toLongOrNull() ?: 0L
                val p = page.toLong()
                val result = when (match.groupValues[1]) {
                    "+" -> p + number
                    "-" -> p - number
                    "*" -> p * number
                    "/" -> { require(number > 0); p / number }
                    "%" -> { require(number > 0); p % number }
                    else -> p
                }
                require(result in 0..100_000_000L) { "Page expression out of bounds" }
                result.toString()
            }
            require(expanded.none { it in "{}<>\r\n" } && !expanded.contains("@js:", true))
            return expanded
        }
        val bodyTemplate = opt.string("body")
        require(urlTemplate.contains("{{key}}") || bodyTemplate.contains("{{key}}"))
        val url = sameOrigin(base, expand(urlTemplate))
        val headers = sourceHeaders(source) + parseHeaders(opt["headers"], base)
        val body = if (method == "POST") {
            require(bodyTemplate.isNotBlank() && bodyTemplate.length <= 8192 && '=' in bodyTemplate)
            require(headers["content-type"]?.substringBefore(';')?.trim()?.equals("application/x-www-form-urlencoded", true) != false)
            expand(bodyTemplate)
        } else {
            require(bodyTemplate.isBlank())
            null
        }
        return Spec(url, method, body, headers, opt.string("charset").takeIf { it.isNotBlank() }?.let { charset })
    }

    fun sourceHeaders(source: JsonObject): Map<String, String> =
        parseHeaders(source["header"], source.string("bookSourceUrl"))

    private fun parseHeaders(value: JsonElement?, base: String): Map<String, String> {
        if (value == null || value is JsonNull) return emptyMap()
        val data = if (value is JsonPrimitive) {
            if (value.content.isBlank()) return emptyMap()
            Json.parseToJsonElement(value.content)
        } else value
        require(data is JsonObject && data.size <= 12)
        return data.entries.associate { (key, raw) ->
            val name = key.lowercase(Locale.ROOT)
            require(name in allowedHeaders) { "Unsupported header: $key" }
            require(raw is JsonPrimitive && raw.isString)
            val text = raw.content
            require(text.length in 1..512 && text.all { it.code in 32..126 } &&
                !text.contains("{{") && !text.contains("@js:", true))
            if (name == "referer" || name == "origin") require(sameOrigin(base, text) == text)
            name to text
        }
    }

    private fun sameOrigin(base: String, link: String): String {
        val root = URI(base)
        val url = root.resolve(link).normalize()
        fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        require(root.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !root.host.isNullOrBlank() && url.scheme.equals(root.scheme, true) &&
            url.host.equals(root.host, true) && port(url) == port(root) && url.userInfo == null)
        return url.toASCIIString().substringBefore('#')
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
