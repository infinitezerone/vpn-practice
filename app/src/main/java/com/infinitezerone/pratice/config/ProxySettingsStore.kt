package com.infinitezerone.pratice.config

import android.content.Context

data class ProxyConfig(
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol
)

enum class RoutingMode {
    Bypass,
    Allowlist
}

enum class ProxyProtocol {
    Socks5,
    Http
}

enum class HttpTrafficMode {
    CompatFallback,
    StrictProxy
}

class ProxySettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadHost(): String = prefs.getString(KEY_HOST, "") ?: ""

    fun loadPortText(): String {
        val port = prefs.getInt(KEY_PORT, -1)
        return if (port > 0) port.toString() else ""
    }

    fun loadConfigOrNull(): ProxyConfig? {
        val host = loadHost().trim()
        val portText = loadPortText()
        if (ProxyConfigValidator.validate(host, portText) != null) {
            return null
        }
        return ProxyConfig(host = host, port = portText.toInt(), protocol = loadProxyProtocol())
    }

    fun save(host: String, port: Int) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun loadProxyProtocol(): ProxyProtocol {
        val raw = prefs.getString(KEY_PROXY_PROTOCOL, ProxyProtocol.Socks5.name) ?: ProxyProtocol.Socks5.name
        return ProxyProtocol.entries.firstOrNull { it.name == raw } ?: ProxyProtocol.Socks5
    }

    fun saveProxyProtocol(protocol: ProxyProtocol) {
        prefs.edit()
            .putString(KEY_PROXY_PROTOCOL, protocol.name)
            .apply()
    }

    fun loadHttpTrafficMode(): HttpTrafficMode {
        val raw = prefs.getString(KEY_HTTP_TRAFFIC_MODE, HttpTrafficMode.CompatFallback.name)
            ?: HttpTrafficMode.CompatFallback.name
        return HttpTrafficMode.entries.firstOrNull { it.name == raw } ?: HttpTrafficMode.CompatFallback
    }

    fun saveHttpTrafficMode(mode: HttpTrafficMode) {
        prefs.edit()
            .putString(KEY_HTTP_TRAFFIC_MODE, mode.name)
            .apply()
    }

    fun loadBypassPackages(): Set<String> =
        prefs.getStringSet(KEY_BYPASS_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun saveBypassPackages(packages: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_BYPASS_PACKAGES, packages.toSet())
            .apply()
    }

    fun loadRoutingMode(): RoutingMode {
        val raw = prefs.getString(KEY_ROUTING_MODE, RoutingMode.Bypass.name) ?: RoutingMode.Bypass.name
        return RoutingMode.entries.firstOrNull { it.name == raw } ?: RoutingMode.Bypass
    }

    fun saveRoutingMode(mode: RoutingMode) {
        prefs.edit()
            .putString(KEY_ROUTING_MODE, mode.name)
            .apply()
    }

    fun loadAutoReconnectEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_RECONNECT, false)

    fun saveAutoReconnectEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_RECONNECT, enabled)
            .apply()
    }

    fun loadProxyBypassRawInput(): String = prefs.getString(KEY_PROXY_BYPASS_LIST, "") ?: ""

    fun loadProxyBypassList(): List<String> = ProxyBypassParser.parse(loadProxyBypassRawInput())

    fun saveProxyBypassRawInput(rawInput: String) {
        val normalized = ProxyBypassParser.format(ProxyBypassParser.parse(rawInput))
        prefs.edit()
            .putString(KEY_PROXY_BYPASS_LIST, normalized)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "proxy_settings"
        const val KEY_HOST = "proxy_host"
        const val KEY_PORT = "proxy_port"
        const val KEY_BYPASS_PACKAGES = "bypass_packages"
        const val KEY_ROUTING_MODE = "routing_mode"
        const val KEY_PROXY_PROTOCOL = "proxy_protocol"
        const val KEY_HTTP_TRAFFIC_MODE = "http_traffic_mode"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_PROXY_BYPASS_LIST = "proxy_bypass_list"
    }
}
