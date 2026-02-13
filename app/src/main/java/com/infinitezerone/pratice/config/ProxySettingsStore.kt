package com.infinitezerone.pratice.config

import android.content.Context

data class ProxyConfig(
    val host: String,
    val port: Int
)

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

    private companion object {
        const val PREFS_NAME = "proxy_settings"
        const val KEY_HOST = "proxy_host"
        const val KEY_PORT = "proxy_port"
        const val KEY_BYPASS_PACKAGES = "bypass_packages"
    }
}
