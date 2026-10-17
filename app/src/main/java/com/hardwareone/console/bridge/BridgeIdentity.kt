package com.hardwareone.console.bridge

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/** The key and chain the bridge presents to the ESP32 on the hotspot leg. */
class ServerIdentity(val privateKey: PrivateKey, val chain: Array<X509Certificate>)

/**
 * The bridge's own certificate authority. The ESP32 trusts this one certificate (`llmhttpca`), so
 * the server certificate can be reissued for whatever address the hotspot has today without
 * touching the device again. ECDSA P-256 keeps the CA PEM small enough to provision over a
 * single command. The private key lives only in the app's Keystore-encrypted store.
 */
class BridgeCa private constructor(val certificate: X509Certificate, private val privateKey: PrivateKey) {

    val certificatePem: String get() = Pem.encodeCertificate(certificate)

    /** PKCS#8 DER, base64 — the form the encrypted store keeps. Never log or display it. */
    fun privateKeyPkcs8Base64(): String = Base64.getEncoder().encodeToString(privateKey.encoded)

    /** A short-lived server certificate for exactly one hotspot address (SAN iPAddress). */
    fun issueServerIdentity(address: InetAddress, now: Date = Date()): ServerIdentity {
        val keys = generateKeyPair()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(certificate.subjectX500Principal.name),
            randomSerial(),
            Date(now.time - DAY_MS),
            Date(now.time + LEAF_VALIDITY_MS),
            X500Name("CN=HardwareOne Bridge"),
            keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.iPAddress, address.hostAddress)),
        )
        val leaf = JcaX509CertificateConverter().getCertificate(builder.build(signer(privateKey)))
        return ServerIdentity(keys.private, arrayOf(leaf, certificate))
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val CA_VALIDITY_MS = 20L * 365 * DAY_MS
        private const val LEAF_VALIDITY_MS = 2L * 365 * DAY_MS

        fun generate(now: Date = Date()): BridgeCa {
            val keys = generateKeyPair()
            val name = X500Name("CN=HW1 Bridge CA")
            val builder = JcaX509v3CertificateBuilder(
                name, randomSerial(), Date(now.time - DAY_MS), Date(now.time + CA_VALIDITY_MS), name, keys.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer(keys.private)))
            return BridgeCa(cert, keys.private)
        }

        /** Rebuild from the stored public PEM and the decrypted PKCS#8 key. */
        fun restore(certificatePem: String, privateKeyPkcs8Base64: String): BridgeCa {
            val cert = Pem.decodeCertificates(certificatePem).first()
            val key = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyPkcs8Base64)))
            return BridgeCa(cert, key)
        }

        private fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        private fun randomSerial(): BigInteger = BigInteger(63, SecureRandom())

        private fun signer(key: PrivateKey) = JcaContentSignerBuilder("SHA256withECDSA").build(key)
    }
}
