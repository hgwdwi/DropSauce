package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Source-isolated cookies. Session cookies remain in memory; persistent cookies use app-private storage. */
internal class LegadoCookieJar(private val file: File? = null) : CookieJar {
    private val cookies = LinkedHashMap<String, Cookie>()

    init { loadPersistent() }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        var changed = false
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            require(cookie.name.length <= 256 && cookie.value.length <= MAX_COOKIE_CHARS) { "Cookie exceeds size limit" }
            val key = key(cookie)
            if (cookie.expiresAt <= now) changed = this.cookies.remove(key) != null || changed
            else {
                this.cookies[key] = cookie
                changed = true
            }
        }
        if (pruneExpired(now)) changed = true
        while (this.cookies.size > MAX_COOKIES) {
            this.cookies.remove(this.cookies.keys.first())
            changed = true
        }
        if (changed) persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (pruneExpired(System.currentTimeMillis())) persist()
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        file?.let { if (it.exists() && !it.delete()) throw IOException("Cannot clear source cookies") }
    }

    @Synchronized
    fun size(): Int {
        if (pruneExpired(System.currentTimeMillis())) persist()
        return cookies.size
    }

    private fun loadPersistent() {
        val target = file ?: return
        if (!target.isFile) return
        try {
            require(target.length() <= MAX_FILE_BYTES)
            val root = Json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonArray
                ?: error("Invalid cookie store")
            for (entry in root.take(MAX_COOKIES)) {
                val obj = entry as? JsonObject ?: continue
                val name = obj.string("name")
                val value = obj.string("value")
                val domain = obj.string("domain")
                val path = obj.string("path")
                val expiresAt = obj.long("expiresAt")
                if (name.isBlank() || domain.isBlank() || path.isBlank() ||
                    expiresAt <= System.currentTimeMillis() || value.length > MAX_COOKIE_CHARS) continue
                val builder = Cookie.Builder().name(name).value(value).path(path).expiresAt(expiresAt)
                if (obj.boolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
                if (obj.boolean("secure")) builder.secure()
                if (obj.boolean("httpOnly")) builder.httpOnly()
                runCatching { builder.build() }.getOrNull()?.let { cookies[key(it)] = it }
            }
            pruneExpired(System.currentTimeMillis())
        } catch (_: Exception) {
            cookies.clear()
            target.delete()
        }
    }

    private fun pruneExpired(now: Long): Boolean {
        val expired = cookies.filterValues { it.expiresAt <= now }.keys
        expired.forEach(cookies::remove)
        return expired.isNotEmpty()
    }

    private fun persist() {
        val target = file ?: return
        val persistent = cookies.values.filter { it.persistent }.take(MAX_COOKIES)
        if (persistent.isEmpty()) {
            if (target.exists() && !target.delete()) throw IOException("Cannot remove expired cookie store")
            return
        }
        val json = JsonArray(persistent.map { cookie ->
            buildJsonObject {
                put("name", JsonPrimitive(cookie.name))
                put("value", JsonPrimitive(cookie.value))
                put("domain", JsonPrimitive(cookie.domain))
                put("path", JsonPrimitive(cookie.path))
                put("expiresAt", JsonPrimitive(cookie.expiresAt))
                put("hostOnly", JsonPrimitive(cookie.hostOnly))
                put("secure", JsonPrimitive(cookie.secure))
                put("httpOnly", JsonPrimitive(cookie.httpOnly))
            }
        }).toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        val parent = target.parentFile ?: throw IOException("Cookie store has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create cookie directory")
        val stage = File.createTempFile("cookies-", ".tmp", parent)
        try {
            stage.outputStream().use { out -> out.write(bytes); out.fd.sync() }
            if (!stage.setReadable(false, false) || !stage.setReadable(true, true) ||
                !stage.setWritable(false, false) || !stage.setWritable(true, true)) {
                throw IOException("Cannot restrict cookie file permissions")
            }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            stage.delete()
        }
    }

    private fun key(cookie: Cookie): String = cookie.domain + "\n" + cookie.path + "\n" + cookie.name
    private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

    companion object {
        private const val MAX_COOKIES = 300
        private const val MAX_COOKIE_CHARS = 4096
        private const val MAX_FILE_BYTES = 256 * 1024
        private val instances = object : LinkedHashMap<String, LegadoCookieJar>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LegadoCookieJar>?): Boolean = size > 128
        }

        @Synchronized
        fun forSource(directory: File, sourceUrl: String): LegadoCookieJar {
            require(sourceUrl.isNotBlank())
            val digest = MessageDigest.getInstance("SHA-256").digest(sourceUrl.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val target = File(directory, "$digest.json")
            return instances[target.absolutePath] ?: LegadoCookieJar(target).also { instances[target.absolutePath] = it }
        }
    }
}
