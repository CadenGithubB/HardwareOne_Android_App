package com.hardwareone.console.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpHeadTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun readsExactlyOneHeadAndLeavesTheBody() {
        val input = ByteArrayInputStream(bytes("POST /v1/terminal HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}TRAILING"))
        val head = HttpHead.readHead(input, 8192)!!
        assertEquals("POST /v1/terminal HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n", String(head, Charsets.US_ASCII))
        assertEquals("{}TRAILING", input.readBytes().toString(Charsets.US_ASCII))
    }

    @Test
    fun headReadReturnsNullOnEofAndThrowsWhenTooLarge() {
        assertNull(HttpHead.readHead(ByteArrayInputStream(bytes("GET / HTTP/1.1\r\nHost: x\r\n")), 8192))
        try {
            HttpHead.readHead(ByteArrayInputStream(bytes("X".repeat(300))), 256)
            fail("expected too-large")
        } catch (e: HttpHeadException) {
            assertTrue(e.tooLarge)
        }
    }

    @Test
    fun parsesRequestLowercasingNamesAndTrimmingValues() {
        val head = HttpHead.parseRequest(bytes("POST /v1/chat/completions HTTP/1.1\r\nHost:  127.0.0.1:18790 \r\nAuthorization: Bearer abc\r\nContent-Length: 12\r\n\r\n"), 32)
        assertEquals("POST", head.method)
        assertEquals("/v1/chat/completions", head.target)
        assertEquals("127.0.0.1:18790", head.headers["host"])
        assertEquals("Bearer abc", head.headers["authorization"])
        assertEquals("12", head.headers["content-length"])
    }

    @Test
    fun rejectsDuplicateHeadersControlBytesAndBadStartLines() {
        for (bad in listOf(
            "POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n",
            "POST / HTTP/1.1\r\nX-Bad: a\u0001b\r\n\r\n",
            "POST / HTTP/1.1\r\nNo colon here\r\n\r\n",
            "POST / HTTP/2.0\r\n\r\n",
            "post / HTTP/1.1\r\n\r\n",
            "POST / HTTP/1.1\r\né: x\r\n\r\n",
        )) {
            try {
                HttpHead.parseRequest(bytes(bad), 32)
                fail("expected rejection of: $bad")
            } catch (_: HttpHeadException) {
            }
        }
    }

    @Test
    fun enforcesLineLimit() {
        val many = "POST / HTTP/1.1\r\n" + (1..40).joinToString("") { "H$it: v\r\n" } + "\r\n"
        try {
            HttpHead.parseRequest(bytes(many), 32)
            fail("expected too many lines")
        } catch (_: HttpHeadException) {
        }
    }

    @Test
    fun parsesResponseFraming() {
        val sse = HttpHead.parseResponse(bytes("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n"), 32)
        assertEquals(200, sse.status)
        assertEquals("OK", sse.reason)
        assertEquals("text/event-stream", sse.mediaType())
        assertNull(sse.contentLength)
        assertFalse(sse.isChunked)

        val json = HttpHead.parseResponse(bytes("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: 40\r\n\r\n"), 32)
        assertEquals(401, json.status)
        assertEquals(40L, json.contentLength)

        val chunked = HttpHead.parseResponse(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"), 32)
        assertTrue(chunked.isChunked)

        val noReason = HttpHead.parseResponse(bytes("HTTP/1.1 503\r\n\r\n"), 32)
        assertEquals(503, noReason.status)
        assertEquals("", noReason.reason)

        val badLength = HttpHead.parseResponse(bytes("HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\n"), 32)
        assertNull(badLength.contentLength)
    }
}
