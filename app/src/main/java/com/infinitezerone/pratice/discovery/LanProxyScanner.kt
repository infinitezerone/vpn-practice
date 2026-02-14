package com.infinitezerone.pratice.discovery

import android.content.Context
import android.net.ConnectivityManager
import com.infinitezerone.pratice.config.ProxyProtocol
import com.infinitezerone.pratice.vpn.ProxyConnectivityChecker
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class DiscoveredProxy(
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val localIp: String
)

class LanProxyScanner(
    private val localIpv4Provider: suspend (Context) -> String? = { context ->
        resolveLocalIpv4(context)
    },
    private val proxyProbe: suspend (String, Int, Int) -> ProxyProtocol? = { host, port, timeoutMs ->
        detectProxyProtocol(host, port, timeoutMs)
    }
) {
    suspend fun discover(
        context: Context,
        scanPorts: List<Int> = DEFAULT_SCAN_PORTS,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        parallelism: Int = DEFAULT_PARALLELISM
    ): DiscoveredProxy? {
        val localIp = localIpv4Provider(context) ?: return null
        val hosts = buildScanHosts(localIp).filterNot { it == localIp }
        val ports = scanPorts
            .filter { it in 1..65535 }
            .distinct()
        if (hosts.isEmpty() || ports.isEmpty()) {
            return null
        }

        val winner = AtomicReference<DiscoveredProxy?>(null)
        val semaphore = Semaphore(parallelism.coerceAtLeast(1))

        return coroutineScope {
            val tasks = hosts.flatMap { host ->
                ports.map { port ->
                    async(Dispatchers.IO) {
                        if (winner.get() != null) {
                            return@async null
                        }
                        semaphore.withPermit {
                            if (winner.get() != null) {
                                return@withPermit null
                            }
                            val protocol = proxyProbe(host, port, timeoutMs) ?: return@withPermit null
                            val discovered = DiscoveredProxy(
                                host = host,
                                port = port,
                                protocol = protocol,
                                localIp = localIp
                            )
                            winner.compareAndSet(null, discovered)
                            discovered
                        }
                    }
                }
            }
            tasks.awaitAll().firstOrNull { it != null } ?: winner.get()
        }
    }

    companion object {
        val DEFAULT_SCAN_PORTS = listOf(7890, 7891, 8888)
        private const val DEFAULT_TIMEOUT_MS = 400
        private const val DEFAULT_PARALLELISM = 64

        fun buildScanHosts(localIpv4: String): List<String> {
            val octets = localIpv4.split('.')
            if (octets.size != 4) {
                return emptyList()
            }
            if (octets.any { it.toIntOrNull() !in 0..255 }) {
                return emptyList()
            }
            val prefix = octets.take(3).joinToString(".")
            return (1..254).map { last -> "$prefix.$last" }
        }

        private suspend fun detectProxyProtocol(
            host: String,
            port: Int,
            timeoutMs: Int
        ): ProxyProtocol? = withContext(Dispatchers.IO) {
            val protocolOrder = when (port) {
                7891, 1080 -> listOf(ProxyProtocol.Socks5, ProxyProtocol.Http)
                else -> listOf(ProxyProtocol.Http, ProxyProtocol.Socks5)
            }
            protocolOrder.firstOrNull { protocol ->
                ProxyConnectivityChecker.testConnection(
                    host = host,
                    port = port,
                    protocol = protocol,
                    timeoutMs = timeoutMs
                ) == null
            }
        }

        private fun resolveLocalIpv4(context: Context): String? {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            val activeNetwork = connectivityManager?.activeNetwork
            val linkProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }
            val fromLinkProperties = linkProperties
                ?.linkAddresses
                ?.firstOrNull { link ->
                    val address = link.address
                    address is Inet4Address && !address.isLoopbackAddress
                }
                ?.address
                ?.hostAddress
            if (!fromLinkProperties.isNullOrBlank()) {
                return fromLinkProperties
            }

            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (networkInterface in interfaces) {
                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        return address.hostAddress
                    }
                }
            }
            return null
        }
    }
}
