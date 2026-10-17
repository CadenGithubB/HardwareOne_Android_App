package com.hardwareone.console.pipe

import com.hardwareone.console.pipe.PipeAttPolicy.Action
import com.hardwareone.console.pipe.PipeAttPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage-4 ATT access table: RX takes write commands only, the first valid CCCD enable admits
 * the single candidate, and every malformed or out-of-contract request from the admitted peer
 * ends its session. Each ordering rule is pinned by giving a request several reasons to fail.
 */
class PipeAttPolicyTest {
    private val enable = byteArrayOf(1, 0)
    private val disable = byteArrayOf(0, 0)

    private fun rx(isRx: Boolean = true, responseNeeded: Boolean = false, prepared: Boolean = false, offset: Int = 0, admitted: Boolean = true) =
        PipeAttPolicy.rxWrite(isRx, responseNeeded, prepared, offset, admitted)

    private fun cccd(
        value: ByteArray? = enable, isCccd: Boolean = true, responseNeeded: Boolean = true, prepared: Boolean = false, offset: Int = 0,
        admitted: Boolean = false, otherAdmitted: Boolean = false, coolingDown: Boolean = false, ready: Boolean = true, mtu: Int = 247,
    ) = PipeAttPolicy.cccdWrite(isCccd, responseNeeded, prepared, offset, value, admitted, otherAdmitted, coolingDown, ready, mtu)

    // ---- RX -------------------------------------------------------------------------------------

    @Test
    fun aWriteToAnotherCharacteristicIsUnsupportedAndTouchesNoSession() {
        assertEquals(Decision(0x06, Action.NONE), rx(isRx = false, responseNeeded = true))
        assertEquals(Decision(0x06, Action.NONE), rx(isRx = false, responseNeeded = true, admitted = false))
        // Its own offset or prepared flag does not change the answer.
        assertEquals(Decision(0x06, Action.NONE), rx(isRx = false, responseNeeded = true, offset = 4, prepared = true))
        assertEquals(Decision(null, Action.NONE), rx(isRx = false, responseNeeded = false))
        assertEquals(Decision(null, Action.NONE), rx(isRx = false, responseNeeded = false, admitted = false))
    }

    @Test
    fun onlyTheAdmittedCandidatesWriteCommandIsDelivered() {
        assertEquals(Decision(null, Action.DELIVER), rx())
        // Never answered, never delivered: not admitted (yet) or another device's link.
        assertEquals(Decision(null, Action.NONE), rx(admitted = false))
    }

    @Test
    fun aWriteCommandsOffsetAndPreparedFlagDoNotChangeItsHandling() {
        assertEquals(Decision(null, Action.DELIVER), rx(offset = 1))
        assertEquals(Decision(null, Action.DELIVER), rx(prepared = true))
        assertEquals(Decision(null, Action.DELIVER), rx(prepared = true, offset = 1))
        assertEquals(Decision(null, Action.NONE), rx(prepared = true, offset = 1, admitted = false))
    }

    @Test
    fun aWriteRequestToRxIsUnsupportedAndEndsTheAdmittedSession() {
        assertEquals(Decision(0x06, Action.END_AFTER), rx(responseNeeded = true))
        assertEquals(Decision(0x06, Action.END_AFTER), rx(responseNeeded = true, prepared = true))
        assertEquals(Decision(0x06, Action.NONE), rx(responseNeeded = true, admitted = false))
        assertEquals(Decision(0x06, Action.NONE), rx(responseNeeded = true, prepared = true, admitted = false))
    }

    @Test
    fun aNonzeroRxOffsetIsInvalidEvenOnAPreparedWrite() {
        assertEquals(Decision(0x07, Action.END_AFTER), rx(responseNeeded = true, offset = 1))
        assertEquals(Decision(0x07, Action.END_AFTER), rx(responseNeeded = true, offset = 1, prepared = true))
        assertEquals(Decision(0x07, Action.NONE), rx(responseNeeded = true, offset = 512, admitted = false))
        assertEquals(Decision(0x07, Action.NONE), rx(responseNeeded = true, offset = 1, prepared = true, admitted = false))
    }

    // ---- CCCD: request shape --------------------------------------------------------------------

    @Test
    fun aWriteToAnotherDescriptorIsUnsupportedAndTouchesNoSession() {
        assertEquals(Decision(0x06, Action.NONE), cccd(isCccd = false))
        assertEquals(Decision(0x06, Action.NONE), cccd(isCccd = false, admitted = true))
        assertEquals(Decision(0x06, Action.NONE), cccd(value = null, isCccd = false, offset = 2, prepared = true, admitted = true))
        assertEquals(Decision(null, Action.NONE), cccd(isCccd = false, responseNeeded = false))
        assertEquals(Decision(null, Action.NONE), cccd(isCccd = false, responseNeeded = false, admitted = true))
    }

    @Test
    fun aCccdWriteCommandEndsTheAdmittedSessionWithoutAResponse() {
        for (value in listOf(enable, disable, byteArrayOf(2, 0), byteArrayOf(1), null)) {
            assertEquals(Decision(null, Action.END), cccd(value = value, responseNeeded = false, admitted = true))
            assertEquals(Decision(null, Action.NONE), cccd(value = value, responseNeeded = false))
        }
        // A command is never an admission, even when everything else would allow one.
        assertEquals(Decision(null, Action.NONE), cccd(responseNeeded = false, mtu = 517))
        // Nor does its offset or prepared flag turn it into a shape error that spares the session.
        assertEquals(Decision(null, Action.END), cccd(responseNeeded = false, offset = 1, admitted = true))
        assertEquals(Decision(null, Action.END), cccd(responseNeeded = false, prepared = true, admitted = true))
        assertEquals(Decision(null, Action.END), cccd(responseNeeded = false, prepared = true, offset = 1, admitted = true))
        assertEquals(Decision(null, Action.NONE), cccd(responseNeeded = false, prepared = true, offset = 1))
    }

    @Test
    fun offsetBeatsPreparedBeatsLength() {
        assertEquals(Decision(0x07, Action.NONE), cccd(value = null, offset = 1, prepared = true))
        assertEquals(Decision(0x07, Action.END_AFTER), cccd(value = null, offset = 1, prepared = true, admitted = true))
        assertEquals(Decision(0x06, Action.NONE), cccd(value = null, prepared = true))
        assertEquals(Decision(0x06, Action.END_AFTER), cccd(value = null, prepared = true, admitted = true))
        assertEquals(Decision(0x0d, Action.NONE), cccd(value = null))
        assertEquals(Decision(0x0d, Action.END_AFTER), cccd(value = null, admitted = true))
    }

    @Test
    fun aValidEnableWithABadShapeIsStillRefused() {
        assertEquals(Decision(0x07, Action.NONE), cccd(offset = 1))
        assertEquals(Decision(0x07, Action.END_AFTER), cccd(offset = 1, admitted = true))
        assertEquals(Decision(0x06, Action.NONE), cccd(prepared = true))
        assertEquals(Decision(0x06, Action.END_AFTER), cccd(prepared = true, admitted = true))
    }

    @Test
    fun aCccdValueThatIsNotTwoBytesIsAnInvalidLength() {
        for (value in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(1, 0, 0), byteArrayOf(0, 0, 0, 0))) {
            assertEquals(Decision(0x0d, Action.NONE), cccd(value = value))
            assertEquals(Decision(0x0d, Action.END_AFTER), cccd(value = value, admitted = true))
        }
        // The length check runs before any admission check.
        assertEquals(Decision(0x0d, Action.NONE), cccd(value = byteArrayOf(1), otherAdmitted = true, coolingDown = true, ready = false))
    }

    // ---- CCCD: enable and admission -------------------------------------------------------------

    @Test
    fun theFirstValidEnableAdmitsTheDevice() {
        assertEquals(Decision(0x00, Action.ADMIT), cccd())
        assertEquals(Decision(0x00, Action.ADMIT), cccd(mtu = 517))
    }

    @Test
    fun aDuplicateEnableFromTheAdmittedPeerEndsItsSession() {
        assertEquals(Decision(0x0e, Action.END_AFTER), cccd(admitted = true))
        // It beats every admission refusal.
        assertEquals(Decision(0x0e, Action.END_AFTER), cccd(admitted = true, otherAdmitted = true))
        assertEquals(Decision(0x0e, Action.END_AFTER), cccd(admitted = true, coolingDown = true))
        assertEquals(Decision(0x0e, Action.END_AFTER), cccd(admitted = true, ready = false))
        assertEquals(Decision(0x0e, Action.END_AFTER), cccd(admitted = true, mtu = 23))
        assertEquals(Decision(0x0e, Action.END_AFTER), cccd(admitted = true, otherAdmitted = true, coolingDown = true, ready = false, mtu = 0))
    }

    @Test
    fun aSecondDeviceIsRefusedWithInsufficientResources() {
        assertEquals(Decision(0x11, Action.NONE), cccd(otherAdmitted = true))
        // otherAdmitted beats coolingDown, !ready and mtu.
        assertEquals(Decision(0x11, Action.NONE), cccd(otherAdmitted = true, coolingDown = true))
        assertEquals(Decision(0x11, Action.NONE), cccd(otherAdmitted = true, ready = false))
        assertEquals(Decision(0x11, Action.NONE), cccd(otherAdmitted = true, mtu = 63))
        assertEquals(Decision(0x11, Action.NONE), cccd(otherAdmitted = true, coolingDown = true, ready = false, mtu = 0))
    }

    @Test
    fun anEnableDuringTheCooldownIsRefusedWithInsufficientResources() {
        assertEquals(Decision(0x11, Action.NONE), cccd(coolingDown = true))
        // coolingDown beats !ready and mtu.
        assertEquals(Decision(0x11, Action.NONE), cccd(coolingDown = true, ready = false))
        assertEquals(Decision(0x11, Action.NONE), cccd(coolingDown = true, mtu = 63))
        assertEquals(Decision(0x11, Action.NONE), cccd(coolingDown = true, ready = false, mtu = 0))
    }

    @Test
    fun anEnableBeforeThePipeIsReadyIsRefusedWithInsufficientResources() {
        assertEquals(Decision(0x11, Action.NONE), cccd(ready = false))
        // !ready beats mtu.
        assertEquals(Decision(0x11, Action.NONE), cccd(ready = false, mtu = 63))
        assertEquals(Decision(0x11, Action.NONE), cccd(ready = false, mtu = 0))
    }

    @Test
    fun anMtuBelow64IsRefusedWithInvalidLengthAndExactly64IsAdmitted() {
        assertEquals(Decision(0x0d, Action.NONE), cccd(mtu = 63))
        assertEquals(Decision(0x0d, Action.NONE), cccd(mtu = 23))
        // 0 = no MTU exchange seen on this link.
        assertEquals(Decision(0x0d, Action.NONE), cccd(mtu = 0))
        assertEquals(Decision(0x00, Action.ADMIT), cccd(mtu = 64))
        assertEquals(64, PipeAttPolicy.MIN_MTU)
    }

    // ---- CCCD: disable and other values ---------------------------------------------------------

    @Test
    fun unsubscribingSucceedsAndEndsTheAdmittedSession() {
        assertEquals(Decision(0x00, Action.END_AFTER), cccd(value = disable, admitted = true))
        assertEquals(Decision(0x00, Action.NONE), cccd(value = disable))
        // Admission state of other devices and the pipe does not matter for a non-candidate.
        assertEquals(Decision(0x00, Action.NONE), cccd(value = disable, otherAdmitted = true, coolingDown = true, ready = false, mtu = 0))
    }

    @Test
    fun indicationsAndOtherCccdValuesAreUnsupported() {
        val others = listOf(byteArrayOf(2, 0), byteArrayOf(3, 0), byteArrayOf(1, 1), byteArrayOf(0, 1), byteArrayOf(-1, -1))
        for (value in others) {
            val hex = value.joinToString(" ") { "%02x".format(it) }
            assertEquals(hex, Decision(0x06, Action.END_AFTER), cccd(value = value, admitted = true))
            assertEquals(hex, Decision(0x06, Action.NONE), cccd(value = value))
            assertEquals(hex, Decision(0x06, Action.NONE), cccd(value = value, otherAdmitted = true))
        }
    }

    // ---- Cross-cutting --------------------------------------------------------------------------

    @Test
    fun aWriteCommandNeverYieldsAStatus() {
        val bools = listOf(false, true)
        val values = listOf(enable, disable, byteArrayOf(2, 0), byteArrayOf(1), null)
        for (target in bools) for (prepared in bools) for (offset in listOf(0, 1)) for (admitted in bools) {
            val where = "target=$target prepared=$prepared offset=$offset admitted=$admitted"
            // Only the target and admission decide the action: never the offset, prepared flag or anything else.
            val rxAction = if (target && admitted) Action.DELIVER else Action.NONE
            val r = PipeAttPolicy.rxWrite(target, false, prepared, offset, admitted)
            assertEquals(where, Decision(null, rxAction), r)
            val cccdAction = if (target && admitted) Action.END else Action.NONE
            for (value in values) for (other in bools) for (cooling in bools) for (ready in bools) for (mtu in listOf(0, 63, 64, 517)) {
                val d = PipeAttPolicy.cccdWrite(target, false, prepared, offset, value, admitted, other, cooling, ready, mtu)
                assertEquals("$where value=${value?.toList()} other=$other cooling=$cooling ready=$ready mtu=$mtu", Decision(null, cccdAction), d)
            }
        }
    }

    @Test
    fun readsAndExecuteWritesAreUnsupported() {
        assertEquals(0x06, PipeAttPolicy.read())
        assertEquals(0x06, PipeAttPolicy.executeWrite())
    }

    @Test
    fun onlySuccessAndCongestedNotificationCompletionsAreAccepted() {
        assertTrue(PipeAttPolicy.notificationAccepted(0))
        assertTrue(PipeAttPolicy.notificationAccepted(0x8f))
        for (status in listOf(0x01, 0x80, 0x81, 0x85, 0x87, 0x88, 0x8e, 0x101)) {
            assertFalse("status 0x%x".format(status), PipeAttPolicy.notificationAccepted(status))
        }
    }

    @Test
    fun attCodesMatchTheBluetoothCoreValues() {
        assertEquals(0x00, PipeAttPolicy.ATT_SUCCESS)
        assertEquals(0x06, PipeAttPolicy.ATT_REQUEST_NOT_SUPPORTED)
        assertEquals(0x07, PipeAttPolicy.ATT_INVALID_OFFSET)
        assertEquals(0x0d, PipeAttPolicy.ATT_INVALID_ATTRIBUTE_LENGTH)
        assertEquals(0x0e, PipeAttPolicy.ATT_UNLIKELY_ERROR)
        assertEquals(0x11, PipeAttPolicy.ATT_INSUFFICIENT_RESOURCES)
    }
}
