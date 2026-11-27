package org.koitharu.kotatsu.legado

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.util.await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Explicit user-triggered requests for a supported Legado source.
 * Isolated client: no app authentication, cookies, scripts or redirects.
 */
internal class LegadoHttpReader(private val source: LegadoSourcePreflight.Inspection) {

    private val reader = LegadoCssReader(source)
    private val origin = source.url.toHttpUrl()

    suspend fun search(query: String, page: Int = 1): List<LegadoCssReader.Book> {
        val url = reader.searchUrl(query, page)
        return reader.searchResults(fetch(url), url)
    }

    suspend fun chapters(book: LegadoCssReader.Book): List<LegadoCssReader.Chapter> =
        reader.chapters(fetch(book.url), book.url)

    suspend fun content(chapter: LegadoCssReader.Chapter): String =
        reader.chapterHtml(fetch(chapter.url), chapter.url)

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val target = url.toHttpUrl()
        require(target.scheme == origin.scheme && target.host == origin.host &&
            target.port == origin.port && target.username.isEmpty() && target.password.isEmpty()) {
            "Cross-origin book request is not supported"
        }
        val request = Request.Builder()
            .url(target)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "TsubakiReader/0.4")
            .get().build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            val body = response.body ?: throw IOException("Empty response")
            val media = body.contentType()
            if (media != null && media.type != "text" &&
                !(media.type == "application" && media.subtype == "xhtml+xml")) {
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
            // Jsoup detects BOM and HTML meta charset when HTTP has no charset.
            val charset = media?.charset()?.name()
            Jsoup.parse(ByteArrayInputStream(bytes.toByteArray()), charset, target.toString()).outerHtml()
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}
