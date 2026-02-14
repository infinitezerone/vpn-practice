package com.infinitezerone.pratice.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpBypassRuleMatcherTest {
    @Test
    fun matchesExactAndWildcardRules() {
        val rules = listOf("api.example.com", "*.googleapis.com", "<local>")

        assertEquals("api.example.com", HttpBypassRuleMatcher.findMatchingRule("api.example.com", rules))
        assertEquals("*.googleapis.com", HttpBypassRuleMatcher.findMatchingRule("android.googleapis.com", rules))
        assertEquals("<local>", HttpBypassRuleMatcher.findMatchingRule("printer", rules))
    }

    @Test
    fun returnsNullWhenNoRuleMatches() {
        val rules = listOf("*.googleapis.com")
        assertNull(HttpBypassRuleMatcher.findMatchingRule("example.org", rules))
    }
}
