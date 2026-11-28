package org.koitharu.kotatsu.local.data

import java.io.File
import java.util.Locale

/**
 * A presentation-level classification for the local library.
 *
 * This deliberately does not inspect or mutate Manga tags or the persisted index. Downloaded
 * novels and comics can both contain index.json, so the index is not a reliable discriminator.
 */
enum class LocalShelf {
    ALL,
    COMICS,
    NOVELS,
}

internal object LocalShelfClassifier {

    fun classify(file: File): LocalShelf {
        if (!file.isDirectory) return classifyArchive(file.extension)
        // LocalMangaParser recognises EPUB collections only when EPUBs are direct children.
        val children = file.listFiles() ?: return LocalShelf.ALL
        if (children.any { it.isFile && it.extension.equals("epub", ignoreCase = true) }) {
            return LocalShelf.NOVELS
        }
        // Image folders can contain nested chapter directories. Do not label arbitrary folders
        // as comics merely because they contain a subdirectory.
        return if (file.walkTopDown().any { child ->
                child.isFile && (
                    classifyArchive(child.extension) == LocalShelf.COMICS ||
                        hasImageExtension(child.name)
                    )
            }) {
            LocalShelf.COMICS
        } else {
            LocalShelf.ALL
        }
    }

    private fun classifyArchive(extension: String): LocalShelf = when (extension.lowercase(Locale.ROOT)) {
        "epub" -> LocalShelf.NOVELS
        in COMIC_ARCHIVE_EXTENSIONS -> LocalShelf.COMICS
        else -> LocalShelf.ALL
    }

    private val COMIC_ARCHIVE_EXTENSIONS = setOf("cbz", "cbr", "zip", "pdf")
}
