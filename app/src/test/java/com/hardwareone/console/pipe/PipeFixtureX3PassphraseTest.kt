package com.hardwareone.console.pipe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * HW1-PIPE-v1 fixture X3 (hardwareone-idf `tools/ble_secure/fixtures/phone_pipe_v1_passphrase.json`,
 * copied verbatim to `src/test/resources/pipe/`, SHA-256 checked below): 32 texts, each entered in the
 * Android app and typed into the P4 as `phonepipesecret <text>` over serial.
 *
 * Every vector runs end to end:
 *  1. `text_hex` becomes the text a user would enter, through a strict UTF-8 decoder. Bytes that are
 *     not UTF-8 are the encoding of no String (the only entry path is a text field), so for such a
 *     vector the test proves the strict decoder refuses it, names the malformed byte, and shows the
 *     standard lenient decode (U+FFFD) encodes to different bytes.
 *  2. [PipeSecret.normalize] on that text: accepted exactly when `android_accepts`, and then with an
 *     empty `android_reason`. Accepted bytes equal `android_secret_hex` and, for `same_secret`,
 *     `p4_stored_hex`; [PipeSecret.derivePsk] gives `psk_hex`. A refusal must give Android's sentence
 *     for the rule `android_reason` names (see [androidWording] for the one place the wording is finer
 *     than the fixture's).
 *  3. The P4 side of the path as the fixture's `path` states it (serial reader drops CR and ends at LF,
 *     executor ends at NUL, command split and trims): the transport alters a text exactly when it holds
 *     NUL, CR or LF, and every such text is one Android refuses, which is why that refusal is
 *     load-bearing (firmware-003 §3). `p4_result` and `p4_message` are, whole, what `phonepipesecret`
 *     gives for the modelled argument ([p4Command]), so a rejection names the first rule that argument
 *     breaks (for `interior_nul`, the 9 bytes before the NUL). An unaltered text the P4 rejects names
 *     the same rule as `android_reason`. A `set` stores the modelled argument, and `psk_hex` is
 *     Android's PBKDF2 of `p4_stored_hex` even for stored bytes Android refuses to produce.
 *
 * Fixture-wide cases pin the `path`, `assumptions` and `parity` text and probe the rules they state
 * against [PipeSecret]: NUL/CR/LF refusal, the exact outer trim set, the C-locale ASCII classes (with
 * missing-lowercase, which no X3 vector covers) and that no byte Android can emit at 0x80 or above
 * satisfies a class (assumptions: newlib C locale, unsigned char).
 *
 * Expected values come from the fixture or from rules written out here (C-locale ctype, `isspace`
 * trim, the serial transport), never from [PipeSecret]'s own helpers. Android's refusal sentences are
 * pinned whole, as in PipeSecretTest. Synthetic passphrases only.
 */
@RunWith(Parameterized::class)
class PipeFixtureX3PassphraseTest(
    @Suppress("unused") private val case: String,
    private val check: () -> Unit,
) {
    /** Runs one case: a fixture-wide claim, or one vector through Android's entry rule and the P4 path. */
    @Test
    fun holds() = check()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val fileCases = listOf<Pair<String, () -> Unit>>(
                "file: verbatim firmware copy" to ::fileIsTheFirmwareCopy,
                "file: the 32 documented vectors" to ::fileListsTheDocumentedVectors,
                "file: path, assumptions and parity text" to ::fileStatesThePathAndAssumptions,
                "path: NUL, CR and LF refused anywhere" to ::nulCrAndLfAreRefusedAnywhere,
                "path: outer trim is HT, VT, FF and space only" to ::outerTrimIsExactlyTheConsoleTypeableIsspaceBytes,
                "path: ASCII classes are the C-locale sets" to ::asciiClassesAreTheCLocaleSets,
                "assumptions: no byte at 0x80 or above classifies" to ::noProducibleHighByteSatisfiesAClass,
            )
            return fileCases.map { arrayOf<Any>(it.first, it.second) } +
                x3Vectors.map { v -> arrayOf<Any>("vector ${v.id}", { checkVector(v) }) }
        }
    }
}

// ---- Fixture -------------------------------------------------------------------------------------

private const val RESOURCE = "/pipe/phone_pipe_v1_passphrase.json"

/** firmware-003 §3 / firmware-004 §5 give `82608da6…0439`; this is the firmware file's full digest. */
private const val FIRMWARE_SHA256 = "82608da63c24130a9afcefd196844ba9d33b2f9cc6a83fd50b6591cee2e40439"

private val x3Bytes: ByteArray by lazy {
    val stream = PipeSecret::class.java.getResourceAsStream(RESOURCE)
        ?: throw AssertionError("missing test resource $RESOURCE")
    stream.use { it.readBytes() }
}

private val x3: JSONObject by lazy { JSONObject(String(x3Bytes, Charsets.UTF_8)) }

private val x3Vectors: List<X3Vector> by lazy {
    val array = x3.getJSONArray("vectors")
    List(array.length()) { X3Vector(array.getJSONObject(it)) }
}

/** One X3 vector; every field is read strictly (a missing or mistyped field throws). */
private class X3Vector(val json: JSONObject) {
    val id: String = json.getString("id")
    val textHex: String = json.getString("text_hex")
    val textBytes: Int = json.getInt("text_bytes")
    val validUtf8: Boolean = json.getBoolean("valid_utf8")
    val androidAccepts: Boolean = json.getBoolean("android_accepts")
    val androidReason: String = json.getString("android_reason")
    val androidSecretHex: String = json.getString("android_secret_hex")
    val serialLineHex: String = json.getString("serial_line_hex")
    val p4Result: String = json.getString("p4_result")
    val p4Message: String = json.getString("p4_message")
    val p4StoredHex: String = json.getString("p4_stored_hex")
    val p4StoredBytes: Int = json.getInt("p4_stored_bytes")
    val pskHex: String = json.getString("psk_hex")
    val parity: String = json.getString("parity")

    val text: ByteArray get() = hex(textHex)

    fun say(what: String) = "$id: $what"
}

private fun x3Strings(key: String): List<String> = x3.getJSONArray(key).let { a: JSONArray -> List(a.length()) { a.getString(it) } }

/** The fixture's own base passphrase (`plain`), decoded as text. */
private val plainText: String by lazy {
    (strictUtf8(x3Vectors.single { it.id == "plain" }.text) as Decoded.Text).text
}

// ---- Fixture-wide cases --------------------------------------------------------------------------

/** The resource is the firmware's X3 byte for byte, so every vector below is the delivered one. */
private fun fileIsTheFirmwareCopy() {
    val digest = MessageDigest.getInstance("SHA-256").digest(x3Bytes)
    assertEquals(FIRMWARE_SHA256, toHex(digest))
}

/**
 * The file has the documented shape: the six top-level keys, the 32 ids in order, exactly the 15
 * per-vector fields, and the 17 accepted / 15 refused split firmware-003 §3 reports.
 */
private fun fileListsTheDocumentedVectors() {
    assertEquals(setOf("description", "generated_by", "path", "assumptions", "parity", "vectors"), x3.keySet())
    assertEquals(
        listOf(
            "plain", "outer_spaces", "outer_tabs", "outer_vt_ff", "interior_space", "interior_tab",
            "outer_nbsp", "outer_nel", "outer_ideographic_space", "multibyte_letters", "emoji",
            "nfc_e_acute", "nfd_e_acute", "non_ascii_uppercase_only", "unicode_punctuation_only",
            "min_10_bytes", "short_9_bytes", "trimmed_to_10", "max_128_ascii", "over_129_ascii",
            "max_128_multibyte", "over_129_multibyte", "missing_digit", "missing_punctuation",
            "interior_cr", "trailing_cr", "interior_nul", "clear_word", "clear_upper", "empty",
            "only_spaces", "invalid_utf8_byte",
        ),
        x3Vectors.map { it.id },
    )
    val fields = setOf(
        "id", "description", "text_hex", "text_bytes", "valid_utf8", "android_accepts", "android_reason",
        "android_secret_hex", "serial_line_hex", "p4_result", "p4_message", "p4_stored_hex",
        "p4_stored_bytes", "psk_hex", "parity",
    )
    for (v in x3Vectors) assertEquals(v.say("fields"), fields, v.json.keySet())
    assertEquals("vectors Android accepts", 17, x3Vectors.count { it.androidAccepts })
    assertEquals("vectors Android refuses", 15, x3Vectors.count { !it.androidAccepts })
    assertEquals("vectors that are not UTF-8", 1, x3Vectors.count { !it.validUtf8 })
}

/**
 * The claims the probes below check, pinned as delivered: `path[0]` is Android's rule as agreed
 * (DECISIONS Q6/A-7/A-9), `path[1..5]` the P4 steps modelled in [checkP4Path], and the assumptions.
 */
private fun fileStatesThePathAndAssumptions() {
    assertEquals(
        listOf(
            "Android: entry rule from DECISIONS Q6/A-7/A-9 (reject NUL, CR, LF and invalid UTF-8; trim outer ASCII 09-0D and 20; 10-128 bytes; ASCII uppercase, lowercase, digit and punctuation required)",
            "P4 serial reader (HardwareOne.cpp loop drain): every CR is dropped, LF ends the line",
            "P4 executor (System_Utils.cpp): the line becomes a C string, so it ends at the first NUL",
            "P4 command split (System_CommandLookupCore.h) and String::trim on the command and its argument",
            "cmd_phonepipesecret: trim; \"clear\" in any case clears; empty reports status; over 128 bytes rejected; strength policy via C-locale ctype on unsigned bytes; no UTF-8 validation",
            "PSK = PBKDF2-HMAC-SHA256(stored bytes, \"HW1-PIPE-v1\", 100000, 32)",
        ),
        x3Strings("path"),
    )
    assertEquals(
        listOf(
            "Target: RISC-V, plain char unsigned (generator built with -funsigned-char)",
            "Target newlib C locale classifies only ASCII; the generator asserts the host C locale matches for all 256 byte values",
            "The terminal sends CR LF after the line",
        ),
        x3Strings("assumptions"),
    )
    assertEquals(
        "same_secret = Android accepts and the P4 stores identical bytes (checked by the generator); android_rejects = outside the supported entry path, P4 behaviour recorded for reference",
        x3.getString("parity"),
    )
    // path[5]: the salt and iteration count Android derives with.
    assertEquals("HW1-PIPE-v1", PipeSecret.SALT)
    assertEquals(100_000, PipeSecret.ITERATIONS)
    assertEquals(128, PipeSecret.MAX_BYTES)
    assertEquals(10, PipeSecret.MIN_BYTES)
}

/**
 * `path[0]` "reject NUL, CR, LF", against what `path[1]`/`path[2]` do to them on the P4 (drop CR, end
 * the line at LF or NUL): each is refused at the start, inside and at the end of the fixture's base
 * passphrase, and also where the firmware's trim would otherwise have removed CR or LF.
 */
private fun nulCrAndLfAreRefusedAnywhere() {
    for (c in listOf('\u0000', '\r', '\n')) {
        val texts = listOf("$c$plainText", plainText.take(9) + c + plainText.drop(9), "$plainText$c", " $c\t", "$c")
        for (t in texts) assertEquals("U+%04X".format(c.code), REFUSED + LINE_REASON, outcome(t))
    }
    assertEquals(REFUSED + LINE_REASON, outcome("$plainText\r\n"))
}

/**
 * `path[0]` "trim outer ASCII 09-0D and 20" over every ASCII code at both ends of the base passphrase:
 * HT, VT, FF and space are trimmed; LF and CR are in the `isspace` set but are refused first (as the
 * agreed rule orders it); every other byte, controls and DEL included, is kept and counted, which is
 * what the P4's `String::trim` (`path[3]`, `path[4]`) also keeps.
 */
private fun outerTrimIsExactlyTheConsoleTypeableIsspaceBytes() {
    val plainHex = toHex(plainText.toByteArray(Charsets.UTF_8))
    for (code in 0x00..0x7f) {
        val c = code.toChar()
        val b = "%02x".format(code)
        val (front, back) = when (code) {
            0x00, 0x0a, 0x0d -> (REFUSED + LINE_REASON) to (REFUSED + LINE_REASON)
            0x09, 0x0b, 0x0c, 0x20 -> (OK + plainHex) to (OK + plainHex)
            else -> (OK + b + plainHex) to (OK + plainHex + b)
        }
        assertEquals("front 0x$b", front, outcome("$c$plainText"))
        assertEquals("back 0x$b", back, outcome("$plainText$c"))
    }
}

/**
 * `path[0]` "ASCII uppercase, lowercase, digit and punctuation required" and `path[4]` "C-locale ctype":
 * for each class, a base with the other three gets every ASCII code inserted inside it; it is
 * accepted exactly when that code is in the C-locale class. The lowercase base is the only
 * missing-lowercase case, as X3 has no such vector.
 */
private fun asciiClassesAreTheCLocaleSets() {
    for (probe in CLASS_PROBES) {
        assertEquals(probe.base, REFUSED + probe.reason, outcome(probe.base))
        for (code in 0x01..0x7f) {
            if (code == 0x0a || code == 0x0d) continue
            val text = probe.insert(code.toChar().toString())
            val expected = if (probe.member(code)) OK + toHex(text.toByteArray(Charsets.UTF_8)) else REFUSED + probe.reason
            assertEquals("0x%02x in ${probe.base}".format(code), expected, outcome(text))
        }
    }
}

/**
 * Assumptions 1 and 2 (unsigned bytes, a C locale that classifies only ASCII), from Android's side:
 * every BMP scalar from U+0080 and a stride through the supplementary planes, inside each class
 * probe, satisfies no class. Together they put every byte value Android's UTF-8 encoder can emit at
 * 0x80 or above (80–BF, C2–F4) through the classifier.
 */
private fun noProducibleHighByteSatisfiesAClass() {
    val seen = BooleanArray(256)
    val scalars = (0x80..0xffff).filter { it !in 0xd800..0xdfff } + (0x10000..0x10ffff step 0x101) + 0x10ffff
    for (cp in scalars) {
        val s = String(Character.toChars(cp))
        for (b in s.toByteArray(Charsets.UTF_8)) seen[b.toInt() and 0xff] = true
        for (probe in CLASS_PROBES) {
            val r = PipeSecret.normalize(probe.insert(s))
            if (r !is PipeSecret.Result.Invalid || r.reason != probe.reason) {
                fail("U+%04X in ${probe.base}: expected '${probe.reason}', got ${describe(r)}".format(cp))
            }
        }
    }
    assertEquals((0x80..0xbf) + (0xc2..0xf4), (0x80..0xff).filter { seen[it] })
}

// ---- One vector ----------------------------------------------------------------------------------

/** One X3 vector end to end: fixture bookkeeping, text formation, Android's rule, then the P4 path. */
private fun checkVector(v: X3Vector) {
    val text = v.text
    assertEquals(v.say("text_bytes"), v.textBytes, text.size)
    assertEquals(v.say("p4_stored_bytes"), v.p4StoredBytes, hex(v.p4StoredHex).size)
    assertEquals(v.say("parity follows android_accepts"), if (v.androidAccepts) SAME_SECRET else ANDROID_REJECTS, v.parity)

    when (val d = strictUtf8(text)) {
        is Decoded.Malformed -> checkNotText(v, d)
        is Decoded.Text -> {
            assertTrue(v.say("valid_utf8 (the strict decoder read it as text)"), v.validUtf8)
            // The decoded String encodes back to exactly text_hex: it is the text the user enters.
            assertEquals(v.say("text round trip"), v.textHex, toHex(d.text.toByteArray(Charsets.UTF_8)))
            checkAndroidEntry(v, d.text)
        }
    }
    checkP4Path(v)
}

/**
 * A vector that is not UTF-8. Android's only entry path is a text field (a String), and a String's
 * UTF-8 is always well formed, so these bytes cannot be entered: the strict decoder stops at the first
 * malformed byte, and a lenient decode (U+FFFD) encodes to different bytes. Android "refuses" it by
 * never forming it; [PipeSecret.normalize] is not reached.
 */
private fun checkNotText(v: X3Vector, d: Decoded.Malformed) {
    assertFalse(v.say("valid_utf8 (the strict decoder refused it)"), v.validUtf8)
    assertFalse(v.say("android_accepts"), v.androidAccepts)
    assertEquals(v.say("android_reason"), NOT_UTF8, v.androidReason)
    assertEquals(v.say("android_secret_hex"), "", v.androidSecretHex)
    assertEquals(v.say("first malformed byte (offset, length)"), MALFORMED_AT[v.id], d.offset to d.length)
    val nearest = String(v.text, Charsets.UTF_8)
    assertNotEquals(v.say("a lenient decode reproduces the bytes"), v.textHex, toHex(nearest.toByteArray(Charsets.UTF_8)))
}

/** [PipeSecret.normalize] on the entered text against `android_accepts`, the secret, the P4 bytes and the PSK. */
private fun checkAndroidEntry(v: X3Vector, entered: String) {
    when (val r = PipeSecret.normalize(entered)) {
        is PipeSecret.Result.Ok -> {
            val got = toHex(r.bytes)
            assertTrue(v.say("android_accepts is false, but Android accepted $got"), v.androidAccepts)
            assertEquals(v.say("android_reason for an accepted text"), "", v.androidReason)
            assertEquals(v.say("android_secret_hex"), v.androidSecretHex, got)
            assertEquals(v.say("p4_result for same_secret"), "set", v.p4Result)
            assertEquals(v.say("p4_stored_hex (same_secret)"), v.p4StoredHex, got)
            assertEquals(v.say("psk_hex"), v.pskHex, toHex(PipeSecret.derivePsk(r.bytes)))
        }
        is PipeSecret.Result.Invalid -> {
            assertFalse(v.say("android_accepts is true, but Android refused: ${r.reason}"), v.androidAccepts)
            assertEquals(v.say("android_secret_hex"), "", v.androidSecretHex)
            assertEquals(v.say("Android's reason for '${v.androidReason}'"), androidWording(v), r.reason)
        }
    }
}

/**
 * The fixture's P4 path for this vector: `serial_line_hex` is the command, the text and CR LF
 * (assumption 3); the transport (`path[1]`, `path[2]`) alters the text exactly when it holds NUL, CR or
 * LF, and then Android refuses it for that reason; the command split and trims (`path[3]`, `path[4]`)
 * yield the argument. `p4_result` and `p4_message` are, whole, what [p4Command] gives for that argument,
 * so a rejection states the first rule the argument itself breaks. When the transport left the text
 * as typed, a rejection names the same rule as `android_reason`. A `set` stores the argument, and
 * `psk_hex` is the PBKDF2 of the stored bytes (`path[5]`); nothing else stores or derives.
 */
private fun checkP4Path(v: X3Vector) {
    val text = v.text
    assertEquals(v.say("serial_line_hex"), toHex(COMMAND) + "20" + v.textHex + "0d0a", v.serialLineHex)

    val line = serialTransport(hex(v.serialLineHex))
    val intended = COMMAND + byteArrayOf(0x20) + text
    val altered = !line.contentEquals(intended)
    val holdsLineBytes = text.any { it.toInt() == 0x00 || it.toInt() == 0x0d || it.toInt() == 0x0a }
    assertEquals(v.say("the P4 transport alters the text"), holdsLineBytes, altered)
    if (altered) {
        assertFalse(v.say("android_accepts for a text the P4 transport alters"), v.androidAccepts)
        assertEquals(v.say("android_reason for a text the P4 transport alters"), LINE_BYTES, v.androidReason)
    }

    val command = cTrim(line)
    assertEquals(v.say("command name"), toHex(COMMAND), toHex(command.copyOfRange(0, COMMAND.size)))
    val argument = cTrim(command.copyOfRange(COMMAND.size, command.size))
    assertEquals(
        v.say("p4_result and p4_message for the modelled argument ${toHex(argument)}"),
        p4Command(argument),
        v.p4Result to v.p4Message,
    )
    if (v.p4Result == "rejected" && !altered) {
        val rule = P4_RULE_FOR_ANDROID_REASON[v.androidReason]
            ?: throw AssertionError(v.say("P4 rejected '${v.p4Message}', but android_reason '${v.androidReason}' is no shared rule"))
        assertEquals(v.say("P4 rule for android_reason '${v.androidReason}'"), P4_REJECTED_PREFIX + rule, v.p4Message)
    }
    if (v.p4Result == "set") {
        assertEquals(v.say("p4_stored_hex is the trimmed argument"), toHex(argument), v.p4StoredHex)
        // For accepted vectors checkAndroidEntry already derived psk_hex from identical bytes.
        if (!v.androidAccepts) {
            assertEquals(v.say("psk_hex of the P4's bytes"), v.pskHex, toHex(PipeSecret.derivePsk(hex(v.p4StoredHex))))
        }
    } else {
        assertEquals(v.say("p4_stored_hex for '${v.p4Result}'"), "", v.p4StoredHex)
        assertEquals(v.say("psk_hex for '${v.p4Result}'"), "", v.pskHex)
    }
}

/**
 * Android's sentence for the rule a fixture `android_reason` names. The fixture's "fewer than 10
 * bytes after trimming" covers nothing left at all; Android words that case "Enter a passphrase."
 * (the same refusal for the same rule, worded for an empty field), so the empty-after-trim vectors
 * expect that sentence. The trimmed length here uses `isspace` (09–0D, 20), not [PipeSecret].
 */
private fun androidWording(v: X3Vector): String = when (v.androidReason) {
    LINE_BYTES -> LINE_REASON
    "fewer than 10 bytes after trimming" -> if (cTrim(v.text).isEmpty()) EMPTY_REASON else SHORT_REASON
    "more than 128 bytes after trimming" -> LONG_REASON
    "no ASCII uppercase letter" -> UPPER_REASON
    "no ASCII lowercase letter" -> LOWER_REASON
    "no ASCII digit" -> DIGIT_REASON
    "no ASCII punctuation" -> SYMBOL_REASON
    else -> throw AssertionError(v.say("android_reason '${v.androidReason}' has no Android counterpart"))
}

// ---- Rules written out independently of PipeSecret -----------------------------------------------

private sealed class Decoded {
    class Text(val text: String) : Decoded()

    /** Byte offset and length of the first malformed sequence. */
    class Malformed(val offset: Int, val length: Int) : Decoded()
}

/** UTF-8 to text with REPORT on malformed input: what any byte-to-text step on Android would need. */
private fun strictUtf8(bytes: ByteArray): Decoded {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(bytes)
    val out = CharBuffer.allocate(bytes.size + 1)
    val result = decoder.decode(input, out, true)
    if (result.isError) return Decoded.Malformed(input.position(), result.length())
    check(result.isUnderflow && decoder.flush(out).isUnderflow)
    out.flip()
    return Decoded.Text(out.toString())
}

/** `path[1]` and `path[2]`: every CR is dropped, LF ends the line, the C string ends at the first NUL. */
private fun serialTransport(typed: ByteArray): ByteArray {
    val line = ArrayList<Byte>()
    for (b in typed) {
        when (b.toInt()) {
            0x0d -> continue
            0x0a -> break
            else -> line += b
        }
    }
    val nul = line.indexOf(0.toByte())
    return (if (nul >= 0) line.subList(0, nul) else line).toByteArray()
}

/** C-locale `isspace` (09–0D, 20) trimmed from both ends, as Arduino `String::trim`. */
private fun cTrim(b: ByteArray): ByteArray {
    fun space(x: Byte) = x.toInt() == 0x20 || x.toInt() in 0x09..0x0d
    var start = 0
    var end = b.size
    while (start < end && space(b[start])) start++
    while (end > start && space(b[end - 1])) end--
    return b.copyOfRange(start, end)
}

/** One missing-class base: it has the other three classes; [insert] places a probe inside it. */
private class ClassProbe(val base: String, val reason: String, val member: (Int) -> Boolean) {
    fun insert(s: String) = base.take(9) + s + base.drop(9)
}

private fun cUpper(c: Int) = c in 0x41..0x5a
private fun cLower(c: Int) = c in 0x61..0x7a
private fun cDigit(c: Int) = c in 0x30..0x39

/** C `ispunct`: printable, not space, not alphanumeric. */
private fun cPunct(c: Int) = c in 0x21..0x7e && !cUpper(c) && !cLower(c) && !cDigit(c)

/**
 * `path[4]` `cmd_phonepipesecret` on an already-trimmed argument, as (`p4_result`, `p4_message`). An
 * empty argument reports status (the fixture's message shows the generator's P4 already held a
 * secret). "clear" in any ASCII case clears. Anything else must pass [p4PolicyBreach]. Serial is
 * not a Bluetooth source, so the source check never fires on this path.
 */
private fun p4Command(argument: ByteArray): Pair<String, String> {
    val clear = "clear".toByteArray(Charsets.US_ASCII)
    fun asciiLower(b: Byte) = (b.toInt() and 0xff).let { if (cUpper(it)) it + 0x20 else it }
    return when {
        argument.isEmpty() -> "status" to P4_STATUS
        argument.size == clear.size && argument.indices.all { asciiLower(argument[it]) == clear[it].toInt() } ->
            "cleared" to P4_CLEARED
        else -> p4PolicyBreach(argument)?.let { "rejected" to P4_REJECTED_PREFIX + it } ?: ("set" to P4_SET)
    }
}

/**
 * The first rule the P4 finds broken, or null. The order follows firmware-003 §3 ("the 128-byte cap,
 * then the C-locale ASCII policy"), then the order of firmware `blePassphrasePolicyError`
 * (Bluetooth.cpp): fewer than 10 bytes, then no uppercase, lowercase, digit or symbol. Arduino
 * `String::length` counts bytes, which is what the P4's "characters" means. Bytes are classified
 * unsigned in the C locale (assumptions 1 and 2), so no byte at 0x80 or above counts toward a class.
 */
private fun p4PolicyBreach(argument: ByteArray): String? {
    val codes = argument.map { it.toInt() and 0xff }
    return when {
        codes.size > 128 -> "be at most 128 bytes"
        codes.size < 10 -> "be at least 10 characters"
        codes.none(::cUpper) -> "include an uppercase letter"
        codes.none(::cLower) -> "include a lowercase letter"
        codes.none(::cDigit) -> "include a digit"
        codes.none(::cPunct) -> "include a symbol"
        else -> null
    }
}

/** Bases derived from the fixture passphrase; the digit and punctuation ones are X3's own texts. */
private val CLASS_PROBES = listOf(
    ClassProbe("syntheticpipe!123", UPPER_REASON, ::cUpper),
    ClassProbe("SYNTHETICPIPE!123", LOWER_REASON, ::cLower),
    ClassProbe("SyntheticPipe!abc", DIGIT_REASON, ::cDigit),
    ClassProbe("SyntheticPipe0123", SYMBOL_REASON, ::cPunct),
)

private const val OK = "accepted "
private const val REFUSED = "refused: "

private fun outcome(text: String): String = describe(PipeSecret.normalize(text))

private fun describe(r: PipeSecret.Result): String = when (r) {
    is PipeSecret.Result.Ok -> OK + toHex(r.bytes)
    is PipeSecret.Result.Invalid -> REFUSED + r.reason
}

// ---- Constants -----------------------------------------------------------------------------------

private val COMMAND = "phonepipesecret".toByteArray(Charsets.US_ASCII)

private const val SAME_SECRET = "same_secret"
private const val ANDROID_REJECTS = "android_rejects"
private const val LINE_BYTES = "contains NUL, CR or LF"
private const val NOT_UTF8 = "not valid UTF-8 text"

/** The not-UTF-8 vectors and where their first malformed sequence starts. */
private val MALFORMED_AT = mapOf("invalid_utf8_byte" to (16 to 1))

private const val P4_SET = "Pipe secret set; enter the same passphrase on Android, start the pipe and enroll"
private const val P4_CLEARED = "Pipe secret cleared; pipe enrollment removed"
private const val P4_STATUS = "Phone pipe secret is SET (hidden)"
private const val P4_REJECTED_PREFIX = "Error: pipe passphrase must "

/** The P4's wording for each rule it shares with Android, keyed by the fixture's `android_reason`. */
private val P4_RULE_FOR_ANDROID_REASON = mapOf(
    "fewer than 10 bytes after trimming" to "be at least 10 characters",
    "more than 128 bytes after trimming" to "be at most 128 bytes",
    "no ASCII uppercase letter" to "include an uppercase letter",
    "no ASCII lowercase letter" to "include a lowercase letter",
    "no ASCII digit" to "include a digit",
    "no ASCII punctuation" to "include a symbol",
)

// Android's wording (PipeSecret), pinned whole as in PipeSecretTest.
private const val LINE_REASON = "The passphrase cannot contain line breaks or NUL characters (the device's console cannot enter them)."
private const val EMPTY_REASON = "Enter a passphrase."
private const val SHORT_REASON = "The passphrase must be at least 10 bytes."
private const val LONG_REASON = "The passphrase must be at most 128 bytes (accented letters and emoji take 2 to 4 bytes each)."
private const val UPPER_REASON = "The passphrase must include an uppercase letter (A–Z)."
private const val LOWER_REASON = "The passphrase must include a lowercase letter (a–z)."
private const val DIGIT_REASON = "The passphrase must include a digit (0–9)."
private const val SYMBOL_REASON = "The passphrase must include an ASCII symbol such as ! # - or @."
