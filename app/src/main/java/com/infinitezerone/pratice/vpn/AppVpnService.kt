package com.infinitezerone.pratice.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.infinitezerone.pratice.R
import com.infinitezerone.pratice.config.ProxyProtocol
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.config.RoutingMode
import org.amnezia.awg.hevtunnel.TProxyService
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastNonVpnNetwork: Network? = null
    private var activeProxyHost: String? = null
    private var activeProxyPort: Int = -1
    private var activeProxyProtocol: ProxyProtocol = ProxyProtocol.Socks5
    private var bridgeJob: Job? = null
    private var statsJob: Job? = null
    private var appTrafficJob: Job? = null
    private var httpBridge: HttpConnectSocksBridge? = null
    private val monitoredUidToLabel = mutableMapOf<Int, String>()
    private val lastUidTraffic = mutableMapOf<Int, Pair<Long, Long>>()
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
        val protocol = intent?.getStringExtra(EXTRA_PROXY_PROTOCOL)
            ?.let { raw -> ProxyProtocol.entries.firstOrNull { it.name == raw } }
            ?: ProxyProtocol.Socks5
        if (host.isBlank() || port !in 1..65535) {
            startFailed = true
            VpnRuntimeState.setError("Failed to start VPN: invalid proxy settings.")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(host, port))
        registerNetworkCallback()
        serviceScope.coroutineContext.cancelChildren()

        startFailed = false
        stopAlreadyReported = false
        isShuttingDown = false
        stopRequested = false
        activeProxyHost = host
        activeProxyPort = port
        activeProxyProtocol = protocol
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
        appTrafficJob?.cancel()
        if (!stopRequested) {
            stopBridgeAsync()
        }
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopHttpBridge()
        monitoredUidToLabel.clear()
        lastUidTraffic.clear()
        activeProxyHost = null
        activeProxyPort = -1
        activeProxyProtocol = ProxyProtocol.Socks5
        lastNonVpnNetwork = null
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
        lastNonVpnNetwork = findBestNonVpnNetwork(connectivityManager)
        updateUnderlyingNetworkHint(lastNonVpnNetwork)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (stopRequested || isShuttingDown) {
                    return
                }
                if (connectivityManager?.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                ) {
                    return
                }
                lastNonVpnNetwork = network
                updateUnderlyingNetworkHint(network)
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
                    val wasRunning = VpnRuntimeState.state.value.status == RuntimeStatus.Running
                    VpnRuntimeState.appendLog("Network available. Re-checking proxy connectivity.")
                    if (!wasRunning) {
                        VpnRuntimeState.setConnecting(host, port)
                    }
                    val proxyError = waitForProxyWithRetry(host, port)
                    if (stopRequested || isShuttingDown) {
                        return@launch
                    }
                    if (proxyError == null) {
                        if (!wasRunning) {
                            VpnRuntimeState.setRunning(host, port)
                        } else {
                            VpnRuntimeState.appendLog("Proxy re-check succeeded.")
                        }
                    } else {
                        if (wasRunning) {
                            VpnRuntimeState.appendLog("Proxy re-check failed: $proxyError")
                        } else {
                            VpnRuntimeState.setError(proxyError)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                if (network == lastNonVpnNetwork) {
                    lastNonVpnNetwork = findBestNonVpnNetwork(connectivityManager)
                    updateUnderlyingNetworkHint(lastNonVpnNetwork)
                }
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
            lastNonVpnNetwork = null
            updateUnderlyingNetworkHint(null)
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
        val protocol = activeProxyProtocol
        if (host.isNullOrBlank() || port !in 1..65535) {
            return false
        }

        val safeHost = EndpointSanitizer.sanitizeHost(host)
        val builder = Builder()
            .setSession("Pratice VPN")
            .setMtu(1500)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)

        if (protocol == ProxyProtocol.Http) {
            builder.addDnsServer(MAP_DNS_ADDRESS)
            VpnRuntimeState.appendLog("Mapped DNS enabled at $MAP_DNS_ADDRESS for HTTP upstream.")
        } else {
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
        }

        try {
            // IPv6 is optional; some devices or networks may not support it.
            builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            builder.addRoute("::", 0)
        } catch (_: Exception) {
            VpnRuntimeState.appendLog("IPv6 route unavailable. Continuing with IPv4 only.")
        }

        if (protocol == ProxyProtocol.Http) {
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
        configurePerAppTrafficMonitor(routingMode, selectedPackages)
        val (bridgeHost, bridgePort, udpMode) = resolveTunnelProxyEndpoint(protocol, safeHost, port)
        if (bridgePort !in 1..65535) {
            return false
        }
        return startBridge(tunnelFd, bridgeHost, bridgePort, udpMode, protocol == ProxyProtocol.Http)
    }

    private fun startBridge(
        tunnelFd: Int,
        host: String,
        port: Int,
        udpMode: String,
        enableMappedDns: Boolean
    ): Boolean {
        val configFile = writeHevTunnelConfig(host, port, udpMode, enableMappedDns) ?: return false

        bridgeJob?.cancel()
        bridgeJob = serviceScope.launch {
            try {
                VpnRuntimeState.appendLog("Starting tun2socks bridge.")
                TProxyService.TProxyStartService(configFile.absolutePath, tunnelFd)
                if (!isShuttingDown && !stopRequested) {
                    // Some builds return from the native entrypoint while the bridge
                    // thread keeps running. Avoid false-negative state transitions.
                    VpnRuntimeState.appendLog("tun2socks bridge returned control.")
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
        serviceScope.coroutineContext.cancelChildren()
        unregisterNetworkCallback()
        startJob?.cancel()
        bridgeJob?.cancel()
        statsJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopHttpBridge()
        activeProxyHost = null
        activeProxyPort = -1
        activeProxyProtocol = ProxyProtocol.Socks5
        lastNonVpnNetwork = null
        updateUnderlyingNetworkHint(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        stopBridgeAsync()
        appTrafficJob?.cancel()
        monitoredUidToLabel.clear()
        lastUidTraffic.clear()
    }

    private fun stopBridgeAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            stopBridge()
        }
    }

    private fun writeHevTunnelConfig(
        host: String,
        port: Int,
        udpMode: String,
        enableMappedDns: Boolean
    ): File? {
        return try {
            val configFile = File(filesDir, HEV_CONFIG_FILE)
            val config = buildString {
                appendLine("socks5:")
                appendLine("  address: \"$host\"")
                appendLine("  port: $port")
                appendLine("  udp: \"$udpMode\"")
                if (enableMappedDns) {
                    appendLine("mapdns:")
                    appendLine("  address: \"$MAP_DNS_ADDRESS\"")
                    appendLine("  port: $MAP_DNS_PORT")
                    appendLine("  network: \"$MAP_DNS_NETWORK\"")
                    appendLine("  netmask: \"$MAP_DNS_NETMASK\"")
                    appendLine("  cache-size: $MAP_DNS_CACHE_SIZE")
                }
                appendLine("tcp:")
                appendLine("  connect-timeout: 5000")
                appendLine("  idle-timeout: 600")
                appendLine("misc:")
                appendLine("  task-stack-size: 24576")
            }
            configFile.writeText(config.trimEnd())
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
            val protector = if (vpnInterface != null) {
                { socket: java.net.Socket -> protect(socket) }
            } else {
                null
            }
            val connectAddress = resolveUpstreamProxyAddress(host, port)
            if (connectAddress == null) {
                lastError = "Cannot resolve proxy ${EndpointSanitizer.sanitizeHost(host)}:$port."
                if (attempt < maxAttempts) {
                    VpnRuntimeState.appendLog("Proxy DNS resolution failed, retrying in ${backoffMs / 1000}s")
                    delay(backoffMs)
                    backoffMs *= 2
                    continue
                }
                break
            }
            val error = ProxyConnectivityChecker.testConnection(
                host = host,
                port = port,
                protectSocket = protector,
                connectAddress = connectAddress
            )
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

    private fun resolveTunnelProxyEndpoint(
        protocol: ProxyProtocol,
        upstreamHost: String,
        upstreamPort: Int
    ): Triple<String, Int, String> {
        stopHttpBridge()
        if (protocol == ProxyProtocol.Socks5) {
            return Triple(upstreamHost, upstreamPort, "udp")
        }

        val bridge = HttpConnectSocksBridge(
            upstreamHost = upstreamHost,
            upstreamPort = upstreamPort,
            upstreamEndpointProvider = {
                resolveUpstreamProxyAddress(upstreamHost, upstreamPort)
            },
            destinationEndpointProvider = { host, port ->
                resolveUpstreamProxyAddress(host, port)
            },
            bypassVpnForSocket = { socket ->
                val network = lastNonVpnNetwork
                if (network != null) {
                    try {
                        network.bindSocket(socket)
                        true
                    } catch (_: Exception) {
                        protect(socket)
                    }
                } else {
                    protect(socket)
                }
            },
            allowDirectFallbackForNonHttpPorts = true,
            logger = { message -> VpnRuntimeState.appendLog(message) }
        )
        val localPort = bridge.start()
        httpBridge = bridge
        VpnRuntimeState.appendLog("HTTP upstream enabled via local SOCKS bridge on 127.0.0.1:$localPort")
        return Triple("127.0.0.1", localPort, "tcp")
    }

    private fun resolveUpstreamProxyAddress(host: String, port: Int): InetSocketAddress? {
        val safeHost = EndpointSanitizer.sanitizeHost(host)
        if (port !in 1..65535 || safeHost.isBlank()) {
            return null
        }
        val network = lastNonVpnNetwork
        if (network != null) {
            try {
                val resolved = network.getByName(safeHost)
                return InetSocketAddress(resolved, port)
            } catch (_: Exception) {
                // Fallback to system DNS.
            }
        }
        return try {
            InetSocketAddress(InetAddress.getByName(safeHost), port)
        } catch (_: Exception) {
            null
        }
    }

    private fun stopHttpBridge() {
        httpBridge?.stop()
        httpBridge = null
    }

    private fun updateUnderlyingNetworkHint(network: Network?) {
        try {
            if (network == null) {
                setUnderlyingNetworks(null)
            } else {
                setUnderlyingNetworks(arrayOf(network))
            }
        } catch (_: Exception) {
            // Hint API may be unavailable on some devices.
        }
    }

    private fun findBestNonVpnNetwork(manager: ConnectivityManager?): Network? {
        val cm = manager ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val capabilities = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun configurePerAppTrafficMonitor(routingMode: RoutingMode, selectedPackages: Set<String>) {
        appTrafficJob?.cancel()
        monitoredUidToLabel.clear()
        lastUidTraffic.clear()

        val monitoredPackages = when (routingMode) {
            RoutingMode.Allowlist -> selectedPackages
            RoutingMode.Bypass -> {
                val launchablePackages = getInstalledApplicationsCompat(packageManager)
                    .asSequence()
                    .filter { appInfo ->
                        appInfo.packageName != packageName &&
                            packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
                    }
                    .map { it.packageName }
                    .toSet()
                launchablePackages - selectedPackages
            }
        }

        monitoredPackages.forEach { monitoredPackage ->
            try {
                val appInfo = packageManager.getApplicationInfo(monitoredPackage, 0)
                val uid = appInfo.uid
                if (!monitoredUidToLabel.containsKey(uid)) {
                    val label = packageManager.getApplicationLabel(appInfo).toString()
                    monitoredUidToLabel[uid] = "$label ($monitoredPackage)"
                }
            } catch (_: Exception) {
                // Ignore stale packages.
            }
        }

        if (monitoredUidToLabel.isEmpty()) {
            VpnRuntimeState.appendLog("App traffic monitor unavailable: no matching app UIDs.")
            return
        }

        val sample = monitoredUidToLabel.values.take(5).joinToString()
        VpnRuntimeState.appendLog(
            "App traffic monitor active for ${monitoredUidToLabel.size} apps. Sample: $sample"
        )
        startPerAppTrafficMonitor()
    }

    private fun startPerAppTrafficMonitor() {
        appTrafficJob?.cancel()
        appTrafficJob = serviceScope.launch {
            var supportedUidCount = 0
            monitoredUidToLabel.keys.forEach { uid ->
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                if (rx >= 0L && tx >= 0L) {
                    supportedUidCount += 1
                    lastUidTraffic[uid] = rx to tx
                }
            }

            if (supportedUidCount == 0) {
                VpnRuntimeState.appendLog(
                    "App traffic monitor unavailable: UID byte counters are not exposed on this device build."
                )
                return@launch
            }

            var idleIntervals = 0
            while (!isShuttingDown && !stopRequested) {
                delay(4_000)
                val deltas = mutableListOf<Pair<String, Long>>()
                monitoredUidToLabel.forEach { (uid, label) ->
                    val rxNow = TrafficStats.getUidRxBytes(uid)
                    val txNow = TrafficStats.getUidTxBytes(uid)
                    if (rxNow < 0L || txNow < 0L) {
                        return@forEach
                    }
                    val previous = lastUidTraffic[uid]
                    if (previous == null) {
                        lastUidTraffic[uid] = rxNow to txNow
                        return@forEach
                    }
                    val rxDelta = (rxNow - previous.first).coerceAtLeast(0L)
                    val txDelta = (txNow - previous.second).coerceAtLeast(0L)
                    lastUidTraffic[uid] = rxNow to txNow
                    val total = rxDelta + txDelta
                    if (total > 0L) {
                        val summary = "$label rx=${formatBytes(rxDelta)} tx=${formatBytes(txDelta)}"
                        deltas += summary to total
                    }
                }

                if (deltas.isNotEmpty()) {
                    idleIntervals = 0
                    val top = deltas
                        .sortedByDescending { it.second }
                        .take(4)
                        .joinToString(separator = " | ") { it.first }
                    VpnRuntimeState.appendLog("VPN app traffic: $top")
                } else {
                    idleIntervals += 1
                    if (idleIntervals % 6 == 0) {
                        VpnRuntimeState.appendLog(
                            "VPN app traffic: no monitored app data in the last ${idleIntervals * 4}s."
                        )
                    }
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format("%.1fMB", bytes / (1024f * 1024f))
            bytes >= 1024L -> String.format("%.1fKB", bytes / 1024f)
            else -> "${bytes}B"
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledApplicationsCompat(packageManager: PackageManager): List<ApplicationInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getInstalledApplications(0)
        }
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
        private const val EXTRA_PROXY_PROTOCOL = "extra_proxy_protocol"
        private const val CHANNEL_ID = "pratice_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAP_DNS_ADDRESS = "198.18.0.2"
        private const val MAP_DNS_PORT = 53
        private const val MAP_DNS_NETWORK = "100.64.0.0"
        private const val MAP_DNS_NETMASK = "255.192.0.0"
        private const val MAP_DNS_CACHE_SIZE = 4096

        fun start(
            context: Context,
            host: String,
            port: Int,
            protocol: ProxyProtocol = ProxyProtocol.Socks5
        ) {
            val intent = Intent(context, AppVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROXY_HOST, host)
                .putExtra(EXTRA_PROXY_PORT, port)
                .putExtra(EXTRA_PROXY_PROTOCOL, protocol.name)
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
