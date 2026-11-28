package org.koitharu.kotatsu.legado

import com.google.re2j.Pattern

/** Limited, linear-time regex replacement. Java lookarounds/backreferences are not accepted. */
internal class LegadoReplacement private constructor(
    private val pattern: Pattern,
    private val replacement: String,
) {
    fun apply(value: String): String {
        require(value.length <= 4 * 1024 * 1024) { "Regex input exceeds limit" }
        val matcher = pattern.matcher(value)
        // Bound output before allocating; avoid amplification from many zero-width matches.
        var count = 0
        while (matcher.find()) {
            count++
            require(count <= 100000 && count.toLong() * replacement.length <= 4L * 1024 * 1024) {
                "Regex expansion exceeds limit"
            }
        }
        val result = pattern.matcher(value).replaceAll(replacement)
        require(result.length <= 4 * 1024 * 1024) { "Regex output exceeds limit" }
        return result
    }

    companion object {
        fun parse(rule: String): LegadoReplacement {
            require(rule.length in 3..1024 && rule.startsWith("##"))
            val parts = rule.substring(2).split("##")
            require(parts.size in 1..2 && parts[0].isNotBlank())
            val pattern = Pattern.compile(parts[0])
            val replacement = parts.getOrElse(1) { "" }
            // This subset supports literal replacement only; group expansion is intentionally excluded.
            require('$' !in replacement && '\\' !in replacement && replacement.length <= 128)
            return LegadoReplacement(pattern, replacement)
        }
    }
}
