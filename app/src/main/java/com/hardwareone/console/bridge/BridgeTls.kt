package com.hardwareone.console.bridge

import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager

/** TLS plumbing for both legs. No option here ever weakens verification. */
object BridgeTls {
    private val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")
    private const val ALIAS = "bridge"

    /** Server side (hotspot leg): presents [identity]; never asks the device for a client cert. */
    fun serverContext(identity: ServerIdentity): SSLContext {
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (matches(keyType)) arrayOf(ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (matches(keyType)) ALIAS else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
                if (matches(keyType)) ALIAS else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) identity.chain else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) identity.privateKey else null
            private fun matches(keyType: String?) = keyType != null && keyType.equals(identity.privateKey.algorithm, ignoreCase = true)
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(km), null, null) }
    }

    fun configure(server: SSLServerSocket) {
        server.enabledProtocols = server.supportedProtocols.filter { it in PROTOCOLS }.toTypedArray()
        server.needClientAuth = false
        server.wantClientAuth = false
    }

    /** Client side (home leg): trusts exactly [trustAnchors], nothing from the system store. */
    fun clientContext(trustAnchors: List<X509Certificate>): SSLContext {
        require(trustAnchors.isNotEmpty()) { "A trust anchor is required" }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trustAnchors.forEachIndexed { i, cert -> store.setCertificateEntry("home-ca-$i", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    /** Enables the platform's hostname check on top of chain validation and pins protocols. */
    fun configure(client: SSLSocket) {
        client.useClientMode = true
        client.enabledProtocols = client.supportedProtocols.filter { it in PROTOCOLS }.toTypedArray()
        client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }

    /**
     * Explicit subjectAltName check, run after the handshake in addition to the platform's: the
     * relay certificate names its IP (SAN iPAddress) or a hostname (SAN dNSName). No CN fallback.
     */
    fun peerMatchesHost(cert: X509Certificate, host: String): Boolean {
        val sans = cert.subjectAlternativeNames ?: return false
        val wantIp = if (BridgeConfig.IPV4.matches(host)) InetAddress.getByName(host) else null
        for (san in sans) {
            val type = san[0] as? Int ?: continue
            val value = san[1] as? String ?: continue
            when (type) {
                7 -> if (wantIp != null && runCatching { InetAddress.getByName(value) }.getOrNull() == wantIp) return true
                2 -> if (wantIp == null && dnsMatches(value, host)) return true
            }
        }
        return false
    }

    private fun dnsMatches(pattern: String, host: String): Boolean {
        val p = pattern.lowercase().trimEnd('.')
        val h = host.lowercase().trimEnd('.')
        if (!p.startsWith("*.")) return p == h
        val suffix = p.substring(1) // ".example.com"
        return h.endsWith(suffix) && h.count { it == '.' } == p.count { it == '.' }
    }
}
