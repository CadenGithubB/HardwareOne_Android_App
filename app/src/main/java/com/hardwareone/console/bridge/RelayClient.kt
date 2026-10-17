package com.hardwareone.console.bridge

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Supplies the plain TCP socket to the relay. The Android implementation binds it to the VPN
 * network and refuses when there is none; tests connect directly. Throws [UpstreamException]
 * with the reason when no socket can be offered.
 */
fun interface UpstreamConnector {
    fun connect(host: String, port: Int, timeoutMs: Int): Socket
}

class UpstreamException(val kind: FailureKind, message: String, cause: Throwable? = null) : IOException(message, cause)

/** An open, verified connection to the relay with the response head already parsed. */
class UpstreamResponse(private val socket: SSLSocket, val head: HttpResponseHead, val input: InputStream) : Closeable {
    fun setReadTimeout(ms: Int) { socket.soTimeout = ms }
    override fun close() = runCatching { socket.close() }.let { }
}

data class ProbeResult(val reachable: Boolean, val httpStatus: Int?, val failure: FailureKind?, val detail: String)

/**
 * The only thing the bridge knows how to talk to: the configured home relay, over TLS pinned to
 * the configured CA, with the relay's own small header vocabulary (relay.py ALLOWED_HEADERS).
 * Requests are never retried here or anywhere else.
 */
class RelayClient(
    private val config: BridgeConfig,
    private val limits: BridgeLimits,
    private val connector: UpstreamConnector,
    private val sslContext: SSLContext = BridgeTls.clientContext(Pem.decodeCertificates(config.relayCaPem)),
    private val userAgent: String = "HardwareOne-Console-Bridge/1",
) {
    /**
     * Connects, verifies, sends one request and returns once the response head has arrived.
     * Any failure after the first byte was written is reported as [FailureKind.OUTCOME_UNKNOWN].
     */
    fun open(route: BridgeRoute, authorization: String, body: ByteArray): UpstreamResponse {
        val ssl = handshake()
        var sent = false
        try {
            val head = buildString {
                append("POST ").append(route.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(config.relayHost).append(':').append(config.relayPort).append("\r\n")
                append("User-Agent: ").append(userAgent).append("\r\n")
                append("Content-Type: application/json\r\n")
                append("Accept: ").append(route.accept).append("\r\n")
                append("Authorization: ").append(authorization).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            val out = ssl.outputStream
            sent = true
            out.write(head)
            out.write(body)
            out.flush()
            ssl.soTimeout = limits.upstreamHeaderTimeoutMs
            val input = BufferedInputStream(ssl.inputStream, 8192)
            val headBytes = HttpHead.readHead(input, limits.maxHeaderBytes)
                ?: throw UpstreamException(FailureKind.OUTCOME_UNKNOWN, "The relay closed the connection before answering.")
            return UpstreamResponse(ssl, HttpHead.parseResponse(headBytes, limits.maxHeaderLines), input)
        } catch (e: UpstreamException) {
            runCatching { ssl.close() }
            throw e
        } catch (e: SocketTimeoutException) {
            runCatching { ssl.close() }
            throw UpstreamException(FailureKind.OUTCOME_UNKNOWN, "The relay accepted the request but sent no response headers within ${limits.upstreamHeaderTimeoutMs / 1000} s.", e)
        } catch (e: IOException) {
            runCatching { ssl.close() }
            val kind = if (sent) FailureKind.OUTCOME_UNKNOWN else FailureKind.RELAY_UNREACHABLE
            throw UpstreamException(kind, "Connection to the relay failed (${e.javaClass.simpleName}).", e)
        }
    }

    /**
     * Reachability check that sends no prompt: TLS handshake plus an unauthenticated `GET /`,
     * which the relay answers 401. Any well-formed HTTP reply proves route, TLS and relay process.
     */
    fun probe(): ProbeResult {
        val ssl = try {
            handshake(limits.probeTimeoutMs)
        } catch (e: UpstreamException) {
            return ProbeResult(false, null, e.kind, e.message ?: e.kind.name)
        }
        try {
            ssl.outputStream.apply {
                write("GET / HTTP/1.1\r\nHost: ${config.relayHost}:${config.relayPort}\r\nUser-Agent: $userAgent\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            ssl.soTimeout = limits.probeTimeoutMs
            val headBytes = HttpHead.readHead(BufferedInputStream(ssl.inputStream), limits.maxHeaderBytes)
                ?: return ProbeResult(false, null, FailureKind.RELAY_ERROR, "The relay closed the probe connection without an HTTP reply.")
            val head = HttpHead.parseResponse(headBytes, limits.maxHeaderLines)
            return ProbeResult(true, head.status, null, "Relay answered HTTP ${head.status} to an unauthenticated probe (401 is the expected answer).")
        } catch (e: IOException) {
            return ProbeResult(false, null, FailureKind.RELAY_ERROR, "The relay did not complete the probe (${e.javaClass.simpleName}).")
        } finally {
            runCatching { ssl.close() }
        }
    }

    /** TCP via the connector, TLS pinned to the home CA, then the explicit host check. */
    private fun handshake(timeoutMs: Int = limits.upstreamHandshakeTimeoutMs): SSLSocket {
        val plain = try {
            connector.connect(config.relayHost, config.relayPort, limits.upstreamConnectTimeoutMs)
        } catch (e: UpstreamException) {
            throw e
        } catch (e: IOException) {
            throw UpstreamException(
                FailureKind.RELAY_UNREACHABLE,
                "Could not connect to the relay at ${config.relayHost}:${config.relayPort} (${e.javaClass.simpleName}). Is it running and reachable through the tunnel?",
                e,
            )
        }
        var ssl: SSLSocket? = null
        try {
            plain.soTimeout = timeoutMs
            ssl = sslContext.socketFactory.createSocket(plain, config.relayHost, config.relayPort, true) as SSLSocket
            BridgeTls.configure(ssl)
            ssl.startHandshake()
            val peer = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw UpstreamException(FailureKind.RELAY_TLS_UNTRUSTED, "The relay presented no X.509 certificate.")
            if (!BridgeTls.peerMatchesHost(peer, config.relayHost)) {
                throw UpstreamException(
                    FailureKind.RELAY_TLS_HOSTNAME,
                    "The relay certificate is trusted but is not issued for ${config.relayHost}. Point the bridge at the address in the certificate, or reissue the relay certificate.",
                )
            }
            return ssl
        } catch (e: UpstreamException) {
            runCatching { (ssl ?: plain).close() }
            throw e
        } catch (e: SSLException) {
            runCatching { (ssl ?: plain).close() }
            throw UpstreamException(classifyTls(e), tlsMessage(e), e)
        } catch (e: SocketTimeoutException) {
            runCatching { (ssl ?: plain).close() }
            throw UpstreamException(FailureKind.RELAY_UNREACHABLE, "The relay did not complete the TLS handshake within ${timeoutMs / 1000} s.", e)
        } catch (e: IOException) {
            runCatching { (ssl ?: plain).close() }
            throw UpstreamException(FailureKind.RELAY_UNREACHABLE, "Could not connect to the relay (${e.javaClass.simpleName}).", e)
        }
    }

    private fun classifyTls(e: SSLException): FailureKind {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is CertificateException) {
                val m = cause.message?.lowercase() ?: ""
                return if ("hostname" in m || "subjectaltname" in m || "not verified" in m || "no name matching" in m) {
                    FailureKind.RELAY_TLS_HOSTNAME
                } else {
                    FailureKind.RELAY_TLS_UNTRUSTED
                }
            }
            cause = cause.cause
        }
        return FailureKind.RELAY_TLS_UNTRUSTED
    }

    private fun tlsMessage(e: SSLException): String = when (classifyTls(e)) {
        FailureKind.RELAY_TLS_HOSTNAME ->
            "The relay certificate is not issued for ${config.relayHost}. Use the address named in the certificate."
        else ->
            "The relay's certificate is not signed by the imported CA (or the handshake failed: ${e.javaClass.simpleName}). Re-import ca-cert.pem from the relay host."
    }
}
