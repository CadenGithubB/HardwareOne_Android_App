package com.hardwareone.console.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `#NOTIF` contract (firmware `docs/APP_JSON_CONTRACT.md` sec. 9).
 *
 * The load-bearing cases are the two diversion rules — a card must never reach the off-console
 * capture buffer, and never reach the auth-state sniffer. Everything else here guards the
 * parser's forward-compatibility.
 */
class DeviceNotificationTest {

    private val sample =
        """#NOTIF {"kind":"peer_online","level":"info","title":"Mesh","msg":"Peer online: kitchen","ms":2000}"""

    @Test
    fun parsesTheDocumentedShape() {
        val card = DeviceNotification.parse(sample, nowMs = 1_700_000_000_000L)
        assertNotNull(card)
        requireNotNull(card)
        assertEquals("peer_online", card.kind)
        assertEquals(DeviceNotification.Level.INFO, card.level)
        assertEquals("Mesh", card.title)
        assertEquals("Peer online: kitchen", card.msg)
        assertEquals(2000, card.durationMs)
        assertEquals(1_700_000_000_000L, card.receivedAt)
    }

    @Test
    fun unescapesQuotesAndNewlinesInTheBody() {
        val card = DeviceNotification.parse(
            """#NOTIF {"kind":"wifi","level":"warning","title":"Network","msg":"SSID \"free\\wifi\"\nlost","ms":4000}""",
        )
        requireNotNull(card)
        assertEquals("SSID \"free\\wifi\"\nlost", card.msg)
        assertEquals(DeviceNotification.Level.WARNING, card.level)
    }

    /** A level this build doesn't know must degrade, never drop the card. */
    @Test
    fun unknownLevelDegradesToInfo() {
        val card = DeviceNotification.parse(
            """#NOTIF {"kind":"k","level":"catastrophe","title":"T","msg":"m","ms":1}""",
        )
        requireNotNull(card)
        assertEquals(DeviceNotification.Level.INFO, card.level)
    }

    @Test
    fun missingOptionalFieldsGetDefaults() {
        val card = DeviceNotification.parse("""#NOTIF {"kind":"k","msg":"m"}""")
        requireNotNull(card)
        assertEquals(DeviceNotification.Level.INFO, card.level)
        assertEquals("Device", card.title)
        assertEquals(DeviceNotification.DEFAULT_DURATION_MS, card.durationMs)
    }

    /** The device truncates `msg` to fit one BLE frame; a shortened body is still a valid card. */
    @Test
    fun acceptsATruncatedBody() {
        val card = DeviceNotification.parse(
            """#NOTIF {"kind":"espnow_rx","level":"info","title":"Mesh","msg":"Message from ki","ms":2000}""",
        )
        requireNotNull(card)
        assertEquals("Message from ki", card.msg)
    }

    @Test
    fun rejectsMalformedAndUnprefixedLines() {
        assertNull(DeviceNotification.parse("""{"kind":"k","msg":"m"}"""))       // no sentinel
        assertNull(DeviceNotification.parse("""#NOTIFY {"kind":"k","msg":"m"}""")) // near-miss
        assertNull(DeviceNotification.parse("""#NOTIF {"kind":"k","msg":"""))     // truncated JSON
        assertNull(DeviceNotification.parse("#NOTIF not json at all"))
        assertNull(DeviceNotification.parse("""#NOTIF {"level":"info"}"""))       // nothing to show
        // Leading whitespace is NOT tolerated: console text must never be eaten by accident.
        assertNull(DeviceNotification.parse("  $sample"))
    }

    // --- Diversion rules -------------------------------------------------------------

    /**
     * Rule 1. A card arriving while an off-console capture is armed must be pulled out of the
     * stream BEFORE the capture appends it. This is the regression test for the whole design:
     * if the remainder ever contains card text, a `status json` / `espnowmessages` reply gets
     * corrupted by an unrelated alert.
     */
    @Test
    fun cardIsRemovedFromTheTextTheCaptureWouldSee() {
        val split = DeviceNotification.split(sample + "\n")
        assertEquals(1, split.cards.size)
        assertTrue("card text must not survive into the capture", split.remainder.isBlank())
    }

    /** A card interleaved between two sends of a large reply leaves the reply text intact. */
    @Test
    fun replyTextSurvivesACardInTheSamePayload() {
        val split = DeviceNotification.split("""{"peers":[1,2,""" + "\n" + sample + "\n3]}")
        assertEquals(1, split.cards.size)
        assertEquals("""{"peers":[1,2,""" + "\n" + "3]}", split.remainder)
    }

    /**
     * Rule 2. `updateAuthFromReply` substring-matches "Login successful" / "Logged out", and a
     * card body carries device-supplied text (peer names, SSIDs, file names). A card saying so
     * must be diverted, so it can never reach the sniffer and flip the app's auth state.
     */
    @Test
    fun cardBodyCannotReachTheAuthSniffer() {
        for (phrase in listOf("Login successful", "Authentication required", "Logged out")) {
            val line = """#NOTIF {"kind":"file_written","level":"info","title":"Files","msg":"Saved $phrase.txt","ms":2000}"""
            val split = DeviceNotification.split(line)
            assertEquals(1, split.cards.size)
            assertTrue("'$phrase' leaked to the console path", split.remainder.isBlank())
        }
    }

    /** Braces in a card body must not look like the JSON a capture is waiting for. */
    @Test
    fun braceHeavyBodyIsStillFullyDiverted() {
        val split = DeviceNotification.split(
            """#NOTIF {"kind":"k","level":"info","title":"T","msg":"got {\"a\":1} back","ms":2000}""",
        )
        assertEquals(1, split.cards.size)
        assertEquals("got {\"a\":1} back", split.cards[0].msg)
        assertTrue(split.remainder.isBlank())
    }

    /** Ordinary console output that merely quotes the sentinel is left alone. */
    @Test
    fun consoleTextContainingTheSentinelPassesThrough() {
        val text = "the app diverts #NOTIF lines before the capture"
        val split = DeviceNotification.split(text)
        assertTrue(split.cards.isEmpty())
        assertEquals(0, split.malformed)
        assertEquals(text, split.remainder)
    }

    /** A prefixed line we can't read is counted but still shown — never silently swallowed. */
    @Test
    fun malformedCardIsCountedAndKept() {
        val bad = """#NOTIF {"kind":"k","msg":"""
        val split = DeviceNotification.split("$bad\nOK")
        assertTrue(split.cards.isEmpty())
        assertEquals(1, split.malformed)
        assertEquals("$bad\nOK", split.remainder)
    }

    /** No sentinel anywhere: the same string instance comes back, so the hot path allocates nothing. */
    @Test
    fun payloadWithoutASentinelIsReturnedUntouched() {
        val text = """{"success":true,"active":false}"""
        val split = DeviceNotification.split(text)
        assertSame(text, split.remainder)
        assertTrue(split.cards.isEmpty())
    }

    @Test
    fun multipleCardsInOnePayloadAllSurface() {
        val split = DeviceNotification.split("$sample\n$sample\nOK\n")
        assertEquals(2, split.cards.size)
        assertEquals("OK\n", split.remainder)
    }
}
