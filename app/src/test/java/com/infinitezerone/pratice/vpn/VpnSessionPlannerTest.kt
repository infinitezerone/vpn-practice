package com.infinitezerone.pratice.vpn

import com.infinitezerone.pratice.config.HttpTrafficMode
import com.infinitezerone.pratice.config.ProxyProtocol
import com.infinitezerone.pratice.config.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSessionPlannerTest {
    @Test
    fun returnsNullWhenHostIsInvalid() {
        val plan = VpnSessionPlanner.createPlan(
            activeHost = "  ",
            activePort = 9027,
            activeProtocol = ProxyProtocol.Http,
            httpTrafficMode = HttpTrafficMode.CompatFallback,
            proxyBypassRules = listOf("*.example.com"),
            selectedPackages = setOf("com.example.app"),
            routingMode = RoutingMode.Bypass
        )

        assertNull(plan)
    }

    @Test
    fun sanitizesHostAndKeepsRoutingInputs() {
        val plan = VpnSessionPlanner.createPlan(
            activeHost = "  http://user:pass@stg-proxy.travel.rakuten.co.jp  ",
            activePort = 9027,
            activeProtocol = ProxyProtocol.Http,
            httpTrafficMode = HttpTrafficMode.StrictProxy,
            proxyBypassRules = listOf("*.rakuten.co.jp"),
            selectedPackages = setOf("com.example.a", "com.example.b"),
            routingMode = RoutingMode.Allowlist
        )

        requireNotNull(plan)
        assertEquals("stg-proxy.travel.rakuten.co.jp", plan.safeHost)
        assertEquals(9027, plan.port)
        assertEquals(ProxyProtocol.Http, plan.protocol)
        assertEquals(HttpTrafficMode.StrictProxy, plan.httpTrafficMode)
        assertEquals(listOf("*.rakuten.co.jp"), plan.proxyBypassRules)
        assertEquals(setOf("com.example.a", "com.example.b"), plan.selectedPackages)
        assertEquals(RoutingMode.Allowlist, plan.routingMode)
    }

    @Test
    fun exposesDnsAndFallbackFlagsFromProtocolAndMode() {
        val httpPlan = VpnSessionPlanner.createPlan(
            activeHost = "proxy.example",
            activePort = 8080,
            activeProtocol = ProxyProtocol.Http,
            httpTrafficMode = HttpTrafficMode.CompatFallback,
            proxyBypassRules = emptyList(),
            selectedPackages = emptySet(),
            routingMode = RoutingMode.Bypass
        )
        val socksPlan = VpnSessionPlanner.createPlan(
            activeHost = "proxy.example",
            activePort = 1080,
            activeProtocol = ProxyProtocol.Socks5,
            httpTrafficMode = HttpTrafficMode.StrictProxy,
            proxyBypassRules = emptyList(),
            selectedPackages = emptySet(),
            routingMode = RoutingMode.Bypass
        )

        requireNotNull(httpPlan)
        requireNotNull(socksPlan)
        assertTrue(httpPlan.enableMappedDns)
        assertTrue(httpPlan.allowDirectFallbackForNonHttpPorts)
        assertFalse(socksPlan.enableMappedDns)
        assertFalse(socksPlan.allowDirectFallbackForNonHttpPorts)
    }
}
