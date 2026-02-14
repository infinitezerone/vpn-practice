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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.infinitezerone.pratice.R
import com.infinitezerone.pratice.config.HttpTrafficMode
import com.infinitezerone.pratice.config.ProxyProtocol
import com.infinitezerone.pratice.config.ProxySettingsStore
import com.infinitezerone.pratice.config.RoutingMode
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class AppVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val nonVpnNetworkTracker = NonVpnNetworkTracker()
    private var activeProxyHost: String? = null
    private var activeProxyPort: Int = -1
    private var activeProxyProtocol: ProxyProtocol = ProxyProtocol.Socks5
    private var httpBridge: HttpConnectSocksBridge? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridgeManager by lazy {
        TunnelBridgeManager(
            scope = serviceScope,
            logger = { message -> VpnRuntimeState.appendLog(message) },
            shouldRun = { !isShuttingDown && !stopRequested },
            onBridgeFailure = { error ->
                startFailed = true
                VpnRuntimeState.setError("tun2socks bridge failed (${error.javaClass.simpleName}).")
                stopSelf()
            }
        )
    }
    private val proxyProbeCoordinator by lazy {
        ProxyProbeCoordinator(
            logger = { message -> VpnRuntimeState.appendLog(message) },
            resolveAddress = { host, port -> resolveUpstreamProxyAddress(host, port) },
            bypassVpnForSocket = { socket -> bypassVpnForSocket(socket) },
            hasActiveTunnel = { vpnInterface != null }
        )
    }
    private val networkWatcher by lazy {
        NetworkAvailabilityWatcher(
            connectivityManagerProvider = { getSystemService(ConnectivityManager::class.java) },
            logger = { message -> VpnRuntimeState.appendLog(message) },
            onRegistered = { manager ->
                val initialNetwork = nonVpnNetworkTracker.initialize(manager)
                updateUnderlyingNetworkHint(initialNetwork)
            },
            onAvailable = onAvailable@{ manager, network ->
                if (stopRequested || isShuttingDown) {
                    return@onAvailable
                }
                if (!nonVpnNetworkTracker.onAvailable(manager, network)) {
                    return@onAvailable
                }
                updateUnderlyingNetworkHint(nonVpnNetworkTracker.currentNetwork())
                if (vpnInterface == null) {
                    return@onAvailable
                }
                val host = activeProxyHost ?: return@onAvailable
                val port = activeProxyPort
                if (port !in 1..65535) {
                    return@onAvailable
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
                    val proxyError = proxyProbeCoordinator.waitForProxyWithRetry(host, port, activeProxyProtocol)
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
            },
            onLost = { manager, network ->
                nonVpnNetworkTracker.onLost(manager, network)
                updateUnderlyingNetworkHint(nonVpnNetworkTracker.currentNetwork())
                VpnRuntimeState.appendLog("Network lost.")
            },
            onUnregistered = {
                nonVpnNetworkTracker.clear()
                updateUnderlyingNetworkHint(null)
            }
        )
    }
    private val appTrafficMonitor by lazy {
        AppTrafficMonitor(
            packageManager = packageManager,
            currentPackageName = packageName,
            scope = serviceScope,
            logger = { message -> VpnRuntimeState.appendLog(message) },
            shouldContinue = { !isShuttingDown && !stopRequested }
        )
    }
    private var startJob: Job? = null
    @Volatile
    private var startFailed = false
    @Volatile
    private var stopAlreadyReported = false
    @Volatile
    private var isShuttingDown = false
    @Volatile
    private var stopRequested = false

    @RequiresApi(Build.VERSION_CODES.Q)
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
            VpnRuntimeState.appendLog("Proxy protocol: ${protocol.name}")
            VpnRuntimeState.setConnecting(host, port)
            val proxyError = proxyProbeCoordinator.waitForProxyWithRetry(host, port, protocol)
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
        bridgeManager.cancelJobs()
        appTrafficMonitor.stop()
        if (!stopRequested) {
            bridgeManager.stopAsync()
        }
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopHttpBridge()
        activeProxyHost = null
        activeProxyPort = -1
        activeProxyProtocol = ProxyProtocol.Socks5
        nonVpnNetworkTracker.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val preserveErrorState = !stopRequested && VpnRuntimeState.state.value.status == RuntimeStatus.Error
        if (!stopAlreadyReported && !preserveErrorState) {
            VpnRuntimeState.setStopped("VPN stopped.")
        }
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        networkWatcher.register()
    }

    private fun unregisterNetworkCallback() {
        networkWatcher.unregister()
    }

    override fun onRevoke() {
        requestStopAsync("VPN permission revoked by system.")
        super.onRevoke()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startVpnTunnel(): Boolean {
        if (vpnInterface != null) {
            VpnRuntimeState.appendLog("Reconfiguring active VPN tunnel with latest settings.")
            resetActiveTunnelStateForRestart()
        }

        val settingsStore = ProxySettingsStore(this)
        val host = activeProxyHost
        val port = activeProxyPort
        val protocol = activeProxyProtocol
        val httpTrafficMode = settingsStore.loadHttpTrafficMode()
        val proxyBypassRules = settingsStore.loadProxyBypassList()
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
            VpnRuntimeState.appendLog("HTTP traffic mode: ${httpTrafficMode.name}")
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
                val proxyInfo = if (proxyBypassRules.isEmpty()) {
                    ProxyInfo.buildDirectProxy(safeHost, port)
                } else {
                    ProxyInfo.buildDirectProxy(safeHost, port, proxyBypassRules)
                }
                builder.setHttpProxy(proxyInfo)
                if (proxyBypassRules.isEmpty()) {
                    VpnRuntimeState.appendLog("HTTP proxy bridge enabled for $safeHost:$port")
                } else {
                    VpnRuntimeState.appendLog(
                        "HTTP proxy bridge enabled for $safeHost:$port with ${proxyBypassRules.size} bypass rules"
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
        appTrafficMonitor.configureAndStart(routingMode, selectedPackages)
        val (bridgeHost, bridgePort, udpMode) = resolveTunnelProxyEndpoint(
            protocol = protocol,
            upstreamHost = safeHost,
            upstreamPort = port,
            proxyBypassRules = proxyBypassRules,
            allowDirectFallbackForNonHttpPorts = httpTrafficMode == HttpTrafficMode.CompatFallback
        )
        if (bridgePort !in 1..65535) {
            return false
        }
        val configFile = writeHevTunnelConfig(
            host = bridgeHost,
            port = bridgePort,
            udpMode = udpMode,
            enableMappedDns = protocol == ProxyProtocol.Http
        ) ?: return false
        bridgeManager.start(tunnelFd, configFile)
        return true
    }

    private fun resetActiveTunnelStateForRestart() {
        bridgeManager.cancelJobs()
        appTrafficMonitor.stop()
        stopHttpBridge()
        bridgeManager.stopNow()
        vpnInterface?.close()
        vpnInterface = null
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
        bridgeManager.cancelJobs()
        vpnInterface?.close()
        vpnInterface = null
        stopHttpBridge()
        activeProxyHost = null
        activeProxyPort = -1
        activeProxyProtocol = ProxyProtocol.Socks5
        nonVpnNetworkTracker.clear()
        updateUnderlyingNetworkHint(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        bridgeManager.stopAsync()
        appTrafficMonitor.stop()
    }

    private fun writeHevTunnelConfig(
        host: String,
        port: Int,
        udpMode: String,
        enableMappedDns: Boolean
    ): File? {
        return try {
            val configFile = File(filesDir, HEV_CONFIG_FILE)
            val mapDnsConfig = if (enableMappedDns) {
                MapDnsConfig(
                    address = MAP_DNS_ADDRESS,
                    port = MAP_DNS_PORT,
                    network = MAP_DNS_NETWORK,
                    netmask = MAP_DNS_NETMASK,
                    cacheSize = MAP_DNS_CACHE_SIZE
                )
            } else {
                null
            }
            val config = HevTunnelConfigBuilder.build(
                host = host,
                port = port,
                udpMode = udpMode,
                mapDnsConfig = mapDnsConfig
            )
            configFile.writeText(config.trimEnd())
            configFile
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTunnelProxyEndpoint(
        protocol: ProxyProtocol,
        upstreamHost: String,
        upstreamPort: Int,
        proxyBypassRules: List<String>,
        allowDirectFallbackForNonHttpPorts: Boolean
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
            proxyBypassRuleMatcher = { destinationHost, _ ->
                HttpBypassRuleMatcher.findMatchingRule(destinationHost, proxyBypassRules)
            },
            bypassVpnForSocket = { socket -> bypassVpnForSocket(socket) },
            allowDirectFallbackForNonHttpPorts = allowDirectFallbackForNonHttpPorts,
            logger = { message -> VpnRuntimeState.appendLog(message) }
        )
        val localPort = bridge.start()
        httpBridge = bridge
        VpnRuntimeState.appendLog("HTTP upstream enabled via local SOCKS bridge on 127.0.0.1:$localPort")
        return Triple("127.0.0.1", localPort, "tcp")
    }

    private fun resolveUpstreamProxyAddress(host: String, port: Int): InetSocketAddress? {
        return nonVpnNetworkTracker.resolveAddress(host, port)
    }

    private fun bypassVpnForSocket(socket: Socket): Boolean {
        return nonVpnNetworkTracker.bypassVpnForSocket(socket) { targetSocket -> protect(targetSocket) }
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
            if (prepare(context) != null) {
                VpnRuntimeState.setError("VPN permission required. Open app and tap Start.")
                return
            }
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
