package org.koitharu.kotatsu.legado

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.util.await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Explicit user-triggered requests for a supported Legado source.
 * Per-source HTTP client with isolated cookies and same-origin-only redirects.
 */
internal class LegadoHttpReader(
    private val source: LegadoSourcePreflight.Inspection,
    cookieDirectory: File? = null,
) {

    private val reader = LegadoCssReader(source)
    private val origin = source.url.toHttpUrl()
    private val cookieJar = cookieDirectory?.let { LegadoCookieJar.forSource(it, source.url) } ?: LegadoCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    fun clearCookies() = cookieJar.clear()


    suspend fun search(query: String, page: Int = 1): List<LegadoCssReader.Book> {
        val spec = reader.searchRequest(query, page)
        return reader.searchResults(fetchSpec(spec), spec.url)
    }

    data class BookDirectory(val book: LegadoCssReader.Book, val chapters: List<LegadoCssReader.Chapter>)

    suspend fun directory(book: LegadoCssReader.Book): BookDirectory {
        val detailHtml = fetch(book.url)
        val details = reader.details(book, detailHtml, book.url)
        val tocHtml = if (details.tocUrl == book.url) detailHtml else fetch(details.tocUrl)
        val pages = LegadoPagination.collect(details.tocUrl, tocHtml, 20, ::fetch,
            { html, url -> reader.chapters(html, url, details.book.variables) }, reader::nextDirectoryUrls)
        val chapters = pages.flatten().distinctBy { it.url }
        require(chapters.size <= 10000) { "Directory exceeds chapter limit" }
        return BookDirectory(details.book, if (reader.reverseDirectory()) chapters.reversed() else chapters)
    }

    suspend fun chapters(book: LegadoCssReader.Book): List<LegadoCssReader.Chapter> = directory(book).chapters

    suspend fun content(chapter: LegadoCssReader.Chapter): String {
        val pages = LegadoPagination.collect(chapter.url, limit = 10, fetch = ::fetch,
            extract = { html, url -> reader.chapterHtml(html, url, chapter.variables) }, next = reader::nextContentUrls)
        val html = pages.joinToString("\n")
        require(html.length <= 4 * 1024 * 1024) { "Combined chapter exceeds size limit" }
        return reader.cleanCombinedContent(html)
    }

    private suspend fun fetch(url: String): String = fetchSpec(LegadoRequestRule.Spec(url, headers = LegadoRequestRule.sourceHeaders(source.original)))

    private suspend fun fetchSpec(spec: LegadoRequestRule.Spec): String = withContext(Dispatchers.IO) {
        var current = spec.url.toHttpUrl()
        require(LegadoRedirectPolicy.isSameOrigin(origin, current)) { "Cross-origin book request is not supported" }
        var method = spec.method
        var requestBody = spec.body
        var redirects = 0
        while (true) {
            val builder = Request.Builder()
                .url(current)
                .header("Accept", "text/html,application/xhtml+xml,application/json")
                .header("User-Agent", "TsubakiReader/0.4")
            spec.headers.forEach { (key, value) -> builder.header(key, value) }
            if (method == "POST") {
                val type = (spec.headers["content-type"] ?: "application/x-www-form-urlencoded").toMediaType()
                builder.post(requireNotNull(requestBody).toRequestBody(type))
            } else builder.get()
            val response = client.newCall(builder.build()).await()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val target = LegadoRedirectPolicy.resolve(
                    origin, response.request.url, response.code, response.header("Location"), method, requestBody,
                )
                response.close()
                require(++redirects <= MAX_REDIRECTS) { "Too many redirects" }
                current = target.url
                method = target.method
                requestBody = target.body
                continue
            }
            return@withContext response.use { finalResponse ->
                if (!finalResponse.isSuccessful) throw IOException("HTTP " + finalResponse.code)
                val body = finalResponse.body ?: throw IOException("Empty response")
                val media = body.contentType()
                if (media != null && media.type != "text" &&
                    !(media.type == "application" && (media.subtype == "xhtml+xml" ||
                        media.subtype == "json" || media.subtype.endsWith("+json")))) {
                    throw IOException("Unsupported response type")
                }
                if (body.contentLength() > MAX_BYTES) throw IOException("HTML exceeds size limit")
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { stream ->
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (bytes.size() > MAX_BYTES - n) throw IOException("HTML exceeds size limit")
                        bytes.write(buffer, 0, n)
                    }
                }
                val charset = spec.charset?.name() ?: media?.charset()?.name()
                val raw = bytes.toByteArray()
                val decoded = raw.toString(spec.charset ?: media?.charset() ?: Charsets.UTF_8)
                val leading = decoded.trimStart().removePrefix("\uFEFF")
                if (leading.startsWith("{") || leading.startsWith("[")) decoded
                else Jsoup.parse(ByteArrayInputStream(raw), charset, current.toString()).outerHtml()
            }
        }
        throw IOException("Redirect loop")
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
    }
}
