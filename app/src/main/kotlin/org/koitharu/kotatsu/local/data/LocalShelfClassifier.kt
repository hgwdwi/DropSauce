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

    fun classify(file: File): LocalShelf = when {
        file.isDirectory -> LocalShelf.COMICS
        file.extension.lowercase(Locale.ROOT) == "epub" -> LocalShelf.NOVELS
        file.extension.lowercase(Locale.ROOT) in COMIC_EXTENSIONS -> LocalShelf.COMICS
        else -> LocalShelf.ALL
    }

    private val COMIC_EXTENSIONS = setOf("cbz", "cbr", "zip", "pdf")
}
