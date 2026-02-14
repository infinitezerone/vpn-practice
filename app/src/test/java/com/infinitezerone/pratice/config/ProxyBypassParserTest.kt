package com.infinitezerone.pratice.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyBypassParserTest {
    @Test
    fun `parse trims drops empty and deduplicates`() {
        val parsed = ProxyBypassParser.parse(" localhost, *.rakuten.co.jp, ,localhost,10.0.0.0/8 ")
        assertEquals(listOf("localhost", "*.rakuten.co.jp", "10.0.0.0/8"), parsed)
    }

    @Test
    fun `format joins with comma`() {
        val formatted = ProxyBypassParser.format(listOf("localhost", "*.example.com"))
        assertEquals("localhost,*.example.com", formatted)
    }
}
