package org.koitharu.kotatsu.legado

import org.junit.Assert.assertEquals
import org.junit.Test

class LegadoDynamicRuleTest {

    @Test
    fun jsRuleCanWriteAndReadRuleVariables() {
        val vars = LegadoVariables()
        val rule = LegadoDynamicRule.parse("@js:java.put('page', '2') + java.get('page')")
        assertEquals(listOf("22"), rule.evaluate("unused", vars))
        assertEquals("2", vars.get("page"))
    }

    @Test
    fun interpolationCanUseTheSameVariableBridge() {
        val vars = LegadoVariables()
        val rule = LegadoDynamicRule.parse("chapter={{java.put('chapter', result.trim())}}/{{java.get('chapter')}}")
        assertEquals(listOf("chapter=Novel/Novel"), rule.evaluate(" Novel ", vars))
        assertEquals("Novel", vars.get("chapter"))
    }

    @Test
    fun dynamicExpressionCannotUseNetworkWithoutAnInjectedReader() {
        val vars = LegadoVariables()
        val rule = LegadoDynamicRule.parse("@js:java.ajax('https://example.com')")
        org.junit.Assert.assertTrue(runCatching { rule.evaluate("unused", vars) }.isFailure)
        assertEquals(emptyMap<String, String>(), vars.snapshot())
    }
}
