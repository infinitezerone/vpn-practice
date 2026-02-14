package com.infinitezerone.pratice.vpn

import java.net.InetSocketAddress
import java.net.Socket

object ProxyConnectivityChecker {
    fun testConnection(
        host: String,
        port: Int,
        timeoutMs: Int = 3_000,
        protectSocket: ((Socket) -> Boolean)? = null,
        connectAddress: InetSocketAddress? = null
    ): String? {
        val safeHost = EndpointSanitizer.sanitizeHost(host)
        val targetAddress = connectAddress ?: InetSocketAddress(safeHost, port)
        return try {
            Socket().use { socket ->
                if (protectSocket != null && !protectSocket.invoke(socket)) {
                    return "Cannot protect proxy socket for $safeHost:$port."
                }
                socket.connect(targetAddress, timeoutMs)
            }
            null
        } catch (e: Exception) {
            "Cannot connect to proxy $safeHost:$port (${e.javaClass.simpleName})."
        }
    }
}
