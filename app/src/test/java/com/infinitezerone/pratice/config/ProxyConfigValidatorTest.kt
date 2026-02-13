package com.infinitezerone.pratice.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigValidatorTest {
    @Test
    fun `returns error when host is blank`() {
        val result = ProxyConfigValidator.validate("", "8080")
        assertEquals("Proxy host is required.", result)
    }

    @Test
    fun `returns error when port is not numeric`() {
        val result = ProxyConfigValidator.validate("1.2.3.4", "abc")
        assertEquals("Proxy port must be a number.", result)
    }

    @Test
    fun `returns error when port is out of range`() {
        val result = ProxyConfigValidator.validate("1.2.3.4", "70000")
        assertEquals("Proxy port must be between 1 and 65535.", result)
    }

    @Test
    fun `returns null for valid host and port`() {
        val result = ProxyConfigValidator.validate("proxy.local", "1080")
        assertNull(result)
    }
}
