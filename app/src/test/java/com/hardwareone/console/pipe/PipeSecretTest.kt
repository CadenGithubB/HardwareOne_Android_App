package com.hardwareone.console.pipe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pipe passphrase entry rules and PSK derivation (DECISIONS Q6 / A-7 / A-9). Expected values come
 * from the firmware, not from [PipeSecret]: the P4 stores what `phonepipesecret` leaves after
 * Arduino `String::trim` (C-locale `isspace`: 09–0D, 20), checks `length() > 128` and then
 * `blePassphrasePolicyError` (length < 10, then upper, lower, digit, punctuation via C-locale
 * ctype) over raw bytes (Bluetooth.cpp `cmd_phonepipesecret`). Android additionally refuses what
 * the serial console cannot carry (NUL, CR, LF) and text that is not valid UTF-16. Every expected
 * byte string below was encoded independently of the implementation and is asserted as hex.
 * The PSK comes from tools/ble_secure/fixtures/phone_secure_channel_v1_vectors.json.
 */
class PipeSecretTest {

    // ---- Outer trim: ASCII whitespace bytes only ---------------------------------------------------

    @Test
    fun eachTrimByteIsRemovedAtTheFront() {
        for (c in TRIMMED) assertEquals("U+%04X".format(c.code), BASE_HEX, okHex("$c$BASE"))
    }

    @Test
    fun eachTrimByteIsRemovedAtTheBack() {
        for (c in TRIMMED) assertEquals("U+%04X".format(c.code), BASE_HEX, okHex("$BASE$c"))
    }

    @Test
    fun runsOfMixedTrimBytesAreRemovedOnBothSides() {
        assertEquals(BASE_HEX, okHex("\t\u000b\u000c  $BASE \u000c\u000b\t"))
    }

    /**
     * The other side of the trim set: C0 controls outside 09–0D (among them 1C–1F, which Java's
     * isWhitespace includes) and DEL are not C-locale `isspace`, so they stay, count and stop the trim.
     */
    @Test
    fun otherControlBytesAndDelAreKeptAtTheEdges() {
        for (code in (0x01..0x08) + (0x0e..0x1f) + 0x7f) {
            val c = code.toChar()
            val hex = "%02x".format(code)
            assertEquals("front 0x$hex", hex + BASE_HEX, okHex("$c$BASE"))
            assertEquals("back 0x$hex", BASE_HEX + hex, okHex("$BASE$c"))
            assertEquals("both 0x$hex", hex + BASE_HEX + hex, okHex(" $c$BASE$c\t"))
        }
        // The whitespace inside a kept byte is interior and stays.
        assertEquals("0120" + BASE_HEX + "201f", okHex(" \u0001 $BASE \u001f\t"))
        // A kept byte counts toward the minimum: 9 ASCII bytes + 1 = exactly 10.
        assertEquals("1f416231217878787878", okHex("\u001fAb1!xxxxx"))
        assertEquals("4162312178787878787f", okHex("Ab1!xxxxx\u007f"))
        assertEquals(SHORT_REASON, invalid("\u001fAb1!xxxx"))
    }

    /** CR and LF are trim bytes on the P4, but the serial console ends the line there: refused, not trimmed. */
    @Test
    fun crAndLfAreRefusedEvenWhereTheFirmwareWouldTrimThem() {
        for (c in listOf('\r', '\n')) {
            assertEquals(LINE_REASON, invalid("$c$BASE"))
            assertEquals(LINE_REASON, invalid("$BASE$c"))
        }
        assertEquals(LINE_REASON, invalid("$BASE\r\n"))
    }

    @Test
    fun interiorTabsSpacesQuotesAndFormFeedsArePreserved() {
        assertEquals("5061737320092231220b0c27576f726427", okHex("Pass \t\"1\"\u000b\u000c'Word'"))
        assertEquals("5061737320092231220b0c27576f726427", okHex("  Pass \t\"1\"\u000b\u000c'Word' \t"))
    }

    /** Arduino trim is byte-wise ASCII; String.trim() would strip these and change the key. */
    @Test
    fun outerNonAsciiWhitespaceIsPreservedAndCounted() {
        assertEquals("c2a0" + BASE_HEX + "c2a0", okHex("\u00a0$BASE\u00a0"))
        assertEquals("e280a8" + BASE_HEX + "e280a8", okHex("\u2028$BASE\u2028"))
        assertEquals("e38080" + BASE_HEX + "e38080", okHex("\u3000$BASE\u3000"))
        assertEquals("c285" + BASE_HEX, okHex("\u0085$BASE"))
        assertEquals("efbbbf" + BASE_HEX, okHex("\uFEFF$BASE"))
        // 7 ASCII bytes + U+3000 (3 bytes) = exactly 10: the ideographic space makes the minimum.
        assertEquals("4162312178797ae38080", okHex("Ab1!xyz\u3000"))
        assertEquals(SHORT_REASON, invalid("Ab1!xy\u00a0"))
        // Ten bytes of NBSP are not empty after trim: they fail the class policy instead.
        assertEquals(UPPER_REASON, invalid("\u00a0".repeat(5)))
    }

    // ---- Length in UTF-8 bytes ---------------------------------------------------------------------

    @Test
    fun multibyteCharactersCountInBytes() {
        // 6 characters, 10 bytes.
        assertEquals("41623121e282ace282ac", okHex("Ab1!€€"))
        // 5 characters, 6 bytes; 8 characters, 9 bytes.
        assertEquals(SHORT_REASON, invalid("Ab1!é"))
        assertEquals(SHORT_REASON, invalid("Ab1!éxyz"))
    }

    @Test
    fun minimumIsTenBytes() {
        assertEquals(SHORT_REASON, invalid(ascii(9)))
        assertEquals(10, ok(ascii(10)).size)
        assertEquals(toHex(ascii(10).toByteArray(Charsets.US_ASCII)), okHex(ascii(10)))
        // A 3-byte character carries the total from 9 to 10 with only 8 characters.
        val straddle = "Ab1!xxx€"
        assertEquals(8, straddle.length)
        assertEquals(10, ok(straddle).size)
        assertEquals(SHORT_REASON, invalid("Ab1!xx€"))
    }

    @Test
    fun maximumIs128Bytes() {
        assertEquals(128, ok(ascii(128)).size)
        assertEquals(toHex(ascii(128).toByteArray(Charsets.US_ASCII)), okHex(ascii(128)))
        assertTooLong(ascii(129))
        // Trim happens before the length check.
        assertEquals(128, ok("  ${ascii(128)}\t").size)
    }

    @Test
    fun multibyteCharacterStraddlingTheMaximumIsCountedInFull() {
        val euro = "€" // E2 82 AC
        val fits = ascii(125) + euro // 125 + 3 = 128 bytes
        assertEquals(126, fits.length)
        assertEquals(toHex(ascii(125).toByteArray(Charsets.US_ASCII)) + "e282ac", okHex(fits))
        val over = ascii(126) + euro // 126 + 3 = 129 bytes, though only 127 characters
        assertEquals(127, over.length)
        assertTooLong(over)

        val emoji = "😀" // U+1F600, F0 9F 98 80
        val emojiFits = ascii(124) + emoji // 128 bytes in 126 UTF-16 units
        assertEquals(128, ok(emojiFits).size)
        assertTrue(okHex(emojiFits).endsWith("f09f9880"))
        assertTooLong(ascii(125) + emoji)
    }

    /** The firmware checks the maximum before the policy, so a long weak entry gets the length reason. */
    @Test
    fun maximumIsCheckedBeforeThePolicy() {
        assertEquals(LONG_REASON, invalid("x".repeat(129)))
    }

    // ---- Character classes ------------------------------------------------------------------------

    @Test
    fun eachMissingClassIsReportedInTheFirmwaresOrder() {
        assertEquals(UPPER_REASON, invalid("ab1!ab1!ab"))
        assertEquals(LOWER_REASON, invalid("AB1!AB1!AB"))
        assertEquals(DIGIT_REASON, invalid("Abc!Abc!Ab"))
        assertEquals(SYMBOL_REASON, invalid("Abc1Abc1Ab"))
        // Several missing: upper, then lower, then digit, then symbol.
        assertEquals(UPPER_REASON, invalid("abcdefghij"))
        assertEquals(LOWER_REASON, invalid("ABCDEFGHIJ"))
        assertEquals(DIGIT_REASON, invalid("Abcdefghij"))
        assertEquals(UPPER_REASON, invalid("1234567890"))
        assertEquals(UPPER_REASON, invalid("!!!!!!!!!!"))
        // Too short is reported before any class.
        assertEquals(SHORT_REASON, invalid("abc"))
    }

    /** Every combination of present classes: the one reason names the first missing class, in the firmware's order. */
    @Test
    fun onlyTheFirstMissingClassIsReported() {
        val classes = listOf("A" to UPPER_REASON, "b" to LOWER_REASON, "1" to DIGIT_REASON, "!" to SYMBOL_REASON)
        for (mask in 0 until 16) {
            // é (C3 A9) pads to the minimum without satisfying any class.
            val text = classes.filterIndexed { k, _ -> mask and (1 shl k) != 0 }.joinToString("") { it.first } +
                "\u00e9".repeat(5)
            val missing = classes.indices.firstOrNull { mask and (1 shl it) == 0 }
            if (missing == null) {
                assertEquals("41623121" + "c3a9".repeat(5), okHex(text))
            } else {
                assertEquals("mask $mask", classes[missing].second, invalid(text))
            }
        }
    }

    @Test
    fun everyAsciiPunctuationRangeEdgeCountsAsASymbol() {
        for (code in listOf(0x21, 0x2f, 0x3a, 0x40, 0x5b, 0x60, 0x7b, 0x7e)) {
            val text = "Abcdefgh1" + code.toChar()
            assertEquals("0x%02x".format(code), toHex(text.toByteArray(Charsets.US_ASCII)), okHex(text))
        }
    }

    @Test
    fun whitespaceControlsAndNonAsciiSatisfyNoClass() {
        assertEquals(SYMBOL_REASON, invalid("Abcd efgh1"))
        assertEquals(SYMBOL_REASON, invalid("Abcd\tefgh1"))
        assertEquals(SYMBOL_REASON, invalid("Abcdefgh1é"))
        assertEquals(SYMBOL_REASON, invalid("Abcdefgh1\u00a0"))
        assertEquals(UPPER_REASON, invalid("Éynthetic#42"))
        assertEquals(UPPER_REASON, invalid("Ｓynthetic#42")) // FULLWIDTH S
        assertEquals(LOWER_REASON, invalid("SYNTHETIC#42ß")) // LATIN SMALL SHARP S
        assertEquals(DIGIT_REASON, invalid("Synthetic#x１")) // FULLWIDTH DIGIT ONE
        assertEquals(SYMBOL_REASON, invalid("Synthetic1x！")) // FULLWIDTH !
        assertEquals(SYMBOL_REASON, invalid("Abcdefgh1\u0001"))
        assertEquals(SYMBOL_REASON, invalid("Abcdefgh1\u001f"))
        assertEquals(SYMBOL_REASON, invalid("Abcdefgh1\u007f")) // DEL, just past '~'
    }

    // ---- Refused characters -----------------------------------------------------------------------

    @Test
    fun nulCrAndLfAreRefusedAtStartMiddleAndEnd() {
        for (c in listOf('\u0000', '\r', '\n')) {
            for (text in listOf("$c$BASE", "Synth${c}etic#42", "$BASE$c")) {
                assertEquals("U+%04X".format(c.code), LINE_REASON, invalid(text))
            }
        }
        assertEquals(LINE_REASON, invalid("\u0000"))
        assertEquals(LINE_REASON, invalid(" \n "))
    }

    @Test
    fun loneSurrogatesAreRefused() {
        for (s in listOf('\uD83D', '\uDE00')) {
            for (text in listOf("$s$BASE", "Synth${s}etic#42", "$BASE$s")) {
                assertEquals(SURROGATE_REASON, invalid(text))
            }
        }
        assertEquals(SURROGATE_REASON, invalid("$BASE\uDE00\uD83D")) // reversed pair
        assertEquals(SURROGATE_REASON, invalid("\uD83D"))
    }

    @Test
    fun fourByteEmojiIsEncodedAndKeptAtTheEdges() {
        assertEquals(BASE_HEX + "f09f9880", okHex("$BASE😀"))
        assertEquals("f09f9880" + BASE_HEX + "f09f9880", okHex(" 😀$BASE😀\t"))
    }

    // ---- Empty and the firmware's "clear" keyword ----------------------------------------------------

    @Test
    fun emptyAfterTrimAsksForAPassphrase() {
        for (text in listOf("", " ", "\t\u000b\u000c ", "          ")) {
            assertEquals("'$text'", "Enter a passphrase.", invalid(text))
        }
    }

    /** `clear` is the device's keyword to remove the secret; on Android no spelling of it can be a secret. */
    @Test
    fun clearInAnyCaseOrPaddingIsInvalid() {
        for (text in listOf("clear", "CLEAR", " Clear ", "\tcLeAr\u000b")) {
            assertEquals("'$text'", SHORT_REASON, invalid(text))
        }
    }

    @Test
    fun reasonsNeverContainTheInput() {
        val inputs = listOf(
            "ab1!ab1!ab", "AB1!AB1!AB", "Abc!Abc!Ab", "Abc1Abc1Ab", "Ab1!é", "x".repeat(129),
            "Synth\netic#42", "Synth\uD83Detic#42", "secret", "Ab1!xxxx",
        )
        for (text in inputs) {
            val reason = invalid(text)
            assertFalse(text, reason.contains(text.trim()))
            for (word in text.split(Regex("[^A-Za-z0-9]+")).filter { it.length >= 4 }) {
                assertFalse("$word in '$reason'", reason.contains(word))
            }
        }
    }

    @Test
    fun okBytesAreAFreshCopyEachTime() {
        val a = ok(BASE)
        val b = ok(BASE)
        assertArrayEquals(a, b)
        a.fill(0)
        assertEquals(BASE_HEX, toHex(b))
        assertEquals(BASE_HEX, okHex(BASE))
    }

    // ---- PSK derivation ----------------------------------------------------------------------------

    @Test
    fun constantsMatchTheFirmware() {
        assertEquals(10, PipeSecret.MIN_BYTES)
        assertEquals(128, PipeSecret.MAX_BYTES)
        assertEquals(100_000, PipeSecret.ITERATIONS)
        // BLE_PhoneSecureChannel.h kPipeLabel, passed with strlen: 11 bytes, no NUL.
        assertEquals("4857312d504950452d7631", toHex(PipeSecret.SALT.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun fixturePassphraseNormalisesToItselfAndDerivesTheFixturePsk() {
        assertEquals(FIXTURE_HEX, okHex(FIXTURE_PASSPHRASE))
        val secret = ok(FIXTURE_PASSPHRASE)
        val psk = PipeSecret.derivePsk(secret)
        assertEquals(32, psk.size)
        assertEquals(FIXTURE_PSK, toHex(psk))
        assertEquals("the secret is not modified", FIXTURE_HEX, toHex(secret))
        // Raw UTF-8 bytes (not via normalize) give the same key.
        assertEquals(FIXTURE_PSK, toHex(PipeSecret.derivePsk(FIXTURE_PASSPHRASE.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun outerTrimDerivesTheSameKeyAndInteriorBytesDoNot() {
        assertEquals(FIXTURE_PSK, toHex(PipeSecret.derivePsk(ok(" \t$FIXTURE_PASSPHRASE\u000c "))))
        val interior = toHex(PipeSecret.derivePsk(ok("SyntheticPipe! 123")))
        assertFalse(interior == FIXTURE_PSK)
        val nbsp = toHex(PipeSecret.derivePsk(ok("$FIXTURE_PASSPHRASE\u00a0")))
        assertFalse(nbsp == FIXTURE_PSK)
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private fun ok(text: String): ByteArray = when (val r = PipeSecret.normalize(text)) {
        is PipeSecret.Result.Ok -> r.bytes
        is PipeSecret.Result.Invalid -> {
            fail("expected Ok for ${text.length} chars, got: ${r.reason}")
            ByteArray(0)
        }
    }

    private fun okHex(text: String): String = toHex(ok(text))

    private fun invalid(text: String): String = when (val r = PipeSecret.normalize(text)) {
        is PipeSecret.Result.Invalid -> r.reason.also { assertTrue(it.isNotBlank()) }
        is PipeSecret.Result.Ok -> {
            fail("expected Invalid, got ${r.bytes.size} bytes: ${toHex(r.bytes)}")
            ""
        }
    }

    private fun assertTooLong(text: String) = assertEquals(LONG_REASON, invalid(text))

    /** A passphrase of exactly [n] ASCII bytes that satisfies every class ("Ab1!" + 'x' padding). */
    private fun ascii(n: Int): String = "Ab1!" + "x".repeat(n - 4)

    private companion object {
        const val BASE = "Synthetic#42"
        const val BASE_HEX = "53796e746865746963233432"
        const val LINE_REASON = "The passphrase cannot contain line breaks or NUL characters (the device's console cannot enter them)."

        // Android's own wording, pinned whole so that each refusal names exactly one rule.
        const val SURROGATE_REASON = "The passphrase contains an invalid character (a broken emoji or symbol). Retype it."
        const val SHORT_REASON = "The passphrase must be at least 10 bytes."
        const val LONG_REASON = "The passphrase must be at most 128 bytes (accented letters and emoji take 2 to 4 bytes each)."
        const val UPPER_REASON = "The passphrase must include an uppercase letter (A–Z)."
        const val LOWER_REASON = "The passphrase must include a lowercase letter (a–z)."
        const val DIGIT_REASON = "The passphrase must include a digit (0–9)."
        const val SYMBOL_REASON = "The passphrase must include an ASCII symbol such as ! # - or @."

        /** The bytes Arduino `String::trim` removes that the console can type (CR and LF are refused). */
        val TRIMMED = listOf('\t', '\u000b', '\u000c', ' ')

        const val FIXTURE_PASSPHRASE = "SyntheticPipe!123"
        const val FIXTURE_HEX = "53796e7468657469635069706521313233"
        const val FIXTURE_PSK = "7cbd65005cf381ea90f6faa75bafef1b9fbf0ac203106c2dfab332b26962298d"
    }
}
