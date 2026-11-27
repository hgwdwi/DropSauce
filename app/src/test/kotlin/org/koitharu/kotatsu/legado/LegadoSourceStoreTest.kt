package org.koitharu.kotatsu.legado

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegadoSourceStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun source(name: String = "Fixture", url: String = "https://example.com"): LegadoSourcePreflight.Inspection =
        LegadoSourcePreflight.inspect("""
            {
              "bookSourceName":"$name",
              "bookSourceUrl":"$url",
              "searchUrl":"/search?q={{key}}",
              "ruleSearch":{"bookList":"@css:.book","name":"@css:h2@text","bookUrl":"@css:a@href"},
              "ruleToc":{"chapterList":"@css:.chapter","chapterName":"@css:a@text","chapterUrl":"@css:a@href"},
              "ruleContent":{"content":"@css:#content@html"}
            }
        """.trimIndent()).single()

    @Test fun persistsAndReloadsReviewedSources() {
        val store = LegadoSourceStore(folder.root)
        assertTrue(store.list().isEmpty())
        store.save(source())
        store.save(source("Second", "https://second.example"))
        assertEquals(listOf("Fixture", "Second"), LegadoSourceStore(folder.root).list().map { it.name })
    }

    @Test fun updatesSourceWithoutDuplicatesAndRemoves() {
        val store = LegadoSourceStore(folder.root)
        store.save(source())
        store.save(source("Renamed"))
        assertEquals("Renamed", store.list().single().name)
        assertTrue(store.remove("https://example.com"))
        assertFalse(store.remove("https://example.com"))
        assertTrue(LegadoSourceStore(folder.root).list().isEmpty())
    }

    @Test fun rejectsUnsupportedSourceWithoutDestroyingRegistry() {
        val store = LegadoSourceStore(folder.root)
        store.save(source())
        try {
            store.save(source("Invalid", "file:///sdcard"))
            fail("Unsupported source accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals("Fixture", store.list().single().name)
        }
    }
}
