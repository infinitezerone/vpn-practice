package com.infinitezerone.pratice.config

import android.content.Context

data class ProxyConfig(
    val host: String,
    val port: Int
)

enum class RoutingMode {
    Bypass,
    Allowlist
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
        return ProxyConfig(host = host, port = portText.toInt())
    }

    fun save(host: String, port: Int) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
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
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_PROXY_BYPASS_LIST = "proxy_bypass_list"
    }
}
