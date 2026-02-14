package com.infinitezerone.pratice.vpn

import com.infinitezerone.pratice.config.ProxyProtocol
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay

class ProxyProbeCoordinator(
    private val logger: (String) -> Unit,
    private val resolveAddress: (String, Int) -> InetSocketAddress?,
    private val bypassVpnForSocket: (Socket) -> Boolean,
    private val hasActiveTunnel: () -> Boolean,
    private val testConnection: (
        host: String,
        port: Int,
        protocol: ProxyProtocol,
        protectSocket: ((Socket) -> Boolean)?,
        connectAddress: InetSocketAddress
    ) -> String? = { host, port, protocol, protectSocket, connectAddress ->
        ProxyConnectivityChecker.testConnection(
            host = host,
            port = port,
            protocol = protocol,
            protectSocket = protectSocket,
            connectAddress = connectAddress
        )
    },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun waitForProxyWithRetry(host: String, port: Int, protocol: ProxyProtocol): String? {
        val maxAttempts = 3
        var backoffMs = 1_000L
        var lastError: String? = null

        for (attempt in 1..maxAttempts) {
            logger("Proxy connectivity check $attempt/$maxAttempts")
            val protector = if (hasActiveTunnel()) {
                { socket: Socket -> bypassVpnForSocket(socket) }
            } else {
                null
            }
            val connectAddress = resolveAddress(host, port)
            if (connectAddress == null) {
                lastError = "Cannot resolve proxy ${EndpointSanitizer.sanitizeHost(host)}:$port."
                if (attempt < maxAttempts) {
                    logger("Proxy DNS resolution failed, retrying in ${backoffMs / 1000}s")
                    sleep(backoffMs)
                    backoffMs *= 2
                    continue
                }
                break
            }
            val error = testConnection(
                host,
                port,
                protocol,
                protector,
                connectAddress
            )
            if (error == null) {
                return null
            }
            lastError = error

            if (attempt < maxAttempts) {
                logger("Proxy unreachable, retrying in ${backoffMs / 1000}s")
                sleep(backoffMs)
                backoffMs *= 2
            }
        }

        return lastError
    }
}
