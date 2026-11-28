package org.koitharu.kotatsu.local.data

import java.io.File
import java.util.Locale

/**
 * A presentation-level classification for the local library.
 *
 * This deliberately does not inspect or mutate Manga tags or the persisted index. Downloaded
 * novels and comics can both contain index.json, so the index is not a reliable discriminator.
 */
internal enum class LocalShelf {
    ALL,
    COMICS,
    NOVELS,
}

internal object LocalShelfClassifier {

    fun classify(file: File): LocalShelf {
        if (!file.isDirectory) return classifyExtension(file.extension)
        val children = file.listFiles() ?: return LocalShelf.ALL
        // LocalMangaParser treats a directory containing EPUBs as an EPUB collection.
        if (children.any { it.isFile && it.extension.equals("epub", ignoreCase = true) }) {
            return LocalShelf.NOVELS
        }
        return if (children.any { child ->
                child.isDirectory || child.isFile && classifyExtension(child.extension) == LocalShelf.COMICS
            }) {
            LocalShelf.COMICS
        } else {
            LocalShelf.ALL
        }
    }

    private fun classifyExtension(extension: String): LocalShelf = when (extension.lowercase(Locale.ROOT)) {
        "epub" -> LocalShelf.NOVELS
        in COMIC_EXTENSIONS -> LocalShelf.COMICS
        else -> LocalShelf.ALL
    }

    private val COMIC_EXTENSIONS = setOf("cbz", "cbr", "zip", "pdf", "jpg", "jpeg", "png", "webp", "gif", "avif")
}
