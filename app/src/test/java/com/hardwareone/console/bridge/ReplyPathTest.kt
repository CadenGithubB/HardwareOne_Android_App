package com.hardwareone.console.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class ReplyPathTest {
    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun peerStaysInsideTheHotspotSubnetAndOffTheBroadcastAddress() {
        assertEquals("10.42.0.198", ReplyPathCheck.peerFor(LocalInterface("ap0", v4("10.42.0.197"), 24)).hostAddress)
        assertEquals("192.168.43.2", ReplyPathCheck.peerFor(LocalInterface("ap0", v4("192.168.43.1"), 24)).hostAddress)
        assertEquals("192.168.43.253", ReplyPathCheck.peerFor(LocalInterface("ap0", v4("192.168.43.254"), 24)).hostAddress)
        assertEquals("10.0.0.2", ReplyPathCheck.peerFor(LocalInterface("ap0", v4("10.0.0.1"), 30)).hostAddress)
        assertEquals("10.0.0.1", ReplyPathCheck.peerFor(LocalInterface("ap0", v4("10.0.0.2"), 30)).hostAddress)
        // Unknown prefix defaults to /24.
        assertEquals("172.20.10.2", ReplyPathCheck.peerFor(LocalInterface("ap0", v4("172.20.10.1"))).hostAddress)
    }

    @Test
    fun subnetIsTheNetworkPrefix() {
        assertEquals("10.42.0.0/24", LocalInterface("ap0", v4("10.42.0.197"), 24).subnet)
        assertEquals("192.168.0.0/16", LocalInterface("ap0", v4("192.168.43.1"), 16).subnet)
        assertEquals("10.1.2.3/32", LocalInterface("ap0", v4("10.1.2.3"), 32).subnet)
    }

    @Test
    fun classifiesTheChosenSourceAddress() {
        val hotspot = v4("10.42.0.197")
        val vpn = setOf("10.66.66.2", "fd00:6:6::2")
        assertEquals(ReplyPath.Direct, ReplyPathCheck.classify(hotspot, v4("10.42.0.197"), vpn))
        assertEquals(ReplyPath.Captured("10.66.66.2", viaVpn = true), ReplyPathCheck.classify(hotspot, v4("10.66.66.2"), vpn))
        assertEquals(ReplyPath.Captured("198.51.100.23", viaVpn = false), ReplyPathCheck.classify(hotspot, v4("198.51.100.23"), vpn))
        assertTrue(ReplyPathCheck.classify(hotspot, null, vpn) is ReplyPath.Unknown)
        assertTrue(ReplyPathCheck.classify(hotspot, v4("0.0.0.0"), vpn) is ReplyPath.Unknown)
    }

    @Test
    fun probeOnLoopbackIsDirect() {
        // The real code path on this JVM: a connected UDP socket to another loopback address picks
        // the loopback source, which is the "hotspot" here. Nothing is sent.
        val lo = LocalInterface("lo", v4("127.0.0.1"), 8)
        assertEquals(ReplyPath.Direct, ReplyPathCheck.probe(lo, emptySet(), v4("127.0.0.2")))
    }

    @Test
    fun adviceNamesTheHotspotPrefixToExcludeAndTheHomePrefixesToKeep() {
        val captured = ReplyPath.Captured("10.66.66.2", viaVpn = true)
        val text = ReplyPathCheck.advice(captured, "10.42.0.0/24", "192.168.1.143")!!
        assertTrue(text, text.contains("10.42.0.0/24"))
        assertTrue(text, text.contains("Exclude private IPs"))
        assertTrue(text, text.contains("192.168.1.0/24"))
        assertTrue(text, text.contains("10.66.66.0/24"))
        assertTrue(text, text.contains("10.66.66.2"))
        // A relay named by host name yields no /24 suggestion for it, but the tunnel's own does.
        val byName = ReplyPathCheck.advice(captured, "10.42.0.0/24", "relay.home.arpa")!!
        assertTrue(byName, byName.contains("10.66.66.0/24"))
        assertTrue(byName, !byName.contains("relay.home.arpa"))
        // Other outcomes.
        assertTrue(ReplyPathCheck.advice(ReplyPath.Captured("198.51.100.23", viaVpn = false), null, null)!!.contains("198.51.100.23"))
        assertTrue(ReplyPathCheck.advice(ReplyPath.Blocked("SocketException: EACCES"), null, null)!!.contains("lockdown"))
        assertNull(ReplyPathCheck.advice(ReplyPath.Direct, "10.42.0.0/24", "192.168.1.143"))
        assertNull(ReplyPathCheck.advice(ReplyPath.Unknown("x"), null, null))
    }
}
