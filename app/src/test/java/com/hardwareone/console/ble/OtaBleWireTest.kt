package com.hardwareone.console.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaBleWireTest {
    @Test
    fun chunkEnvelopeIsExactAndBigEndian() {
        val frame = OtaBleWire.encodeChunk(0x0102_03feL, byteArrayOf(0x00, 0x7f, 0xff.toByte()))
        assertArrayEquals(
            byteArrayOf(
                0x00, 'H'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte(),
                'O'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 0x01,
                0x01, 0x02, 0x03, 0xfe.toByte(),
                0x00, 0x7f, 0xff.toByte(),
            ),
            frame,
        )
    }

    /** Exactly the shape firmware's bleUploadJson() serialises on `otawrite begin`. */
    @Test
    fun parsesDeviceBeginCheckpoint() {
        val status = OtaWriteStatus.parse(
            """{"success":true,"active":true,"member":"candidate","size":0,""" +
                """"expected":5246976,"final":false,"chunkMax":477}""",
        )
        assertNotNull(status)
        requireNotNull(status)
        assertTrue(status.success)
        assertTrue(status.active)
        assertEquals("candidate", status.member)
        assertEquals(0L, status.size)
        assertEquals(5246976L, status.expected)
        assertEquals(477, status.chunkMax)
        assertNull(status.error)
    }

    /** A finished member reports final=true with size == expected. */
    @Test
    fun parsesFinalCheckpoint() {
        val status = OtaWriteStatus.parse(
            """{"success":true,"active":false,"member":"manifest","size":967,""" +
                """"expected":967,"final":true,"chunkMax":477}""",
        )
        requireNotNull(status)
        assertTrue(status.final)
        assertEquals(status.expected, status.size)
    }

    /** A rejected upload must surface the device's own reason, never a blank string. */
    @Test
    fun failureCheckpointCarriesDeviceError() {
        val status = OtaWriteStatus.parse(
            """{"success":false,"active":true,"member":"candidate","size":1431,""" +
                """"expected":5246976,"final":false,"chunkMax":477,""" +
                """"error":"transport SHA-256 mismatch"}""",
        )
        requireNotNull(status)
        assertEquals("transport SHA-256 mismatch", status.error)
    }

    /** Errors that arrive without a JSON body must not parse into a fake success. */
    @Test
    fun rejectsNonCheckpointReplies() {
        assertNull(OtaWriteStatus.parse("Error: otawrite requires the encrypted Bluetooth channel"))
        assertNull(OtaWriteStatus.parse(""))
        assertNull(OtaWriteStatus.parse("""{"active":true}"""))
    }
}
