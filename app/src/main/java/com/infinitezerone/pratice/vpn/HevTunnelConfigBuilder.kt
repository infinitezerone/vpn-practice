package com.infinitezerone.pratice.vpn

data class MapDnsConfig(
    val address: String,
    val port: Int,
    val network: String,
    val netmask: String,
    val cacheSize: Int
)

object HevTunnelConfigBuilder {
    fun build(
        host: String,
        port: Int,
        udpMode: String,
        mapDnsConfig: MapDnsConfig?
    ): String {
        return buildString {
            appendLine("socks5:")
            appendLine("  address: \"$host\"")
            appendLine("  port: $port")
            appendLine("  udp: \"$udpMode\"")
            mapDnsConfig?.let { mapDns ->
                appendLine("mapdns:")
                appendLine("  address: \"${mapDns.address}\"")
                appendLine("  port: ${mapDns.port}")
                appendLine("  network: \"${mapDns.network}\"")
                appendLine("  netmask: \"${mapDns.netmask}\"")
                appendLine("  cache-size: ${mapDns.cacheSize}")
            }
            appendLine("tcp:")
            appendLine("  connect-timeout: 5000")
            appendLine("  idle-timeout: 600")
            appendLine("misc:")
            appendLine("  task-stack-size: 24576")
        }.trimEnd()
    }
}
