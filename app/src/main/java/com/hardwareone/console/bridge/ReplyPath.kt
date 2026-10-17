package com.hardwareone.console.bridge

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Where the kernel would send this app's reply to a hotspot client.
 *
 * Android's policy routing puts a non-bypassable VPN (WireGuard's default) ahead of everything
 * else for the uids it covers: the rule at priority 13000 sends every unprotected packet of such a
 * uid to the tunnel's table, and the hotspot's own rules only come at 16000/20000. With
 * `AllowedIPs 0.0.0.0/0` the tunnel table has a default route, so even the SYN-ACK answering the
 * ESP32's connection attempt leaves through WireGuard with the tunnel address as its source, and
 * the device sees a connect timeout. No socket option available to an app changes that (the VPN
 * rule tests only the "protected from VPN" bit, which only the VPN app can set); the tunnel's
 * AllowedIPs must leave the hotspot subnet out. This check lets the page say so instead of
 * showing a healthy listener that nothing can reach.
 */
sealed class ReplyPath {
    /** Replies leave through the hotspot interface: clients can connect. */
    data object Direct : ReplyPath()

    /** The kernel chose another source address, so another network swallows the replies. */
    data class Captured(val source: String, val viaVpn: Boolean) : ReplyPath()

    /** The kernel refused to route to a hotspot client at all (what VPN lockdown does). */
    data class Blocked(val detail: String) : ReplyPath()

    /** The probe gave no usable answer; nothing is claimed either way. */
    data class Unknown(val detail: String) : ReplyPath()
}

object ReplyPathCheck {
    private const val DISCARD_PORT = 9

    /**
     * Asks the kernel without sending anything: connecting a UDP socket only runs the route
     * lookup and fixes the source address a reply to [peer] would carry. [vpnAddresses] are the
     * tunnel's own addresses, used to name the culprit.
     */
    fun probe(hotspot: LocalInterface, vpnAddresses: Set<String>, peer: Inet4Address = peerFor(hotspot)): ReplyPath {
        val source = try {
            DatagramSocket().use { s ->
                s.connect(InetSocketAddress(peer, DISCARD_PORT))
                s.localAddress
            }
        } catch (e: Exception) {
            // SocketException (EACCES under lockdown, ENETUNREACH) or UncheckedIOException.
            return ReplyPath.Blocked(listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": "))
        }
        return classify(hotspot.address, source, vpnAddresses)
    }

    fun classify(hotspot: Inet4Address, source: InetAddress?, vpnAddresses: Set<String>): ReplyPath {
        val chosen = source?.hostAddress
        if (source == null || chosen == null || source.isAnyLocalAddress) return ReplyPath.Unknown("no source address chosen")
        if (chosen == hotspot.hostAddress) return ReplyPath.Direct
        return ReplyPath.Captured(chosen, viaVpn = chosen in vpnAddresses)
    }

    /**
     * Another host inside the hotspot's subnet, for the lookup: the next address, or the previous
     * one when the next would be the broadcast address. Any host in the prefix routes the same way.
     */
    fun peerFor(hotspot: LocalInterface): Inet4Address {
        val hostBits = 32 - hotspot.prefixLength.coerceIn(1, 30)
        val address = hotspot.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val network = address and ((0xffffffffL shl hostBits) and 0xffffffffL)
        val broadcastOffset = (1L shl hostBits) - 1
        val offset = address - network
        val peerOffset = if (offset + 1 < broadcastOffset) offset + 1 else offset - 1
        val peer = network + peerOffset
        val bytes = byteArrayOf((peer shr 24).toByte(), (peer shr 16).toByte(), (peer shr 8).toByte(), peer.toByte())
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    /** The "<network>/<prefix>" the hotspot hands out, e.g. `10.42.0.0/24`. */
    fun subnetOf(hotspot: LocalInterface): String {
        val prefix = hotspot.prefixLength.coerceIn(1, 32)
        val address = hotspot.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val network = if (prefix == 32) address else address and ((0xffffffffL shl (32 - prefix)) and 0xffffffffL)
        return "${network shr 24 and 0xff}.${network shr 16 and 0xff}.${network shr 8 and 0xff}.${network and 0xff}/$prefix"
    }

    /** What the user has to do, with the concrete prefixes filled in. Nothing secret. */
    fun advice(path: ReplyPath, hotspotSubnet: String?, relayHost: String?): String? {
        val subnet = hotspotSubnet ?: "the hotspot subnet"
        return when (path) {
            is ReplyPath.Captured -> if (path.viaVpn) {
                val keep = listOfNotNull(slash24(relayHost), slash24(path.source)).distinct()
                "Replies to hotspot clients are routed into the VPN (they would leave as ${path.source}), so the device " +
                    "cannot even connect. In WireGuard, edit the tunnel's Allowed IPs so they do not cover $subnet: tap " +
                    "\"Exclude private IPs\", then add your home networks back" +
                    (if (keep.isEmpty()) "" else " (e.g. ${keep.joinToString(", ")})") +
                    ". Save and reconnect the tunnel; this line disappears once replies leave through the hotspot."
            } else {
                "Replies to hotspot clients would leave as ${path.source}, not through the hotspot interface, so the " +
                    "device cannot connect. Check the listen address under Advanced, or which network owns ${path.source}."
            }
            is ReplyPath.Blocked ->
                "Android refused to route a reply to hotspot clients (${path.detail}). This is what WireGuard's " +
                    "\"Block connections without VPN\" (lockdown) does; the bridge cannot answer the device while it is on."
            ReplyPath.Direct, is ReplyPath.Unknown -> null
        }
    }

    private fun slash24(host: String?): String? {
        val parts = host?.split('.') ?: return null
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.0/24"
    }
}
