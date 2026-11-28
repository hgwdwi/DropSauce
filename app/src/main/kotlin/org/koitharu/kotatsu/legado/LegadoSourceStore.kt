package org.koitharu.kotatsu.legado

import kotlinx.serialization.json.JsonArray
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Private file-backed source registry. Import inspection has no side effects;
 * saving requires an explicit call with an individually reviewed source.
 * Unsupported sources are never registered for network access.
 */
internal class LegadoSourceStore(private val directory: File) {

    private val registry = File(directory, "legado-sources.json")

    @Synchronized
    fun list(): List<LegadoSourcePreflight.Inspection> {
        if (!registry.exists()) return emptyList()
        if (registry.length() > MAX_BYTES) throw IOException("Source registry exceeds size limit")
        val data = registry.readText(Charsets.UTF_8)
        if (data == "[]") return emptyList()
        return LegadoSourcePreflight.inspect(data)
    }

    @Synchronized
    fun save(source: LegadoSourcePreflight.Inspection) {
        // Revalidate original JSON, rather than trusting a copied inspection.
        val validated = LegadoSourcePreflight.inspect(source.original.toString()).single()
        require(source.supportedSubset && validated.supportedSubset) { "Unsupported source" }
        val existing = list().filterNot { it.url == validated.url }
        write(existing + validated)
    }

    @Synchronized
    fun remove(url: String): Boolean {
        val existing = list()
        val remaining = existing.filterNot { it.url == url }
        if (remaining.size == existing.size) return false
        write(remaining)
        return true
    }

    private fun write(sources: List<LegadoSourcePreflight.Inspection>) {
        val data = JsonArray(sources.map { it.original }).toString()
        val bytes = data.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        // Recheck aggregate limits and unique source IDs before replacing the old registry.
        if (sources.isNotEmpty()) {
            require(LegadoSourcePreflight.inspect(data).all { it.supportedSubset })
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create source directory")
        val stage = File.createTempFile("legado-", ".tmp", directory)
        try {
            stage.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(stage.toPath(), registry.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            stage.delete()
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
