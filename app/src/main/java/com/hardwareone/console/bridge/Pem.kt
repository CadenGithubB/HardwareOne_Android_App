package com.hardwareone.console.bridge

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** PEM helpers for X.509 certificates (no key material ever goes through here as PEM). */
object Pem {
    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"
    private val BLOCK = Regex("-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END CERTIFICATE-----")

    /** Standard 64-column PEM, trailing newline included — the form OpenSSL writes. */
    fun encodeCertificate(cert: X509Certificate): String {
        val b64 = Base64.getEncoder().encodeToString(cert.encoded)
        val sb = StringBuilder(b64.length + 80)
        sb.append(BEGIN).append('\n')
        var i = 0
        while (i < b64.length) {
            sb.append(b64, i, minOf(i + 64, b64.length)).append('\n')
            i += 64
        }
        sb.append(END).append('\n')
        return sb.toString()
    }

    /** Every CERTIFICATE block in [pem]. Throws [IllegalArgumentException] if none parse. */
    fun decodeCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val out = BLOCK.findAll(pem).map { match ->
            val der = try {
                Base64.getMimeDecoder().decode(match.groupValues[1])
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Certificate is not valid base64", e)
            }
            factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.toList()
        require(out.isNotEmpty()) { "No CERTIFICATE block found" }
        return out
    }

    /** `llmhttpca` takes the PEM on one line with literal `\n` escapes; the firmware unescapes. */
    fun escapedForDeviceCommand(pem: String): String =
        pem.trimEnd('\n', '\r').replace("\r\n", "\n").replace("\n", "\\n")

    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
}
