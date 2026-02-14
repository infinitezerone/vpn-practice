package com.infinitezerone.pratice.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.infinitezerone.pratice.R
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.config.RoutingMode
import org.amnezia.awg.hevtunnel.TProxyService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeProxyHost: String? = null
    private var activeProxyPort: Int = -1
    private var bridgeJob: Job? = null
    private var statsJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    @Volatile
    private var startFailed = false
    @Volatile
    private var stopAlreadyReported = false
    @Volatile
    private var isShuttingDown = false
    @Volatile
    private var stopRequested = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStopAsync("VPN stopped.")
            return START_NOT_STICKY
        }

        val host = intent?.getStringExtra(EXTRA_PROXY_HOST) ?: ""
        val port = intent?.getIntExtra(EXTRA_PROXY_PORT, -1) ?: -1
        if (host.isBlank() || port !in 1..65535) {
            startFailed = true
            VpnRuntimeState.setError("Failed to start VPN: invalid proxy settings.")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(host, port))
        registerNetworkCallback()

        startFailed = false
        stopAlreadyReported = false
        isShuttingDown = false
        stopRequested = false
        activeProxyHost = host
        activeProxyPort = port
        startJob?.cancel()
        startJob = serviceScope.launch {
            VpnRuntimeState.setConnecting(host, port)
            val proxyError = waitForProxyWithRetry(host, port)
            if (stopRequested || isShuttingDown) {
                return@launch
            }
            if (proxyError != null) {
                startFailed = true
                VpnRuntimeState.setError(proxyError)
                stopSelf()
                return@launch
            }

            if (!startVpnTunnel()) {
                startFailed = true
                VpnRuntimeState.setError("Failed to establish VPN tunnel.")
                stopSelf()
                return@launch
            }

            if (!stopRequested && !isShuttingDown) {
                VpnRuntimeState.setRunning(host, port)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isShuttingDown = true
        unregisterNetworkCallback()
        startJob?.cancel()
        bridgeJob?.cancel()
        statsJob?.cancel()
        if (!stopRequested) {
            stopBridgeAsync()
        }
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        activeProxyHost = null
        activeProxyPort = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        val preserveErrorState = !stopRequested && VpnRuntimeState.state.value.status == RuntimeStatus.Error
        if (!stopAlreadyReported && !preserveErrorState) {
            VpnRuntimeState.setStopped("VPN stopped.")
        }
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (stopRequested || isShuttingDown) {
                    return
                }
                if (vpnInterface == null) {
                    return
                }
                val host = activeProxyHost ?: return
                val port = activeProxyPort
                if (port !in 1..65535) {
                    return
                }
                serviceScope.launch {
                    if (stopRequested || isShuttingDown) {
                        return@launch
                    }
                    VpnRuntimeState.appendLog("Network available. Re-checking proxy connectivity.")
                    VpnRuntimeState.setConnecting(host, port)
                    val proxyError = waitForProxyWithRetry(host, port)
                    if (stopRequested || isShuttingDown) {
                        return@launch
                    }
                    if (proxyError == null) {
                        VpnRuntimeState.setRunning(host, port)
                    } else {
                        VpnRuntimeState.setError(proxyError)
                    }
                }
            }

            override fun onLost(network: Network) {
                VpnRuntimeState.appendLog("Network lost.")
            }
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            VpnRuntimeState.appendLog("Network callback unavailable on this device.")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Callback already unregistered.
        } finally {
            networkCallback = null
        }
    }

    override fun onRevoke() {
        requestStopAsync("VPN permission revoked by system.")
        super.onRevoke()
    }

    private fun startVpnTunnel(): Boolean {
        if (vpnInterface != null) {
            return true
        }

        val settingsStore = ProxySettingsStore(this)
        val host = activeProxyHost
        val port = activeProxyPort
        if (host.isNullOrBlank() || port !in 1..65535) {
            return false
        }

        val safeHost = EndpointSanitizer.sanitizeHost(host)
        val builder = Builder()
            .setSession("Pratice VPN")
            .setMtu(1500)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        try {
            // IPv6 is optional; some devices or networks may not support it.
            builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            builder.addRoute("::", 0)
        } catch (_: Exception) {
            VpnRuntimeState.appendLog("IPv6 route unavailable. Continuing with IPv4 only.")
        }

        try {
            val proxyBypassList = settingsStore.loadProxyBypassList()
            val proxyInfo = if (proxyBypassList.isEmpty()) {
                ProxyInfo.buildDirectProxy(safeHost, port)
            } else {
                ProxyInfo.buildDirectProxy(safeHost, port, proxyBypassList)
            }
            builder.setHttpProxy(proxyInfo)
            if (proxyBypassList.isEmpty()) {
                VpnRuntimeState.appendLog("HTTP proxy bridge enabled for $safeHost:$port")
            } else {
                VpnRuntimeState.appendLog(
                    "HTTP proxy bridge enabled for $safeHost:$port with ${proxyBypassList.size} bypass rules"
                )
            }
        } catch (_: Exception) {
            VpnRuntimeState.appendLog("HTTP proxy bridge unavailable. Continuing without HTTP proxy.")
        }

        val selectedPackages = settingsStore.loadBypassPackages()
        val routingMode = settingsStore.loadRoutingMode()

        selectedPackages.forEach { packageName ->
            try {
                if (routingMode == RoutingMode.Allowlist) {
                    builder.addAllowedApplication(packageName)
                } else {
                    builder.addDisallowedApplication(packageName)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                // Ignore packages that no longer exist.
            }
        }

        VpnRuntimeState.appendLog(
            if (routingMode == RoutingMode.Allowlist) {
                "VPN routing mode: allowlist (${selectedPackages.size} apps)"
            } else {
                "VPN routing mode: bypass (${selectedPackages.size} apps)"
            }
        )

        vpnInterface = builder.establish()
        val tunnelFd = vpnInterface?.fd ?: return false
        return startBridge(tunnelFd, safeHost, port)
    }

    private fun startBridge(tunnelFd: Int, host: String, port: Int): Boolean {
        val configFile = writeHevTunnelConfig(host, port) ?: return false

        bridgeJob?.cancel()
        bridgeJob = serviceScope.launch {
            try {
                VpnRuntimeState.appendLog("Starting tun2socks bridge.")
                TProxyService.TProxyStartService(configFile.absolutePath, tunnelFd)
                if (!isShuttingDown && !stopRequested) {
                    startFailed = true
                    VpnRuntimeState.setError("tun2socks bridge stopped unexpectedly.")
                    stopSelf()
                }
            } catch (e: Throwable) {
                if (!isShuttingDown && !stopRequested) {
                    startFailed = true
                    VpnRuntimeState.setError("tun2socks bridge failed (${e.javaClass.simpleName}).")
                    stopSelf()
                }
            }
        }
        startStatsMonitor()
        return true
    }

    private fun stopBridge() {
        try {
            TProxyService.TProxyStopService()
            VpnRuntimeState.appendLog("tun2socks bridge stopped.")
        } catch (_: Throwable) {
            // Native bridge may already be stopped.
        }
    }

    private fun startStatsMonitor() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastSnapshot: String? = null
            while (!isShuttingDown && !stopRequested) {
                try {
                    val stats = TProxyService.TProxyGetStats()
                    if (stats != null && stats.isNotEmpty()) {
                        val snapshot = stats.joinToString(prefix = "[", postfix = "]")
                        if (snapshot != lastSnapshot) {
                            lastSnapshot = snapshot
                            VpnRuntimeState.appendLog("tun2socks stats=$snapshot")
                        }
                    }
                } catch (_: Throwable) {
                    // Bridge not ready or already stopped.
                }
                delay(2_000)
            }
        }
    }

    private fun requestStopAsync(reason: String) {
        if (stopRequested) {
            return
        }
        stopRequested = true
        stopAlreadyReported = true
        isShuttingDown = true
        VpnRuntimeState.setStopped(reason)
        unregisterNetworkCallback()
        startJob?.cancel()
        bridgeJob?.cancel()
        statsJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        activeProxyHost = null
        activeProxyPort = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        stopBridgeAsync()
    }

    private fun stopBridgeAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            stopBridge()
        }
    }

    private fun writeHevTunnelConfig(host: String, port: Int): File? {
        return try {
            val configFile = File(filesDir, HEV_CONFIG_FILE)
            val config = """
                socks5:
                  address: "$host"
                  port: $port
                  udp: "udp"
                tcp:
                  connect-timeout: 5000
                  idle-timeout: 600
                misc:
                  task-stack-size: 24576
            """.trimIndent()
            configFile.writeText(config)
            configFile
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun waitForProxyWithRetry(host: String, port: Int): String? {
        val maxAttempts = 3
        var backoffMs = 1_000L
        var lastError: String? = null

        for (attempt in 1..maxAttempts) {
            VpnRuntimeState.appendLog("Proxy connectivity check $attempt/$maxAttempts")
            val error = ProxyConnectivityChecker.testConnection(host, port)
            if (error == null) {
                return null
            }
            lastError = error

            if (attempt < maxAttempts) {
                VpnRuntimeState.appendLog("Proxy unreachable, retrying in ${backoffMs / 1000}s")
                delay(backoffMs)
                backoffMs *= 2
            }
        }

        return lastError
    }

    private fun buildNotification(host: String, port: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pratice VPN running")
            .setContentText("Proxy ${EndpointSanitizer.sanitizeHost(host)}:$port")
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
        private const val HEV_CONFIG_FILE = "hev-socks5.yaml"
        private const val ACTION_START = "com.infinitezerone.pratice.vpn.action.START"
        private const val ACTION_STOP = "com.infinitezerone.pratice.vpn.action.STOP"
        private const val EXTRA_PROXY_HOST = "extra_proxy_host"
        private const val EXTRA_PROXY_PORT = "extra_proxy_port"
        private const val CHANNEL_ID = "pratice_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, AppVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROXY_HOST, host)
                .putExtra(EXTRA_PROXY_PORT, port)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                VpnRuntimeState.setError("Failed to start VPN service (${e.javaClass.simpleName}).")
            }
        }

        fun stop(context: Context) {
            val stopIntent = Intent(context, AppVpnService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(stopIntent)
            } catch (_: Exception) {
                context.stopService(Intent(context, AppVpnService::class.java))
            }
        }
    }
}
