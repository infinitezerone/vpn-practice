package com.infinitezerone.pratice.vpn

import java.net.InetSocketAddress
import java.net.Socket

object ProxyConnectivityChecker {
    fun testConnection(host: String, port: Int, timeoutMs: Int = 3_000): String? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            null
        } catch (e: Exception) {
            "Cannot connect to proxy $host:$port (${e.javaClass.simpleName})."
        }
    }
}
