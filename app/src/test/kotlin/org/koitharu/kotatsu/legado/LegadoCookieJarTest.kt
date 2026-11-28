package org.koitharu.kotatsu.legado

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LegadoCookieJarTest {
    private val url = "https://example.com/reader/chapter".toHttpUrl()

    @Test fun persistentCookiesSurviveJarRecreation() {
        val directory = Files.createTempDirectory("legado-cookie-test").toFile()
        val file = directory.resolve("source.json")
        val cookie = Cookie.Builder().name("session").value("abc").hostOnlyDomain("example.com").path("/")
            .expiresAt(System.currentTimeMillis() + 60_000).httpOnly().secure().build()
        LegadoCookieJar(file).saveFromResponse(url, listOf(cookie))
        val restored = LegadoCookieJar(file)
        assertEquals("abc", restored.loadForRequest(url).single().value)
        restored.clear()
        assertEquals(0, restored.size())
        directory.deleteRecursively()
    }

    @Test fun sessionCookiesAreMemoryOnly() {
        val directory = Files.createTempDirectory("legado-cookie-session").toFile()
        val file = directory.resolve("source.json")
        val cookie = Cookie.Builder().name("sid").value("memory").hostOnlyDomain("example.com").path("/").build()
        val first = LegadoCookieJar(file)
        first.saveFromResponse(url, listOf(cookie))
        assertEquals(1, first.loadForRequest(url).size)
        assertEquals(0, LegadoCookieJar(file).loadForRequest(url).size)
        directory.deleteRecursively()
    }

    @Test fun cookiesRespectDomainPathAndSecureScope() {
        val jar = LegadoCookieJar()
        val hostOnly = Cookie.Builder().name("host").value("1").hostOnlyDomain("example.com").path("/reader").secure().build()
        val domain = Cookie.Builder().name("domain").value("2").domain("example.com").path("/").build()
        jar.saveFromResponse(url, listOf(hostOnly, domain))
        assertEquals(setOf("host", "domain"), jar.loadForRequest(url).map { it.name }.toSet())
        assertEquals(setOf("domain"), jar.loadForRequest("https://sub.example.com/".toHttpUrl()).map { it.name }.toSet())
        assertTrue(jar.loadForRequest("http://example.com/reader".toHttpUrl()).none { it.name == "host" })
    }

    @Test fun expiredCookiesAreDiscardedAndClearRemovesPersistence() {
        val directory = Files.createTempDirectory("legado-cookie-expired").toFile()
        val file = directory.resolve("source.json")
        val jar = LegadoCookieJar(file)
        val expired = Cookie.Builder().name("old").value("x").hostOnlyDomain("example.com").path("/")
            .expiresAt(System.currentTimeMillis() - 1000).build()
        jar.saveFromResponse(url, listOf(expired))
        assertEquals(0, jar.size())
        val live = Cookie.Builder().name("new").value("x").hostOnlyDomain("example.com").path("/")
            .expiresAt(System.currentTimeMillis() + 60_000).build()
        jar.saveFromResponse(url, listOf(live))
        assertTrue(file.exists())
        jar.clear()
        assertFalse(file.exists())
        directory.deleteRecursively()
    }

    @Test fun sourceInstancesShareSessionButRemainIsolated() {
        val directory = Files.createTempDirectory("legado-cookie-isolation").toFile()
        val first = LegadoCookieJar.forSource(directory, "https://example.com/source-a")
        val same = LegadoCookieJar.forSource(directory, "https://example.com/source-a")
        val other = LegadoCookieJar.forSource(directory, "https://example.com/source-b")
        val session = Cookie.Builder().name("sid").value("one").hostOnlyDomain("example.com").path("/").build()
        first.saveFromResponse(url, listOf(session))
        assertEquals("one", same.loadForRequest(url).single().value)
        assertTrue(other.loadForRequest(url).isEmpty())
        first.clear()
        directory.deleteRecursively()
    }
}
