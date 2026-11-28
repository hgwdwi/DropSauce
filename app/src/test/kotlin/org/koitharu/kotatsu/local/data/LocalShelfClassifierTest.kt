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
    fun doesNotTreatAnImageFileAsAStandaloneComicBook() {
        assertEquals(LocalShelf.ALL, LocalShelfClassifier.classify(File("cover.jpg")))
    }

    @Test
    fun classifiesDirectoriesContainingEpubCollectionsAsNovels() {
        val directory = Files.createTempDirectory("novel-shelf-test").toFile()
        try {
            File(directory, "Volume 1.epub").createNewFile()
            assertEquals(LocalShelf.NOVELS, LocalShelfClassifier.classify(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun classifiesImageDirectoriesAsComics() {
        val directory = Files.createTempDirectory("comic-shelf-test").toFile()
        try {
            File(directory, "001.jpg").createNewFile()
            assertEquals(LocalShelf.COMICS, LocalShelfClassifier.classify(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun classifiesNestedImageChapterFoldersAsComics() {
        val directory = Files.createTempDirectory("nested-comic-shelf-test").toFile()
        try {
            val chapter = File(directory, "Chapter 1")
            assertEquals(true, chapter.mkdir())
            File(chapter, "001.webp").createNewFile()
            assertEquals(LocalShelf.COMICS, LocalShelfClassifier.classify(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsUnknownFilesAndEmptyDirectoriesInAllShelf() {
        listOf("book.txt", "archive.rar", "README").forEach {
            assertEquals(it, LocalShelf.ALL, LocalShelfClassifier.classify(File(it)))
        }
        val directory = Files.createTempDirectory("unknown-shelf-test").toFile()
        try {
            File(directory, "unrelated").mkdir()
            assertEquals(LocalShelf.ALL, LocalShelfClassifier.classify(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
