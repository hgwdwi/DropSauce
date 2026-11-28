package org.koitharu.kotatsu.legado

/** Per-book/per-chapter transient variables. Never stored globally or shared between sources. */
internal class LegadoVariables(initial: Map<String, String> = emptyMap()) {
    private val values = LinkedHashMap<String, String>()

    init {
        require(initial.size <= 64) { "Rule variable limit exceeded" }
        initial.forEach { (key, value) -> put(key, value) }
    }

    fun put(key: String, value: String) {
        require(validKey(key) && value.length <= 8192)
        require(key in values || values.size < 64) { "Rule variable limit exceeded" }
        values[key] = value
    }

    fun get(key: String): String? = values[key]
    fun snapshot(): Map<String, String> = values.toMap()

    companion object {
        private val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        fun validKey(key: String): Boolean = keyPattern.matches(key)
    }
}
