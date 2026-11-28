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
    fun javaAjaxUsesInjectedHandlerAndAllowsBoundedUrlExpressions() {
        val calls = ArrayList<Pair<String, Int?>>()
        val vars = LegadoVariables(ajaxRequest = { url, timeout ->
            calls += url to timeout
            "response:$url"
        })
        val rule = LegadoPureExpression.parse("java.ajax('https://example.com/' + result.trim(), 5000)")
        assertEquals("response:https://example.com/42", rule.evaluate(" 42 ", vars))
        assertEquals(listOf("https://example.com/42" to 5000), calls)
        val noTimeout = LegadoPureExpression.parse("java.ajax('/title')")
        assertEquals("response:/title", noTimeout.evaluate("", vars))
        assertEquals(null, calls.last().second)
    }

    @Test
    fun javaAjaxRejectsUnsafeArgumentsAndMissingNetworkBridge() {
        assertTrue(runCatching { LegadoPureExpression.parse("java.ajax(42)") }.isFailure)
        assertTrue(runCatching { LegadoPureExpression.parse("java.ajax('/x', result)") }.isFailure)
        assertTrue(runCatching { LegadoPureExpression.parse("java.get(result)") }.isFailure)
        assertTrue(runCatching {
            LegadoPureExpression.parse("java.ajax('/x')").evaluate("", LegadoVariables())
        }.isFailure)
    }

    @Test
    fun rejectsDynamicVariableNamesAndExcessiveInitialState() {
        assertTrue(runCatching { LegadoPureExpression.parse("java.put(result, 'x')") }.isFailure)
        assertTrue(runCatching { LegadoVariables((0 until 65).associate { "v$it" to "x" }) }.isFailure)
    }
}
