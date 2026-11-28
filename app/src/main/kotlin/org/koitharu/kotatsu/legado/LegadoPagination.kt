package org.koitharu.kotatsu.legado

/** Explicit next-page traversal. Never guesses URLs. Cycles are ignored and limits fail closed. */
internal object LegadoPagination {
    suspend fun <T> collect(
        firstUrl: String,
        firstPage: String? = null,
        limit: Int,
        fetch: suspend (String) -> String,
        extract: (String, String) -> T,
        next: (String, String) -> List<String>,
    ): List<T> {
        require(limit in 1..100)
        val pending = ArrayDeque<String>()
        val seen = HashSet<String>()
        val result = ArrayList<T>()
        var totalChars = 0L
        pending.add(firstUrl)
        while (pending.isNotEmpty()) {
            val url = pending.removeFirst()
            if (!seen.add(url)) continue
            require(result.size < limit) { "Pagination page limit exceeded" }
            val page = if (url == firstUrl && firstPage != null) firstPage else fetch(url)
            totalChars += page.length
            require(totalChars <= 16L * 1024 * 1024) { "Pagination size limit exceeded" }
            result.add(extract(page, url))
            for (link in next(page, url)) {
                if (link !in seen && link !in pending) pending.addLast(link)
            }
        }
        return result
    }
}
