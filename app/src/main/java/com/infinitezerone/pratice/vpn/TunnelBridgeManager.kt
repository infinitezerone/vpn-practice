package com.infinitezerone.pratice.vpn

import org.amnezia.awg.hevtunnel.TProxyService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelBridgeManager(
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
    private val shouldRun: () -> Boolean,
    private val onBridgeFailure: (Throwable) -> Unit
) {
    private var bridgeJob: Job? = null
    private var statsJob: Job? = null

    fun start(tunnelFd: Int, configFile: File) {
        cancelJobs()
        bridgeJob = scope.launch {
            try {
                logger("Starting tun2socks bridge.")
                TProxyService.TProxyStartService(configFile.absolutePath, tunnelFd)
                if (shouldRun()) {
                    logger("tun2socks bridge returned control.")
                }
            } catch (e: Throwable) {
                if (shouldRun()) {
                    onBridgeFailure(e)
                }
            }
        }
        statsJob = scope.launch {
            var lastSnapshot: String? = null
            while (shouldRun()) {
                try {
                    val stats = TProxyService.TProxyGetStats()
                    if (stats != null && stats.isNotEmpty()) {
                        val snapshot = stats.joinToString(prefix = "[", postfix = "]")
                        if (snapshot != lastSnapshot) {
                            lastSnapshot = snapshot
                            logger("tun2socks stats=$snapshot")
                        }
                    }
                } catch (_: Throwable) {
                    // Bridge not ready or already stopped.
                }
                delay(2_000)
            }
        }
    }

    fun cancelJobs() {
        bridgeJob?.cancel()
        bridgeJob = null
        statsJob?.cancel()
        statsJob = null
    }

    fun stopAsync() {
        scope.launch(Dispatchers.IO) {
            stopNow()
        }
    }

    fun stopNow() {
        try {
            TProxyService.TProxyStopService()
            logger("tun2socks bridge stopped.")
        } catch (_: Throwable) {
            // Native bridge may already be stopped.
        }
    }
}
