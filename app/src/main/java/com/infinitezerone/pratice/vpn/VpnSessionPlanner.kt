package com.infinitezerone.pratice.vpn

import com.infinitezerone.pratice.config.HttpTrafficMode
import com.infinitezerone.pratice.config.ProxyProtocol
import com.infinitezerone.pratice.config.RoutingMode

data class VpnSessionPlan(
    val safeHost: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val httpTrafficMode: HttpTrafficMode,
    val proxyBypassRules: List<String>,
    val selectedPackages: Set<String>,
    val routingMode: RoutingMode
) {
    val enableMappedDns: Boolean
        get() = protocol == ProxyProtocol.Http

    val allowDirectFallbackForNonHttpPorts: Boolean
        get() = httpTrafficMode == HttpTrafficMode.CompatFallback
}

object VpnSessionPlanner {
    fun createPlan(
        activeHost: String?,
        activePort: Int,
        activeProtocol: ProxyProtocol,
        httpTrafficMode: HttpTrafficMode,
        proxyBypassRules: List<String>,
        selectedPackages: Set<String>,
        routingMode: RoutingMode
    ): VpnSessionPlan? {
        val normalizedHost = activeHost?.trim()
        if (normalizedHost.isNullOrBlank() || activePort !in 1..65535) {
            return null
        }
        return VpnSessionPlan(
            safeHost = EndpointSanitizer.sanitizeHost(normalizedHost),
            port = activePort,
            protocol = activeProtocol,
            httpTrafficMode = httpTrafficMode,
            proxyBypassRules = proxyBypassRules,
            selectedPackages = selectedPackages,
            routingMode = routingMode
        )
    }
}
