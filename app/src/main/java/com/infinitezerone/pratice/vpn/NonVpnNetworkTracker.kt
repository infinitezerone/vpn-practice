package com.infinitezerone.pratice.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class NonVpnNetworkTracker {
    @Volatile
    private var activeNonVpnNetwork: Network? = null

    fun initialize(manager: ConnectivityManager?): Network? {
        activeNonVpnNetwork = findBestNonVpnNetwork(manager)
        return activeNonVpnNetwork
    }

    fun onAvailable(manager: ConnectivityManager?, network: Network): Boolean {
        val capabilities = manager?.getNetworkCapabilities(network) ?: return false
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return false
        }
        activeNonVpnNetwork = network
        return true
    }

    fun onLost(manager: ConnectivityManager?, network: Network): Network? {
        if (network == activeNonVpnNetwork) {
            activeNonVpnNetwork = findBestNonVpnNetwork(manager)
        }
        return activeNonVpnNetwork
    }

    fun clear() {
        activeNonVpnNetwork = null
    }

    fun currentNetwork(): Network? = activeNonVpnNetwork

    fun resolveAddress(host: String, port: Int): InetSocketAddress? {
        val safeHost = EndpointSanitizer.sanitizeHost(host)
        if (port !in 1..65535 || safeHost.isBlank()) {
            return null
        }
        val network = activeNonVpnNetwork
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

    fun bypassVpnForSocket(socket: Socket, protectSocket: (Socket) -> Boolean): Boolean {
        val protected = protectSocket(socket)
        val network = activeNonVpnNetwork
        var bound = false
        if (network != null) {
            try {
                network.bindSocket(socket)
                bound = true
            } catch (_: Exception) {
                // Keep protected path result below.
            }
        }
        return protected || bound
    }

    private fun findBestNonVpnNetwork(manager: ConnectivityManager?): Network? {
        val cm = manager ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val capabilities = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
