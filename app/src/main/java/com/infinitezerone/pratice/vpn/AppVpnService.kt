package com.infinitezerone.pratice.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.infinitezerone.pratice.R
import com.infinitezerone.pratice.config.ProxySettingsStore

class AppVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_PROXY_HOST) ?: ""
        val port = intent?.getIntExtra(EXTRA_PROXY_PORT, -1) ?: -1
        if (host.isBlank() || port !in 1..65535) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(host, port))

        if (!startVpnTunnel()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    private fun startVpnTunnel(): Boolean {
        if (vpnInterface != null) {
            return true
        }

        val builder = Builder()
            .setSession("Pratice VPN")
            .setMtu(1500)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        val bypassPackages = ProxySettingsStore(this).loadBypassPackages()
        bypassPackages.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // Ignore packages that no longer exist.
            }
        }

        vpnInterface = builder.establish()
        return vpnInterface != null
    }

    private fun buildNotification(host: String, port: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pratice VPN running")
            .setContentText("Proxy $host:$port")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Service",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val EXTRA_PROXY_HOST = "extra_proxy_host"
        private const val EXTRA_PROXY_PORT = "extra_proxy_port"
        private const val CHANNEL_ID = "pratice_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, AppVpnService::class.java)
                .putExtra(EXTRA_PROXY_HOST, host)
                .putExtra(EXTRA_PROXY_PORT, port)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AppVpnService::class.java)
            context.stopService(intent)
        }
    }
}
