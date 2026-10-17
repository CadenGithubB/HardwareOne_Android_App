package com.hardwareone.console.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end on loopback: fake ESP32 → BridgeServer → fake relay, with real TLS on both legs.
 * These are the forwarding, authentication, TLS-failure, limit, cancellation and uncertain-
 * disconnect cases the design promises; hardware adds only the radios around them.
 */
class BridgeServerTest {

    @Test
    fun streamsChatReplyThroughIncrementallyWithoutBuffering() {
        val gotFirstEvent = CountDownLatch(1)
        BridgeHarness(responder = { _, out, _ ->
            out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
            out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n".toByteArray()); out.flush()
            // Hold the stream open until the device has seen the first event, proving the bridge
            // forwards as bytes arrive rather than waiting for [DONE].
            BridgeHarness.await(gotFirstEvent, "device to receive first event")
            out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}\n\n".toByteArray()); out.flush()
            out.write("data: [DONE]\n\n".toByteArray()); out.flush()
        }).use { h ->
            val response = h.device.request()
            assertEquals(200, response.head.status)
            assertEquals("text/event-stream", response.head.mediaType())
            assertNull(response.head.contentLength)
            assertEquals("close", response.head.headers["connection"])
            val first = response.readUntil { it.contains("\"Hel\"") }
            assertTrue(first, first.contains("\"Hel\"") && !first.contains("[DONE]"))
            gotFirstEvent.countDown()
            val all = response.readToEnd()
            assertTrue(all, all.endsWith("data: [DONE]\n\n"))
            assertTrue(all.contains("\"lo\""))
            response.close()

            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.COMPLETED, record.outcome)
            assertEquals(BridgeRoute.CHAT, record.route)
            assertEquals(200, record.httpStatus)
            assertTrue(record.bytesForwarded > 0)
            assertEquals(RelayReachability.REACHABLE, h.observer.relay.last().reachability)

            // The relay saw exactly the request shape relay.py admits: allowed header names only.
            val seen = h.relay.requests.single()
            assertEquals("/v1/chat/completions", seen.head.target)
            assertEquals(setOf("host", "user-agent", "content-type", "accept", "authorization", "content-length", "connection"), seen.head.headers.keys)
            assertEquals("Bearer device-token-0123456789abcdef0123456789", seen.head.headers["authorization"])
            assertEquals("text/event-stream", seen.head.headers["accept"])
            assertEquals("127.0.0.1:${h.relay.port}", seen.head.headers["host"])
            assertTrue(seen.body.toString(Charsets.UTF_8).contains("\"hi\""))
        }
    }

    @Test
    fun forwardsTerminalJsonWithExactContentLength() {
        val reply = """{"schema":1,"ok":true,"requestId":"0123456789abcdef0123456789abcdef","instance":"i","sessions":[],"nextOffset":null}"""
        BridgeHarness(responder = { _, out, _ -> out.write(BridgeHarness.json(200, "OK", reply)); out.flush() }).use { h ->
            val body = """{"schema":1,"op":"list","requestId":"0123456789abcdef0123456789abcdef","offset":0,"limit":10}"""
            val response = h.device.request(path = BridgeRoute.TERMINAL.path, body = body, accept = "application/json")
            assertEquals(200, response.head.status)
            assertEquals("application/json", response.head.mediaType())
            assertEquals(reply.length.toLong(), response.head.contentLength)
            assertEquals(reply, response.readExactly(reply.length))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.COMPLETED, record.outcome)
            assertEquals(BridgeRoute.TERMINAL, record.route)
            assertEquals("application/json", h.relay.requests.single().head.headers["accept"])
        }
    }

    @Test
    fun passesRelayAuthRejectionThroughAndFlagsIt() {
        val body = """{"error":{"message":"Unauthorized"}}"""
        BridgeHarness(responder = { _, out, _ -> out.write(BridgeHarness.json(401, "Unauthorized", body)); out.flush() }).use { h ->
            val response = h.device.request(token = "wrong-token-wrong-token-wrong-token-1")
            assertEquals(401, response.head.status)
            assertEquals(body, response.readExactly(body.length))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.COMPLETED, record.outcome)
            assertEquals(401, record.httpStatus)
            assertEquals(RelayReachability.AUTH_REJECTED, h.observer.relay.last().reachability)
            assertTrue(h.observer.relay.last().detail!!.contains("llmhttptoken"))
        }
    }

    @Test
    fun refusesUnknownPathMethodMissingTokenAndOversizeLocally() {
        BridgeHarness().use { h ->
            assertEquals(404, h.device.request(path = "/v1/models").also { it.readToEnd(); it.close() }.head.status)
            assertEquals(405, h.device.request(method = "GET").also { it.readToEnd(); it.close() }.head.status)
            assertEquals(401, h.device.request(token = null).also { it.readToEnd(); it.close() }.head.status)
            assertEquals(401, h.device.request(token = "has space").also { it.readToEnd(); it.close() }.head.status)
            assertEquals(415, h.device.request(contentType = "text/plain").also { it.readToEnd(); it.close() }.head.status)
            assertEquals(413, h.device.request(contentLength = 20000).also { it.readToEnd(); it.close() }.head.status)
            assertEquals(413, h.device.request(path = BridgeRoute.TERMINAL.path, contentLength = 5000).also { it.readToEnd(); it.close() }.head.status)
            assertEquals(400, h.device.request(extraHeaders = "Transfer-Encoding: chunked\r\n").also { it.readToEnd(); it.close() }.head.status)
            // Every refusal happened before any relay connection existed.
            assertEquals(0, h.relay.connections.get())
            val records = (0 until 8).map { h.observer.awaitRecord(it) }
            assertTrue(records.all { it.outcome == RequestOutcome.REJECTED_LOCALLY })
            assertEquals(listOf(404, 405, 401, 401, 415, 413, 413, 400), records.map { it.httpStatus })
        }
    }

    @Test
    fun errorBodiesAreJsonLikeTheRelay() {
        BridgeHarness().use { h ->
            val response = h.device.request(path = "/nope")
            assertEquals("application/json", response.head.mediaType())
            val body = response.readExactly(response.head.contentLength!!.toInt())
            assertEquals("""{"error":{"message":"Not found"}}""", body)
            response.close()
        }
    }

    @Test
    fun untrustedRelayCertificateFailsBeforeSendingAnything() {
        val otherCa = BridgeCa.generate()
        BridgeHarness(trustedCaPem = otherCa.certificatePem).use { h ->
            val response = h.device.request()
            assertEquals(502, response.head.status)
            val body = response.readExactly(response.head.contentLength!!.toInt())
            assertTrue(body, body.contains("relay tls untrusted"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.FAILED_BEFORE_SEND, record.outcome)
            assertEquals(FailureKind.RELAY_TLS_UNTRUSTED, record.failure)
            assertEquals(RelayReachability.TLS_UNTRUSTED, h.observer.relay.last().reachability)
            assertTrue(h.relay.requests.isEmpty())
        }
    }

    @Test
    fun relayCertificateForAnotherHostIsRejected() {
        val relayCa = BridgeCa.generate()
        val wrongHostIdentity = relayCa.issueServerIdentity(java.net.InetAddress.getByName("10.9.9.9"))
        BridgeHarness(relayCa = relayCa, relayIdentity = wrongHostIdentity).use { h ->
            val response = h.device.request()
            assertEquals(502, response.head.status)
            response.readToEnd(); response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.FAILED_BEFORE_SEND, record.outcome)
            assertTrue(record.failure.toString(), record.failure == FailureKind.RELAY_TLS_HOSTNAME || record.failure == FailureKind.RELAY_TLS_UNTRUSTED)
            assertTrue(h.relay.requests.isEmpty())
        }
    }

    @Test
    fun unreachableRelayAndMissingVpnAreReportedDistinctly() {
        BridgeHarness(relayPortOverride = BridgeHarness.freePort()).use { h ->
            val response = h.device.request()
            assertEquals(502, response.head.status)
            response.readToEnd(); response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.FAILED_BEFORE_SEND, record.outcome)
            assertEquals(FailureKind.RELAY_UNREACHABLE, record.failure)
            assertEquals(RelayReachability.UNREACHABLE, h.observer.relay.last().reachability)
        }
        val noVpn = UpstreamConnector { _, _, _ -> throw UpstreamException(FailureKind.NO_VPN, "No VPN network is available to this app.") }
        BridgeHarness(connector = noVpn).use { h ->
            val response = h.device.request()
            assertEquals(502, response.head.status)
            val body = response.readExactly(response.head.contentLength!!.toInt())
            assertTrue(body, body.contains("no vpn"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(FailureKind.NO_VPN, record.failure)
            assertEquals(RelayReachability.NO_VPN, h.observer.relay.last().reachability)
            assertEquals(0, h.relay.connections.get())
        }
    }

    @Test
    fun relayDroppingMidStreamYieldsSseErrorAndUnknownOutcomeWithoutRetry() {
        val deviceSawFirst = CountDownLatch(1)
        BridgeHarness(responder = { _, out, _ ->
            out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
            out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"part\"}}]}\n\n".toByteArray()); out.flush()
            BridgeHarness.await(deviceSawFirst, "device to see first event")
            throw java.io.IOException("relay died") // closes the socket without [DONE]
        }).use { h ->
            val response = h.device.request()
            assertEquals(200, response.head.status)
            response.readUntil { it.contains("\"part\"") }
            deviceSawFirst.countDown()
            val all = response.readToEnd()
            assertTrue(all, all.contains("event: error\n"))
            assertTrue(all, all.contains("agent outcome unknown"))
            assertFalse(all, all.contains("[DONE]"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.OUTCOME_UNKNOWN, record.outcome)
            assertEquals(FailureKind.OUTCOME_UNKNOWN, record.failure)
            assertTrue(record.detail!!, record.detail!!.contains("[DONE]"))
            Thread.sleep(200)
            assertEquals("no automatic replay", 1, h.relay.connections.get())
        }
    }

    @Test
    fun relayClosingBeforeHeadersIsAnUnknownOutcomeNotARetry() {
        BridgeHarness(responder = { _, _, _ -> throw java.io.IOException("relay crashed before answering") }).use { h ->
            val response = h.device.request()
            assertEquals(502, response.head.status)
            val body = response.readExactly(response.head.contentLength!!.toInt())
            assertTrue(body, body.contains("outcome unknown"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.OUTCOME_UNKNOWN, record.outcome)
            Thread.sleep(200)
            assertEquals(1, h.relay.connections.get())
        }
    }

    @Test
    fun deviceDisconnectMidStreamClosesTheRelayConnection() {
        val deviceSawFirst = CountDownLatch(1)
        BridgeHarness(holdRelayOpen = true, responder = { _, out, _ ->
            out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
            out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"thinking\"}}]}\n\n".toByteArray()); out.flush()
            // Relay now waits silently (model thinking); the fake relay then watches for the
            // bridge closing its side.
            BridgeHarness.await(deviceSawFirst, "device to see first event")
        }).use { h ->
            val response = h.device.request()
            response.readUntil { it.contains("thinking") }
            deviceSawFirst.countDown()
            response.close() // the ESP32 user pressed Stop
            assertTrue("bridge closed the relay connection", h.relay.peerClosed.await(5, TimeUnit.SECONDS))
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.CANCELLED_BY_DEVICE, record.outcome)
        }
    }

    @Test
    fun deadlineEndsASilentStreamAsUnknownOutcome() {
        val short = TEST_LIMITS.copy(chatTotalTimeoutMs = 1_200, upstreamIdleTimeoutMs = 10_000)
        val release = CountDownLatch(1)
        BridgeHarness(limits = short, responder = { _, out, _ ->
            out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
            release.await(10, TimeUnit.SECONDS) // never sends a token
        }).use { h ->
            val response = h.device.request()
            assertEquals(200, response.head.status)
            val all = response.readToEnd(timeoutMs = 6_000)
            assertTrue(all, all.contains("event: error"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.OUTCOME_UNKNOWN, record.outcome)
            assertEquals(FailureKind.TIMED_OUT, record.failure)
            release.countDown()
        }
    }

    @Test
    fun idleRelayStreamTimesOutAsUnknownOutcome() {
        val short = TEST_LIMITS.copy(upstreamIdleTimeoutMs = 800)
        val release = CountDownLatch(1)
        BridgeHarness(limits = short, responder = { _, out, _ ->
            out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
            release.await(10, TimeUnit.SECONDS)
        }).use { h ->
            val response = h.device.request()
            val all = response.readToEnd(timeoutMs = 6_000)
            assertTrue(all, all.contains("event: error"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.OUTCOME_UNKNOWN, record.outcome)
            assertTrue(record.detail!!.contains("stalled"))
            release.countDown()
        }
    }

    @Test
    fun heartbeatsKeepTheDeviceAliveWhileTheRelayIsSilent() {
        // The firmware drops a reply whose bytes stop for 60 s; the bridge fills a silent relay
        // with SSE comments instead, so a slow first token completes. Comments are not relay bytes.
        val limits = TEST_LIMITS.copy(upstreamHeartbeatIntervalMs = 250, upstreamIdleTimeoutMs = 5_000)
        val event = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Late\"}}]}\n\n"
        BridgeHarness(limits = limits, responder = { _, out, _ ->
            out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
            Thread.sleep(1_100)
            out.write(event.toByteArray()); out.flush()
            out.write("data: [DONE]\n\n".toByteArray()); out.flush()
        }).use { h ->
            val response = h.device.request()
            assertEquals(200, response.head.status)
            val all = response.readToEnd(timeoutMs = 6_000)
            val beats = Regex(": keep-alive\n\n").findAll(all).count()
            assertTrue("heartbeats before the event: $all", beats >= 2)
            assertTrue(all, all.indexOf(": keep-alive") < all.indexOf("data: {"))
            assertTrue(all, all.endsWith("data: [DONE]\n\n"))
            assertFalse(all, all.contains("event: error"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.COMPLETED, record.outcome)
            assertEquals((event + "data: [DONE]\n\n").length.toLong(), record.bytesForwarded)
        }
    }

    @Test
    fun heartbeatsStopAtTheRelayIdleLimit() {
        val limits = TEST_LIMITS.copy(upstreamHeartbeatIntervalMs = 200, upstreamIdleTimeoutMs = 700)
        val release = CountDownLatch(1)
        BridgeHarness(limits = limits, responder = { _, out, _ ->
            out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
            release.await(10, TimeUnit.SECONDS)
        }).use { h ->
            val response = h.device.request()
            val all = response.readToEnd(timeoutMs = 6_000)
            assertTrue(all, all.contains(": keep-alive"))
            assertTrue(all, all.contains("event: error"))
            response.close()
            val record = h.observer.awaitRecord()
            assertEquals(RequestOutcome.OUTCOME_UNKNOWN, record.outcome)
            assertTrue(record.detail!!, record.detail!!.contains("stalled"))
            assertEquals(0L, record.bytesForwarded)
            release.countDown()
        }
    }

    @Test
    fun silentDeviceGets408AndSlowBodyToo() {
        BridgeHarness().use { h ->
            val socket = h.device.connect()
            val response = h.device.readResponse(socket) // bridge must answer on its own after the header timeout
            assertEquals(408, response.head.status)
            response.readToEnd(); response.close()
            assertEquals(RequestOutcome.REJECTED_LOCALLY, h.observer.awaitRecord().outcome)

            val socket2 = h.device.connect()
            socket2.outputStream.write("POST /v1/terminal HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer abcdefabcdefabcdefabcdefabcdefabcdef\r\nContent-Type: application/json\r\nContent-Length: 50\r\n\r\n{\"half".toByteArray())
            socket2.outputStream.flush()
            val response2 = h.device.readResponse(socket2)
            assertEquals(408, response2.head.status)
            response2.readToEnd(); response2.close()
            assertEquals(0, h.relay.connections.get())
        }
    }

    @Test
    fun oversizedHeadIsRefusedWith431() {
        BridgeHarness().use { h ->
            val response = h.device.request(extraHeaders = "X-Pad: " + "a".repeat(9000) + "\r\n")
            assertEquals(431, response.head.status)
            response.readToEnd(); response.close()
        }
    }

    @Test
    fun concurrentRouteRequestsGet503Busy() {
        val holdFirst = CountDownLatch(1)
        val terminalReply = """{"schema":1,"ok":true,"requestId":"0123456789abcdef0123456789abcdef","instance":"i","sessions":[],"nextOffset":null}"""
        BridgeHarness(responder = { req, out, _ ->
            if (req.head.target == BridgeRoute.TERMINAL.path) {
                out.write(BridgeHarness.json(200, "OK", terminalReply)); out.flush()
            } else {
                out.write(BridgeHarness.SSE_HEAD.toByteArray()); out.flush()
                holdFirst.await(10, TimeUnit.SECONDS)
                out.write("data: [DONE]\n\n".toByteArray()); out.flush()
            }
        }).use { h ->
            val first = h.device.request()
            assertEquals(200, first.head.status)
            val second = h.device.request()
            assertEquals(503, second.head.status)
            second.readToEnd(); second.close()
            // A Terminal request has its own slot and is not blocked by the chat in flight.
            val terminal = h.device.request(path = BridgeRoute.TERMINAL.path, body = "{}", accept = "application/json")
            assertEquals(200, terminal.head.status)
            assertEquals(terminalReply, terminal.readExactly(terminalReply.length))
            terminal.close()
            holdFirst.countDown()
            assertTrue(first.readToEnd().endsWith("data: [DONE]\n\n")); first.close()
            val records = (0 until 3).map { h.observer.awaitRecord(it) }
            assertEquals(1, records.count { it.failure == FailureKind.BUSY && it.httpStatus == 503 })
            assertEquals(2, records.count { it.outcome == RequestOutcome.COMPLETED })
        }
    }

    @Test
    fun connectionCapClosesExtraConnectionsWithoutReply() {
        BridgeHarness(limits = TEST_LIMITS.copy(maxConcurrentConnections = 1, deviceHeaderTimeoutMs = 4_000)).use { h ->
            val held = h.device.connect() // occupies the single slot, sends nothing yet
            val extra = java.net.Socket()
            extra.connect(java.net.InetSocketAddress(LOOPBACK, h.server.boundPort), 2_000)
            extra.soTimeout = 3_000
            assertEquals("closed without a byte", -1, extra.getInputStream().read())
            extra.close()
            held.close()
        }
    }

    @Test
    fun terminalReplyWithoutAcceptableLengthIsRefused() {
        BridgeHarness(responder = { _, out, _ ->
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()); out.flush()
        }).use { h ->
            val response = h.device.request(path = BridgeRoute.TERMINAL.path, body = "{}", accept = "application/json")
            assertEquals(502, response.head.status)
            response.readToEnd(); response.close()
            val record = h.observer.awaitRecord()
            assertEquals(FailureKind.RELAY_ERROR, record.failure)
            assertEquals(RequestOutcome.OUTCOME_UNKNOWN, record.outcome)
        }
    }

    @Test
    fun listenerLifecycleIsObserved() {
        val h = BridgeHarness()
        val listening = h.observer.listener.first()
        assertTrue(listening is ListenerState.Listening)
        listening as ListenerState.Listening
        assertEquals("127.0.0.1", listening.address)
        assertEquals(h.server.boundPort, listening.port)
        assertEquals("https://127.0.0.1:${h.server.boundPort}/v1", listening.deviceUrl)
        h.close()
        assertEquals(ListenerState.Stopped, h.observer.listener.last())
        // Idempotent close.
        h.server.close()
    }

    @Test
    fun relayProbeReportsReachabilityWithoutSendingAPrompt() {
        BridgeHarness(responder = { req, out, _ ->
            assertEquals("GET", req.head.method)
            out.write(BridgeHarness.json(401, "Unauthorized", """{"error":{"message":"Unauthorized"}}""")); out.flush()
        }).use { h ->
            val result = h.client.probe()
            assertTrue(result.detail, result.reachable)
            assertEquals(401, result.httpStatus)
            assertNull(result.failure)
            assertFalse(h.relay.requests.single().head.headers.containsKey("authorization"))
        }
        BridgeHarness(relayPortOverride = BridgeHarness.freePort()).use { h ->
            val result = h.client.probe()
            assertFalse(result.reachable)
            assertEquals(FailureKind.RELAY_UNREACHABLE, result.failure)
        }
        val otherCa = BridgeCa.generate()
        BridgeHarness(trustedCaPem = otherCa.certificatePem).use { h ->
            val result = h.client.probe()
            assertFalse(result.reachable)
            assertEquals(FailureKind.RELAY_TLS_UNTRUSTED, result.failure)
            assertNotNull(result.detail)
        }
    }
}
