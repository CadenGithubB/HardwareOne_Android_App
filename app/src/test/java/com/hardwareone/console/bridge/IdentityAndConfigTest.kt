package com.hardwareone.console.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.Date

class IdentityAndConfigTest {

    @Test
    fun caPemRoundTripsAndStaysProvisionable() {
        val ca = BridgeCa.generate()
        val pem = ca.certificatePem
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
        val parsed = Pem.decodeCertificates(pem).single()
        assertEquals(ca.certificate, parsed)
        assertEquals("CN=HW1 Bridge CA", parsed.subjectX500Principal.name)
        assertTrue("CA flag", parsed.basicConstraints >= 0)
        // Firmware stores at most 4096 bytes (llmhttpca). The escaped one-line command is meant
        // for the device web CLI or serial console; keep it comfortably under 1 KiB. (A single
        // BLE command is capped at 512 bytes, so the UI only offers Bluetooth delivery when the
        // command fits the live link.)
        assertTrue("CA PEM ${pem.length} bytes", pem.length <= 4096)
        val command = "llmhttpca " + Pem.escapedForDeviceCommand(pem)
        assertTrue("device command ${command.length} bytes", command.length <= 1024)
        assertFalse(command.contains('\n'))
        assertTrue(command.contains("\\n-----END CERTIFICATE-----"))
    }

    @Test
    fun caRestoresFromStoredFormAndStillSigns() {
        val ca = BridgeCa.generate()
        val restored = BridgeCa.restore(ca.certificatePem, ca.privateKeyPkcs8Base64())
        val identity = restored.issueServerIdentity(InetAddress.getByName("192.168.43.1"))
        // Leaf chains to the CA and names only the hotspot address.
        identity.chain[0].verify(ca.certificate.publicKey)
        assertEquals(ca.certificate, identity.chain[1])
        val sans = identity.chain[0].subjectAlternativeNames.map { it[0] as Int to it[1] as String }
        assertEquals(listOf(7 to "192.168.43.1"), sans)
        assertTrue(identity.chain[0].basicConstraints < 0)
        assertTrue(identity.chain[0].extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
    }

    @Test
    fun leafValidityStartsSlightlyInThePast() {
        val ca = BridgeCa.generate()
        val now = Date()
        val leaf = ca.issueServerIdentity(InetAddress.getByName("10.0.0.1"), now).chain[0]
        assertTrue(leaf.notBefore.before(now))
        assertTrue(leaf.notAfter.after(Date(now.time + 365L * 24 * 3600 * 1000)))
    }

    @Test
    fun peerHostCheckAcceptsOnlyMatchingSan() {
        val ca = BridgeCa.generate()
        val leaf = ca.issueServerIdentity(InetAddress.getByName("192.0.2.10")).chain[0]
        assertTrue(BridgeTls.peerMatchesHost(leaf, "192.0.2.10"))
        assertFalse(BridgeTls.peerMatchesHost(leaf, "192.0.2.11"))
        assertFalse(BridgeTls.peerMatchesHost(leaf, "relay.example"))
        // The CA has no SAN at all: never matches anything.
        assertFalse(BridgeTls.peerMatchesHost(ca.certificate, "192.0.2.10"))
    }

    @Test
    fun pemDecodesMultipleBlocksAndRejectsGarbage() {
        val a = BridgeCa.generate()
        val b = BridgeCa.generate()
        val both = Pem.decodeCertificates(a.certificatePem + "# comment\n" + b.certificatePem)
        assertEquals(listOf(a.certificate, b.certificate), both)
        try {
            Pem.decodeCertificates("not a certificate")
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
        try {
            Pem.decodeCertificates("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
            fail("expected failure")
        } catch (_: Exception) {
        }
        assertNotEquals(Pem.sha256Fingerprint(a.certificate), Pem.sha256Fingerprint(b.certificate))
        assertEquals(95, Pem.sha256Fingerprint(a.certificate).length)
    }

    @Test
    fun configProblemsAreSpecific() {
        val ca = BridgeCa.generate().certificatePem
        assertEquals(emptyList<String>(), BridgeConfig("192.0.2.10", 18790, ca).problems())
        assertEquals(emptyList<String>(), BridgeConfig("relay.home.arpa", 443, ca, listenPort = 8443, listenAddressOverride = "192.168.43.1").problems())
        assertTrue(BridgeConfig("", 18790, ca).problems().single().contains("host"))
        assertTrue(BridgeConfig("192.0.2.10", 70000, ca).problems().single().contains("Relay port"))
        assertTrue(BridgeConfig("192.0.2.10", 18790, "nope").problems().single().contains("CA"))
        assertTrue(BridgeConfig("192.0.2.10", 18790, ca, listenAddressOverride = "ap0").problems().single().contains("Listen address"))
        assertTrue(BridgeConfig("bad host!", 0, "").problems().size >= 3)
    }

    @Test
    fun routesAreExactPaths() {
        assertEquals(BridgeRoute.CHAT, BridgeRoute.forPath("/v1/chat/completions"))
        assertEquals(BridgeRoute.TERMINAL, BridgeRoute.forPath("/v1/terminal"))
        assertEquals(null, BridgeRoute.forPath("/v1/chat/completions/"))
        assertEquals(null, BridgeRoute.forPath("/v1/models"))
        assertEquals(null, BridgeRoute.forPath("/v1/terminal?x=1"))
    }
}
