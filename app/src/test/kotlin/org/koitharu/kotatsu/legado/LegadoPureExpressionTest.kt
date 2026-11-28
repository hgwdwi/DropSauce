package org.koitharu.kotatsu.legado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegadoPureExpressionTest {

    @Test
    fun javaGetReturnsExistingAndMissingValues() {
        val vars = LegadoVariables(mapOf("chapter" to "12"))
        assertEquals("12", LegadoPureExpression.parse("java.get('chapter')").evaluate("", vars))
        assertEquals("", LegadoPureExpression.parse("java.get('missing')").evaluate("", vars))
    }

    @Test
    fun javaPutStoresAndReturnsStringValue() {
        val vars = LegadoVariables()
        val result = LegadoPureExpression.parse("java.put('chapter', 12)").evaluate("", vars)
        assertEquals("12", result)
        assertEquals("12", vars.get("chapter"))
    }

    @Test
    fun javaPutCanStoreDerivedResultAndReadItBack() {
        val vars = LegadoVariables()
        val expression = LegadoPureExpression.parse("java.put('title', result.trim()) + java.get('title')")
        assertEquals("NovelNovel", expression.evaluate("  Novel  ", vars))
        assertEquals("Novel", vars.get("title"))
    }

    @Test
    fun javaPutDoesNotTreatPrototypeNamesAsObjectProperties() {
        val vars = LegadoVariables()
        assertEquals("safe", LegadoPureExpression.parse("java.put('__proto__', 'safe')").evaluate("", vars))
        assertEquals("safe", vars.get("__proto__"))
        assertTrue(vars.snapshot().containsKey("__proto__"))
    }

    @Test
    fun rejectsUnknownJavaBridgeMethods() {
        assertTrue(runCatching { LegadoPureExpression.parse("java.ajax('https://example.com')") }.isFailure)
        assertTrue(runCatching { LegadoPureExpression.parse("java.get(result)") }.isFailure)
    }

    @Test
    fun rejectsDynamicVariableNamesAndExcessiveInitialState() {
        assertTrue(runCatching { LegadoPureExpression.parse("java.put(result, 'x')") }.isFailure)
        assertTrue(runCatching { LegadoVariables((0 until 65).associate { "v$it" to "x" }) }.isFailure)
    }
}
