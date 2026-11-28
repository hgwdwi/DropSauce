package org.koitharu.kotatsu.local.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalShelfClassifierTest {

    @Test
    fun classifiesEpubAsNovelCaseInsensitively() {
        assertEquals(LocalShelf.NOVELS, LocalShelfClassifier.classify(File("Book.epub")))
        assertEquals(LocalShelf.NOVELS, LocalShelfClassifier.classify(File("Book.EPUB")))
    }

    @Test
    fun classifiesComicArchivesAndPdfAsComics() {
        listOf("comic.cbz", "comic.cbr", "comic.zip", "comic.pdf", "comic.CBZ").forEach {
            assertEquals(it, LocalShelf.COMICS, LocalShelfClassifier.classify(File(it)))
        }
    }

    @Test
    fun classifiesDirectoriesAsComics() {
        val directory = Files.createTempDirectory("comic-shelf-test").toFile()
        try {
            assertEquals(LocalShelf.COMICS, LocalShelfClassifier.classify(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsUnknownFilesInAllShelf() {
        listOf("book.txt", "archive.rar", "cover.png", "README").forEach {
            assertEquals(it, LocalShelf.ALL, LocalShelfClassifier.classify(File(it)))
        }
    }
}
