package com.hardwareone.console.ble

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Wire contract for app -> device encrypted BLE OTA payload frames.
 *
 * The outer HardwareOne Secure Channel provides AEAD, replay protection, and
 * ordering. This inner header makes the plaintext unambiguously binary and
 * carries an explicit device-file offset so checkpoint replies can recover
 * cleanly from a dropped/deferred frame.
 */
object OtaBleWire {
    private val PREFIX = byteArrayOf(0x00, 'H'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte(),
        'O'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 0x01)
    const val HEADER_SIZE = 12

    fun encodeChunk(offset: Long, payload: ByteArray): ByteArray {
        require(offset in 0..0xffff_ffffL) { "OTA offset does not fit uint32" }
        require(payload.isNotEmpty()) { "OTA chunk is empty" }
        return ByteArray(HEADER_SIZE + payload.size).also { out ->
            PREFIX.copyInto(out)
            out[8] = (offset ushr 24).toByte()
            out[9] = (offset ushr 16).toByte()
            out[10] = (offset ushr 8).toByte()
            out[11] = offset.toByte()
            payload.copyInto(out, HEADER_SIZE)
        }
    }
}

/** `otawrite begin|status|finish|abort` checkpoint response. */
data class OtaWriteStatus(
    val success: Boolean,
    val active: Boolean,
    val member: String,
    val size: Long,
    val expected: Long,
    val final: Boolean,
    val chunkMax: Int,
    val error: String?,
) {
    companion object {
        fun parse(json: String): OtaWriteStatus? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (!o.has("success")) return null
            val success = o.optBoolean("success", false)
            return OtaWriteStatus(
                success = success,
                active = o.optBoolean("active", false),
                member = o.optString("member"),
                size = o.optLong("size"),
                expected = o.optLong("expected"),
                final = o.optBoolean("final", false),
                chunkMax = o.optInt("chunkMax"),
                error = if (success) null else o.optString("error").ifEmpty { "OTA upload failed" },
            )
        }
    }
}

/** The exact files emitted by `tools/ota/make_bundle.py`. */
data class OtaBundle(
    val firmware: ByteArray,
    val manifestJson: ByteArray,
    val board: String,
    val layout: String,
    val version: String,
    val minUpdaterVersion: String,
    val dataSchema: Long,
    val imageSha256: String,
    val publicKeyFingerprint: String,
) {
    val imageSize: Int get() = firmware.size

    companion object {
        const val FORMAT = "hardwareone-ota-bundle"
        const val FORMAT_VERSION = 1
        // Absolute parser guard only -- it bounds a ZIP member before identity
        // is known. It is NOT a "does this fit ota_0" check: 0x5A0000 is the
        // literal the firmware itself deleted on 2026-08-16 for being wrong in
        // both directions (too small for the 16 MB generic layout, too large
        // for the headless and 8 MB ones). The real per-layout capacity comes
        // from the device as otastatus.slotSize; see requireFitsDevice below.
        const val MAX_IMAGE_BYTES = 0x5A0000
        const val MAX_BUNDLE_BYTES = MAX_IMAGE_BYTES + 16 * 1024
        private const val MAX_MANIFEST_BYTES = 2048
        private const val MAX_METADATA_BYTES = 1024
        private const val MAX_PUBLIC_KEY_BYTES = 4096
        private const val PAYLOAD_BYTES = 224
        private const val SIGNATURE_BYTES = 384
        // RFC 8017 trailer field value. Kept literal for minSdk 26; Android only added the
        // equivalent PSSParameterSpec.TRAILER_FIELD_BC constant to its public stubs in API 35.
        private const val PSS_TRAILER_FIELD_BC = 1
        private const val PAYLOAD_MAGIC = 0x314D3148L
        private const val APP_DESCRIPTOR_OFFSET = 32
        private const val APP_DESCRIPTOR_BYTES = 256
        private const val APP_DESCRIPTOR_MAGIC = 0xABCD5432L
        private val MEMBERS = listOf(
            "bundle.json",
            "manifest.json",
            "public-key.pem",
            "firmware.bin",
        )

        fun parse(bytes: ByteArray): OtaBundle {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BUNDLE_BYTES) {
                "OTA bundle is empty or exceeds ${MAX_BUNDLE_BYTES / 1024} KiB."
            }
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "OTA bundle cannot contain directories." }
                    require(entry.name in MEMBERS) { "Unexpected OTA bundle member: ${entry.name}" }
                    require(entry.name !in entries) { "Duplicate OTA bundle member: ${entry.name}" }
                    require(entry.method == ZipEntry.STORED) {
                        "OTA bundle members must be stored without ZIP compression."
                    }
                    val limit = when (entry.name) {
                        "bundle.json" -> MAX_METADATA_BYTES
                        "manifest.json" -> MAX_MANIFEST_BYTES
                        "public-key.pem" -> MAX_PUBLIC_KEY_BYTES
                        else -> MAX_IMAGE_BYTES
                    }
                    entries[entry.name] = zip.readBounded(limit, entry.name)
                    zip.closeEntry()
                }
            }
            require(entries.keys.toList() == MEMBERS) {
                "OTA bundle must contain exactly: ${MEMBERS.joinToString()}."
            }

            val metadata = JSONObject(entries.getValue("bundle.json").toString(Charsets.UTF_8))
            require(metadata.optString("format") == FORMAT && metadata.optInt("formatVersion") == FORMAT_VERSION) {
                "Unsupported HardwareOne OTA bundle."
            }
            require(
                metadata.optString("manifest") == "manifest.json" &&
                    metadata.optString("firmware") == "firmware.bin" &&
                    metadata.optString("publicKey") == "public-key.pem"
            ) { "OTA bundle member map is invalid." }

            val manifestRaw = entries.getValue("manifest.json")
            val envelope = JSONObject(manifestRaw.toString(Charsets.UTF_8))
            require(envelope.optString("format") == "hardwareone-ota-envelope") {
                "Unsupported OTA manifest envelope."
            }
            require(envelope.optInt("formatVersion") == 1) { "Unsupported OTA manifest version." }
            val signatureObject = envelope.optJSONObject("signature")
                ?: throw IllegalArgumentException("OTA manifest has no signature.")
            require(signatureObject.optString("algorithm") == "rsa-pss-sha256") {
                "Unsupported OTA manifest signature algorithm."
            }
            val payload = decodeBase64(envelope.optString("payload"), "manifest payload")
            val signed = decodeBase64(signatureObject.optString("value"), "manifest signature")
            require(payload.size == PAYLOAD_BYTES) { "Signed OTA payload must be $PAYLOAD_BYTES bytes." }
            require(signed.size == SIGNATURE_BYTES) { "OTA signature is not RSA-3072." }
            val fields = decodePayload(payload)

            val publicKeyDer = decodePem(entries.getValue("public-key.pem"))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
            require((publicKey as? RSAKey)?.modulus?.bitLength() == 3072) {
                "Bundled OTA public key is not RSA-3072."
            }
            val verifier = Signature.getInstance("SHA256withRSAandMGF1", BouncyCastleProvider())
            verifier.initVerify(publicKey)
            verifier.setParameter(
                PSSParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    32,
                    PSS_TRAILER_FIELD_BC,
                ),
            )
            verifier.update(payload)
            require(verifier.verify(signed)) { "OTA manifest signature does not match its public key." }

            val firmware = entries.getValue("firmware.bin")
            require(firmware.isNotEmpty() && firmware.size <= MAX_IMAGE_BYTES) {
                "Firmware image is empty or does not fit ota_0."
            }
            val digest = sha256(firmware)
            require(fields.imageSize == firmware.size.toLong()) { "Firmware size does not match manifest." }
            require(fields.imageSha256.contentEquals(digest)) { "Firmware SHA-256 does not match manifest." }
            validateAppDescriptor(firmware, fields.project, fields.version)
            require(fields.project == "hardwareone-idf") { "Package is not HardwareOne firmware." }
            // Which (board, layout) identities exist is the firmware's contract,
            // not this app's. This was a hardcoded board -> layout/suffix table,
            // so every new deployment layered onto an existing board -- e.g.
            // headless/feathers3, which is feathers3 + hw1-hl-f3-ota-v1 -- was
            // rejected here as an "invalid pairing" before the device-aware
            // check in importOtaBundle could compare the package against what
            // the connected device actually reports. Parsing only proves the
            // package is internally coherent; identity is settled against
            // otastatus, and authoritatively by the device itself on staging.
            require(fields.board.isNotBlank()) { "Package does not name a board." }
            require(fields.layout.isNotBlank()) { "Package does not name an OTA layout." }
            require(fields.dataSchema == 1L) { "Unsupported firmware data schema ${fields.dataSchema}." }

            return OtaBundle(
                firmware = firmware,
                manifestJson = manifestRaw,
                board = fields.board,
                layout = fields.layout,
                version = fields.version,
                minUpdaterVersion = fields.minUpdater,
                dataSchema = fields.dataSchema,
                imageSha256 = digest.toHex(),
                publicKeyFingerprint = sha256(publicKeyDer).toHex(),
            )
        }

        private data class Payload(
            val board: String,
            val layout: String,
            val project: String,
            val version: String,
            val imageSize: Long,
            val imageSha256: ByteArray,
            val minUpdater: String,
            val dataSchema: Long,
        )

        private fun decodePayload(value: ByteArray): Payload {
            require(value.u32le(0) == PAYLOAD_MAGIC && value.u16le(4) == 1 && value.u16le(6) == PAYLOAD_BYTES) {
                "Signed OTA payload header is invalid."
            }
            require(value.copyOfRange(208, 220).all { it == 0.toByte() }) {
                "Signed OTA payload reserved bytes are nonzero."
            }
            val crc = CRC32().apply { update(value, 0, 220) }.value
            require(value.u32le(220) == crc) { "Signed OTA payload CRC is invalid." }
            return Payload(
                board = value.fixedAscii(8, 24),
                layout = value.fixedAscii(32, 24),
                project = value.fixedAscii(56, 32),
                version = value.fixedAscii(88, 48),
                imageSize = value.u32le(136),
                imageSha256 = value.copyOfRange(140, 172),
                minUpdater = value.fixedAscii(172, 32),
                dataSchema = value.u32le(204),
            )
        }

        private fun validateAppDescriptor(image: ByteArray, project: String, version: String) {
            require(image.size >= APP_DESCRIPTOR_OFFSET + APP_DESCRIPTOR_BYTES) {
                "Firmware image has no ESP app descriptor."
            }
            require(image.u32le(APP_DESCRIPTOR_OFFSET) == APP_DESCRIPTOR_MAGIC) {
                "Firmware image has an invalid ESP app descriptor."
            }
            require(image.cString(APP_DESCRIPTOR_OFFSET + 48, 32) == project) {
                "Firmware project does not match manifest."
            }
            require(image.cString(APP_DESCRIPTOR_OFFSET + 16, 32) == version) {
                "Firmware version does not match manifest."
            }
        }

        private fun decodePem(raw: ByteArray): ByteArray {
            val text = raw.toString(Charsets.US_ASCII)
            require(text.contains("-----BEGIN PUBLIC KEY-----") && text.contains("-----END PUBLIC KEY-----")) {
                "Bundled public key is not an X.509 PEM public key."
            }
            val body = text.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("") { it.trim() }
            return decodeBase64(body, "public key")
        }

        private fun decodeBase64(text: String, label: String): ByteArray =
            try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid base64 $label.", e)
            }

        private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

        private fun ByteArray.u16le(offset: Int): Int =
            (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

        private fun ByteArray.u32le(offset: Int): Long =
            (this[offset].toLong() and 0xff) or
                ((this[offset + 1].toLong() and 0xff) shl 8) or
                ((this[offset + 2].toLong() and 0xff) shl 16) or
                ((this[offset + 3].toLong() and 0xff) shl 24)

        private fun ByteArray.fixedAscii(offset: Int, capacity: Int): String {
            val field = copyOfRange(offset, offset + capacity)
            val end = field.indexOf(0.toByte())
            require(end > 0 && field.copyOfRange(end + 1, field.size).all { it == 0.toByte() }) {
                "Signed OTA text field is not canonically padded."
            }
            require(field.copyOfRange(0, end).all { (it.toInt() and 0xff) in 0x21..0x7e }) {
                "Signed OTA text field is not printable ASCII."
            }
            return field.copyOfRange(0, end).toString(Charsets.US_ASCII)
        }

        private fun ByteArray.cString(offset: Int, capacity: Int): String {
            val end = (offset until offset + capacity).firstOrNull { this[it] == 0.toByte() }
                ?: throw IllegalArgumentException("ESP app descriptor text is unterminated.")
            return copyOfRange(offset, end).toString(Charsets.UTF_8)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun ZipInputStream.readBounded(limit: Int, label: String): ByteArray {
            val output = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "$label exceeds its size limit." }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

data class OtaDeviceStatus(
    val available: Boolean,
    val board: String,
    val layout: String,
    /** ota_0 capacity for THIS device's layout; 0 when the firmware predates it. */
    val slotSize: Long,
    /** Version currently installed, for previewing the updater's downgrade rule. */
    val runningVersion: String,
    val runningPartition: String,
    val phase: String,
    val journalSequence: Long,
    val operationId: Long,
    val candidateStaged: Boolean,
    val recoveryCredentialConfigured: Boolean,
    val resultCode: String,
    val resultSequence: Long,
    val resultDetail: String,
    val resultPending: Boolean,
) {
    companion object {
        fun parse(text: String): OtaDeviceStatus? = runCatching {
            val value = JSONObject(text)
            OtaDeviceStatus(
                available = value.optBoolean("available"),
                board = value.optString("board"),
                layout = value.optString("layout"),
                slotSize = value.optLong("slotSize"),
                runningVersion = value.optString("runningVersion"),
                runningPartition = value.optString("runningPartition"),
                phase = value.optString("phase"),
                journalSequence = value.optLong("journalSequence"),
                operationId = value.optLong("operationId"),
                candidateStaged = value.optBoolean("candidateStaged"),
                recoveryCredentialConfigured = value.optBoolean("recoveryCredentialConfigured"),
                resultCode = value.optString("resultCode"),
                resultSequence = value.optLong("resultSequence"),
                resultDetail = value.optString("resultDetail"),
                resultPending = value.optBoolean("resultPending"),
            )
        }.getOrNull()
    }
}

enum class OtaStep {
    IDLE,
    CHECKING_PACKAGE,
    READY,
    SETTING_RECOVERY_PASSWORD,
    UPLOADING_FIRMWARE,
    UPLOADING_MANIFEST,
    VERIFYING_STAGED,
    ARMING_RECOVERY,
    WAITING_FOR_DEVICE,
    WAITING_FOR_LOGIN,
    PROBATION,
    SUCCEEDED,
    FAILED,
}

data class OtaUiState(
    val step: OtaStep = OtaStep.IDLE,
    val packageName: String? = null,
    val bundle: OtaBundle? = null,
    val device: OtaDeviceStatus? = null,
    val progressDone: Long = 0,
    val progressTotal: Long = 0,
    val message: String = "Choose a signed .hw1ota package to begin.",
    val error: String? = null,
) {
    val transferActive: Boolean
        get() = step == OtaStep.UPLOADING_FIRMWARE || step == OtaStep.UPLOADING_MANIFEST
    val keepScreenAwake: Boolean
        get() = transferActive || step == OtaStep.VERIFYING_STAGED || step == OtaStep.ARMING_RECOVERY
}
