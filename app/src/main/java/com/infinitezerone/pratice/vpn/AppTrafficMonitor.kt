package com.infinitezerone.pratice.vpn

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Build
import com.infinitezerone.pratice.config.RoutingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppTrafficMonitor(
    private val packageManager: PackageManager,
    private val currentPackageName: String,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
    private val shouldContinue: () -> Boolean
) {
    private var job: Job? = null
    private val monitoredUidToLabel = mutableMapOf<Int, String>()
    private val lastUidTraffic = mutableMapOf<Int, Pair<Long, Long>>()

    fun configureAndStart(routingMode: RoutingMode, selectedPackages: Set<String>) {
        stop()

        val monitoredPackages = when (routingMode) {
            RoutingMode.Allowlist -> selectedPackages
            RoutingMode.Bypass -> {
                val launchablePackages = getInstalledApplicationsCompat(packageManager)
                    .asSequence()
                    .filter { appInfo ->
                        appInfo.packageName != currentPackageName &&
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
            logger("App traffic monitor unavailable: no matching app UIDs.")
            return
        }

        val sample = monitoredUidToLabel.values.take(5).joinToString()
        logger("App traffic monitor active for ${monitoredUidToLabel.size} apps. Sample: $sample")
        startSampling()
    }

    fun stop() {
        job?.cancel()
        job = null
        monitoredUidToLabel.clear()
        lastUidTraffic.clear()
    }

    private fun startSampling() {
        job?.cancel()
        job = scope.launch {
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
                logger("App traffic monitor unavailable: UID byte counters are not exposed on this device build.")
                return@launch
            }

            var idleIntervals = 0
            while (shouldContinue()) {
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
                    logger("VPN app traffic: $top")
                } else {
                    idleIntervals += 1
                    if (idleIntervals % 6 == 0) {
                        logger("VPN app traffic: no monitored app data in the last ${idleIntervals * 4}s.")
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
}
