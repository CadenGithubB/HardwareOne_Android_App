package com.hardwareone.console.pipe

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * The dedicated pipe passphrase (HW1-PIPE-v1, decision Q6 / A-7 / A-9): entry rules and the PSK
 * derivation. Both sides must derive from the same bytes, so [normalize] accepts only what the P4's
 * serial command `phonepipesecret` would store unchanged (Bluetooth.cpp `cmd_phonepipesecret`,
 * `blePassphrasePolicyError`), and refuses rather than reinterprets anything it would read
 * differently. Nothing here logs or echoes the secret; refusal reasons never contain input text.
 */
object PipeSecret {
    const val MIN_BYTES = 10
    const val MAX_BYTES = 128
    const val ITERATIONS = 100_000
    const val SALT = "HW1-PIPE-v1"

    sealed class Result {
        /** The exact bytes to store and derive from (trimmed UTF-8). The caller owns and wipes them. */
        class Ok(val bytes: ByteArray) : Result()

        /** Why the entry was refused: a short user-facing sentence without the input. */
        class Invalid(val reason: String) : Result()
    }

    /**
     * Applies the firmware command's checks in its order: no NUL/CR/LF (the serial console cannot
     * enter them), strict UTF-8, trim of outer ASCII whitespace bytes (Arduino `String::trim`),
     * empty, at most [MAX_BYTES], then the strength policy (at least [MIN_BYTES]; ASCII upper,
     * lower, digit, punctuation). Lengths are UTF-8 bytes, as the firmware counts them. "clear" is
     * the firmware's own keyword, but no spelling of it can pass the length rule.
     */
    fun normalize(input: CharSequence): Result {
        for (i in 0 until input.length) {
            val c = input[i]
            if (c == '\u0000' || c == '\r' || c == '\n') {
                return Result.Invalid("The passphrase cannot contain line breaks or NUL characters (the device's console cannot enter them).")
            }
        }
        // Sized for the worst case (3 bytes per UTF-16 unit) so the encoder never reallocates and
        // leaves an unwiped copy behind.
        val out = ByteBuffer.allocate(input.length * 3)
        try {
            val encoder = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            // Anything but UNDERFLOW (complete) is a refusal: an unpaired surrogate is malformed input.
            if (!encoder.encode(CharBuffer.wrap(input), out, true).isUnderflow || !encoder.flush(out).isUnderflow) {
                return Result.Invalid("The passphrase contains an invalid character (a broken emoji or symbol). Retype it.")
            }
            val encoded = out.array()
            var start = 0
            var end = out.position()
            while (start < end && trimmed(encoded[start])) start++
            while (end > start && trimmed(encoded[end - 1])) end--
            val length = end - start
            if (length == 0) return Result.Invalid("Enter a passphrase.")
            if (length > MAX_BYTES) {
                return Result.Invalid("The passphrase must be at most $MAX_BYTES bytes (accented letters and emoji take 2 to 4 bytes each).")
            }
            policyError(encoded, start, end)?.let { return Result.Invalid(it) }
            return Result.Ok(encoded.copyOfRange(start, end))
        } finally {
            out.array().fill(0)
        }
    }

    /**
     * PBKDF2-HMAC-SHA256(secret, ASCII "HW1-PIPE-v1" (11 bytes, no NUL), 100000 iterations) -> 32 bytes.
     * [secret] is used exactly as given (pass [Result.Ok.bytes]). Slow by design: call it off the
     * main thread and Bluetooth callbacks, before advertising (R5).
     */
    fun derivePsk(secret: ByteArray): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(secret, SALT.toByteArray(Charsets.US_ASCII), ITERATIONS)
        return (generator.generateDerivedMacParameters(256) as KeyParameter).key
    }

    /** `blePassphrasePolicyError`: length first, then each class in the firmware's order. */
    private fun policyError(b: ByteArray, start: Int, end: Int): String? {
        if (end - start < MIN_BYTES) return "The passphrase must be at least $MIN_BYTES bytes."
        var upper = false
        var lower = false
        var digit = false
        var symbol = false
        // C-locale ctype: bytes 0x80 and above count toward the length but belong to no class.
        for (i in start until end) {
            when (b[i].toInt() and 0xff) {
                in 0x41..0x5a -> upper = true
                in 0x61..0x7a -> lower = true
                in 0x30..0x39 -> digit = true
                in 0x21..0x2f, in 0x3a..0x40, in 0x5b..0x60, in 0x7b..0x7e -> symbol = true
            }
        }
        return when {
            !upper -> "The passphrase must include an uppercase letter (A–Z)."
            !lower -> "The passphrase must include a lowercase letter (a–z)."
            !digit -> "The passphrase must include a digit (0–9)."
            !symbol -> "The passphrase must include an ASCII symbol such as ! # - or @."
            else -> null
        }
    }

    /** `isspace` in the C locale: tab, LF, VT, FF, CR and space. */
    private fun trimmed(b: Byte): Boolean = b.toInt() == 0x20 || b.toInt() in 0x09..0x0d
}
