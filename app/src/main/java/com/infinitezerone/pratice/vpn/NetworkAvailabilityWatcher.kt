package com.infinitezerone.pratice.vpn

import android.net.ConnectivityManager
import android.net.Network

class NetworkAvailabilityWatcher(
    private val connectivityManagerProvider: () -> ConnectivityManager?,
    private val logger: (String) -> Unit,
    private val onRegistered: (ConnectivityManager) -> Unit,
    private val onAvailable: (ConnectivityManager, Network) -> Unit,
    private val onLost: (ConnectivityManager, Network) -> Unit,
    private val onUnregistered: () -> Unit
) {
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register() {
        if (callback != null) {
            return
        }
        val cm = connectivityManagerProvider() ?: run {
            logger("Network callback unavailable on this device.")
            return
        }

        val watcherCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailable(cm, network)
            }

            override fun onLost(network: Network) {
                onLost(cm, network)
            }
        }

        try {
            cm.registerDefaultNetworkCallback(watcherCallback)
            manager = cm
            callback = watcherCallback
            onRegistered(cm)
        } catch (_: Exception) {
            manager = null
            callback = null
            onUnregistered()
            logger("Network callback unavailable on this device.")
        }
    }

    fun unregister() {
        val cm = manager
        val watcherCallback = callback
        if (cm != null && watcherCallback != null) {
            try {
                cm.unregisterNetworkCallback(watcherCallback)
            } catch (_: Exception) {
                // Callback already unregistered.
            }
        }
        manager = null
        callback = null
        onUnregistered()
    }
}
