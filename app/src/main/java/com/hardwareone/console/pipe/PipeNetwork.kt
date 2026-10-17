package com.hardwareone.console.pipe

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Production [PipeConnector]: an ordinary TCP socket bound to the VPN network this app is routed
 * through (WireGuard's tunnel), never to cellular or Wi-Fi, and never `VpnService.protect`ed.
 * DNS uses that network's resolvers. One resolution, one address, one connect: no retries, no
 * second address after a failure, no fallback route. The lookup itself cannot be interrupted, so
 * the engine abandons it at the OPEN deadline and caps abandoned attempts (then OPEN_ERR 10). Binding means a vanished VPN fails the socket instead of
 * letting it leak onto another network.
 */
class VpnPipeConnector(private val vpn: VpnNetworkTracker) : PipeConnector {
    override fun connect(target: PipeTarget, timeoutMs: Long): PipeSocket {
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        val network = vpn.current() ?: throw PipeConnectException(PipeError.VPN_UNAVAILABLE)
        // Bind first: an app the VPN excludes fails here (EPERM), before any DNS traffic.
        val socket = Socket()
        try {
            network.bindSocket(socket)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.VPN_UNAVAILABLE)
        }
        // A VPN without DNS servers makes netd resolve on the physical network instead, and a DNS
        // server outside the tunnel's routes falls through to it: either would leak the relay name.
        if (target.type == PipeTarget.DNS && !dnsStaysInTunnel(vpn.dnsServers(), vpn::routes)) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.VPN_UNAVAILABLE)
        }
        val address: InetAddress = try {
            when (target.type) {
                // The first answer the tunnel routes (an AAAA first on an IPv4-only tunnel must not
                // fail the OPEN). Choosing a destination address is not a retry: one connect follows.
                PipeTarget.DNS -> network.getAllByName(target.hostString()).firstOrNull { vpn.routes(it) }
                    ?: throw PipeConnectException(PipeError.VPN_UNAVAILABLE)
                // Keep the device's 16 bytes as an IPv6 address even when they are IPv4-mapped.
                PipeTarget.IPV6 -> Inet6Address.getByAddress(null, target.host, -1)
                else -> InetAddress.getByAddress(target.host)
            }
        } catch (e: UnknownHostException) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.DNS_FAILED)
        } catch (e: SecurityException) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.VPN_UNAVAILABLE)
        } catch (e: PipeConnectException) {
            runCatching { socket.close() }
            throw e
        }
        if (!vpn.routes(address)) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.VPN_UNAVAILABLE)
        }
        val remaining = deadline - System.nanoTime() / 1_000_000
        if (remaining <= 0) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.CONNECT_TIMEOUT)
        }
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, target.port), remaining.toInt().coerceAtLeast(1))
        } catch (e: SocketTimeoutException) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.CONNECT_TIMEOUT)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw PipeConnectException(PipeError.CONNECT_FAILED)
        }
        return TcpPipeSocket(socket)
    }
}

/**
 * True when name resolution on the VPN network cannot leave the tunnel: it has DNS servers and
 * the tunnel routes every one of them. (A Private DNS hostname server cannot be checked here.)
 */
fun dnsStaysInTunnel(servers: List<InetAddress>, routes: (InetAddress) -> Boolean): Boolean =
    servers.isNotEmpty() && servers.all(routes)

/** [PipeSocket] over a connected java.net.Socket. Writes go out in small slices so credit flows. */
class TcpPipeSocket(private val socket: Socket) : PipeSocket {
    override val remote: InetAddress? get() = socket.inetAddress

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)

    override fun write(b: ByteArray, off: Int, len: Int): Int {
        val n = minOf(len, WRITE_SLICE)
        output.write(b, off, n)
        return n
    }

    override fun shutdownOutput() = socket.shutdownOutput()

    override fun close() = socket.close()

    private companion object {
        const val WRITE_SLICE = 1024
    }
}
