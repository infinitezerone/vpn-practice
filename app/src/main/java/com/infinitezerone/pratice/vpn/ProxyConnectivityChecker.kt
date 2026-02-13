package com.infinitezerone.pratice.vpn

import java.net.InetSocketAddress
import java.net.Socket

object ProxyConnectivityChecker {
    fun testConnection(host: String, port: Int, timeoutMs: Int = 3_000): String? {
        val safeHost = EndpointSanitizer.sanitizeHost(host)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(safeHost, port), timeoutMs)
            }
            null
        } catch (e: Exception) {
            "Cannot connect to proxy $safeHost:$port (${e.javaClass.simpleName})."
        }
    }
}
