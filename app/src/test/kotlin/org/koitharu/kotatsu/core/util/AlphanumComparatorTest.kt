package org.koitharu.kotatsu.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphanumComparatorTest {

    @Test
    fun sortsPageAndChapterNumbersNaturally() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "Chapter 10", "Chapter 2", "Chapter 1")
        assertEquals(
            listOf("1.jpg", "2.jpg", "10.jpg", "Chapter 1", "Chapter 2", "Chapter 10"),
            names.sortedWith(AlphanumComparator()),
        )
    }
}
