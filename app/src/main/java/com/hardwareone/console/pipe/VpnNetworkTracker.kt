package com.hardwareone.console.pipe

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.os.Build
import java.net.InetAddress

/**
 * Tracks the VPN network visible to this app (WireGuard's tunnel). Only networks with
 * TRANSPORT_VPN are considered, so if WireGuard excludes this app there is simply no network,
 * and the pipe refuses to connect rather than falling back to cellular.
 */
class VpnNetworkTracker(private val cm: ConnectivityManager, private val onChange: () -> Unit) {
    private val lock = Any()
    private var network: Network? = null
    private var link: LinkProperties? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            synchronized(lock) { network = n; link = runCatching { cm.getLinkProperties(n) }.getOrNull() }
            onChange()
        }

        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            synchronized(lock) { if (n == network) link = lp }
            onChange()
        }

        override fun onLost(n: Network) {
            synchronized(lock) { if (n == network) { network = null; link = null } }
            onChange()
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        synchronized(lock) { network = null; link = null }
    }

    fun current(): Network? = synchronized(lock) { network }

    /** The tunnel's DNS servers (empty when it has none: Android then resolves elsewhere). */
    fun dnsServers(): List<InetAddress> = synchronized(lock) { link?.dnsServers?.toList() ?: emptyList() }

    /** True when the tunnel's most specific route for [address] is a normal unicast route. */
    fun routes(address: InetAddress): Boolean {
        val lp = synchronized(lock) { link } ?: return false
        var best: RouteInfo? = null
        for (route in lp.routes) {
            val prefix = route.destination
            if (!prefix.contains(address)) continue
            if (best == null || prefix.prefixLength > best.destination.prefixLength) best = route
        }
        val chosen = best ?: return false
        // Route type (unicast vs. unreachable/throw for excluded ranges) is public API only from 33.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) chosen.type == RouteInfo.RTN_UNICAST else true
    }
}
