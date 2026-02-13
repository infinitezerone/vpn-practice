package com.infinitezerone.pratice.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService

class AppVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_PROXY_HOST) ?: ""
        val port = intent?.getIntExtra(EXTRA_PROXY_PORT, -1) ?: -1
        if (host.isBlank() || port !in 1..65535) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROXY_HOST = "extra_proxy_host"
        private const val EXTRA_PROXY_PORT = "extra_proxy_port"

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, AppVpnService::class.java)
                .putExtra(EXTRA_PROXY_HOST, host)
                .putExtra(EXTRA_PROXY_PORT, port)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AppVpnService::class.java)
            context.stopService(intent)
        }
    }
}
