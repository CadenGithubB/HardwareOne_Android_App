package com.hardwareone.console.bridge

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.UnknownHostException

/** One local IPv4 interface that could be the phone's hotspot. */
data class LocalInterface(val name: String, val address: Inet4Address, val prefixLength: Int = 24) {
    val label: String get() = "${address.hostAddress} ($name)"
    val subnet: String get() = ReplyPathCheck.subnetOf(this)
}

/**
 * Finds the tethering interface. Android gives apps no hotspot API, but the hotspot interface is
 * the one UP interface with a private IPv4 address that no ConnectivityManager network (Wi-Fi
 * client, cellular, VPN) claims. Names are only used to rank candidates, never to decide.
 */
object HotspotInterfaces {
    private val EXCLUDED_PREFIXES = listOf("lo", "tun", "tap", "ppp", "wg", "rmnet", "ccmni", "v4-", "dummy", "clat", "ipsec", "pdp", "umts", "r_rmnet", "utun")
    private val PREFERRED = listOf("ap", "swlan", "softap", "wlan1", "wlan")

    fun candidates(cm: ConnectivityManager?): List<LocalInterface> = scan(cm).candidates

    /** What the scan saw, for the "waiting" message: candidates plus every private IPv4 it skipped and why. */
    class Scan(val candidates: List<LocalInterface>, val skipped: List<String>)

    fun scan(cm: ConnectivityManager?): Scan {
        // Addresses that belong to an upstream-style network (Wi-Fi client, cellular, VPN) are not
        // the hotspot. Newer Android also registers the tethering downstream itself as a
        // "local network" Network (no INTERNET capability); those must stay eligible.
        val claimed = HashMap<String, String>()
        if (cm != null) {
            @Suppress("DEPRECATION")
            for (network in runCatching { cm.allNetworks }.getOrDefault(emptyArray())) {
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                val isLocalNetwork = Build.VERSION.SDK_INT >= 35 && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK) == true
                val upstreamLike = caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (isLocalNetwork || !upstreamLike) continue
                val kind = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi client"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    else -> "another network"
                }
                runCatching { cm.getLinkProperties(network) }.getOrNull()?.linkAddresses?.forEach { la ->
                    la.address.hostAddress?.let { claimed[it] = kind }
                }
            }
        }
        val found = mutableListOf<LocalInterface>()
        val skipped = mutableListOf<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
            ?: return Scan(emptyList(), listOf("interface list unavailable"))
        for (nif in interfaces) {
            val name = nif.name ?: continue
            val addresses = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            if (addresses.isEmpty()) continue
            if (EXCLUDED_PREFIXES.any { name.startsWith(it) }) { addresses.forEach { skipped += "$name ${it.hostAddress} (excluded name)" }; continue }
            if (runCatching { !nif.isUp || nif.isLoopback || nif.isPointToPoint }.getOrDefault(true)) { addresses.forEach { skipped += "$name ${it.hostAddress} (down or point-to-point)" }; continue }
            for (address in addresses) {
                val owner = claimed[address.hostAddress]
                if (owner != null) {
                    skipped += "$name ${address.hostAddress} ($owner)"
                } else {
                    val prefix = runCatching { nif.interfaceAddresses.firstOrNull { it.address == address }?.networkPrefixLength?.toInt() }.getOrNull() ?: 24
                    found += LocalInterface(name, address, prefix)
                }
            }
        }
        return Scan(found.sortedWith(compareBy({ rank(it.name) }, { it.name })), skipped)
    }

    private fun rank(name: String): Int = PREFERRED.indexOfFirst { name.startsWith(it) }.let { if (it < 0) PREFERRED.size else it }

    /** The address to bind: the configured override if it is currently present, else the best candidate. */
    fun choose(candidates: List<LocalInterface>, override: String?): LocalInterface? {
        if (override != null) return candidates.firstOrNull { it.address.hostAddress == override }
        return candidates.firstOrNull()
    }
}

/**
 * Tracks the VPN network visible to this app (WireGuard's tunnel). Only networks with
 * TRANSPORT_VPN are considered, so if WireGuard excludes this app there is simply no network,
 * and the bridge refuses to forward rather than falling back to cellular.
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

    fun interfaceName(): String? = synchronized(lock) { link?.interfaceName }

    /** The tunnel's DNS servers (empty when it has none: Android then resolves elsewhere). */
    fun dnsServers(): List<InetAddress> = synchronized(lock) { link?.dnsServers?.toList() ?: emptyList() }

    /** The tunnel's own addresses (e.g. `10.66.66.2`), to recognise replies it would capture. */
    fun addresses(): Set<String> = synchronized(lock) {
        link?.linkAddresses?.mapNotNull { it.address.hostAddress }?.toSet() ?: emptySet()
    }

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

/** Opens relay sockets bound to the VPN network, and refuses when there is none. */
class VpnBoundConnector(private val vpn: VpnNetworkTracker) : UpstreamConnector {
    override fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val network = vpn.current() ?: throw UpstreamException(
            FailureKind.NO_VPN,
            "No VPN network is available to this app. Connect WireGuard and make sure HardwareOne is not excluded from its tunnel.",
        )
        val address = try {
            network.getByName(host)
        } catch (e: UnknownHostException) {
            throw UpstreamException(FailureKind.RELAY_UNREACHABLE, "Could not resolve $host through the VPN's DNS.", e)
        }
        if (!vpn.routes(address)) {
            throw UpstreamException(
                FailureKind.ROUTE_MISSING,
                "The VPN tunnel does not route ${address.hostAddress}. Add the home subnet to WireGuard's Allowed IPs.",
            )
        }
        val socket = Socket()
        try {
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), timeoutMs)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw UpstreamException(
                FailureKind.RELAY_UNREACHABLE,
                "Could not reach the home relay at ${address.hostAddress}:$port through the VPN (${e.javaClass.simpleName}). Is the relay running and the tunnel up?",
                e,
            )
        }
        return socket
    }
}
