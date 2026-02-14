package com.infinitezerone.pratice.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.infinitezerone.pratice.config.ProxySettingsStore

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        val settingsStore = ProxySettingsStore(context)
        if (!settingsStore.loadAutoReconnectEnabled()) {
            return
        }

        val config = settingsStore.loadConfigOrNull() ?: return
        if (VpnService.prepare(context) != null) {
            VpnRuntimeState.appendLog("Boot auto-reconnect skipped: VPN permission not granted yet.")
            return
        }
        VpnRuntimeState.appendLog("Boot auto-reconnect requested.")
        AppVpnService.start(context, config.host, config.port, config.protocol)
    }
}
