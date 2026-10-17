package com.hardwareone.console.pipe

/**
 * What the stage-4 pipe's GATT server does with each ATT request, as a pure decision table so it
 * is unit-testable without Bluetooth. There is no bonding: the attributes have plain permissions
 * and the HW1-PIPE-v1 secure channel authenticates the peer, so this only enforces the request
 * shapes and the single-candidate admission rule. [Decision.status] null means no ATT response
 * exists (a write command).
 *
 * In every call, `admitted` means this device is the admitted pipe candidate; `otherAdmitted`
 * means a different device is.
 *
 * On Android the stack itself checks a CCCD write before this code sees it: a write request whose
 * length is not 2 gets 0x0d, a prepared write at a nonzero offset gets 0x0b, and a malformed write
 * command is dropped. Those cases never reach [cccdWrite] and do not end a session; its length and
 * offset branches stay as defensive checks.
 */
object PipeAttPolicy {
    const val ATT_SUCCESS = 0x00
    const val ATT_REQUEST_NOT_SUPPORTED = 0x06
    const val ATT_INVALID_OFFSET = 0x07
    const val ATT_INVALID_ATTRIBUTE_LENGTH = 0x0d
    /** Well-formed ATT that breaks the pipe protocol (a duplicate CCCD enable). */
    const val ATT_UNLIKELY_ERROR = 0x0e
    /** The pipe cannot take this device now (busy with another, cooling down, or not ready). */
    const val ATT_INSUFFICIENT_RESOURCES = 0x11

    /** Smallest negotiated ATT MTU a CCCD enable is admitted with. */
    const val MIN_MTU = 64

    enum class Action {
        /** Only the response, if any. */
        NONE,
        /** Hand the RX write-command value to the admitted session. */
        DELIVER,
        /** Admit this device as the single pipe candidate, respond 0x00, then start the secure handshake. */
        ADMIT,
        /** Respond (when the status is non-null), then end the admitted session silently. */
        END_AFTER,
        /** End the admitted session; no response is possible. */
        END,
    }

    /** [status] is the ATT response to send, or null when none exists (a write command). */
    data class Decision(val status: Int?, val action: Action)

    /** A characteristic write. [isRx]: it targets RX, whose only legal form is a write command. */
    fun rxWrite(isRx: Boolean, responseNeeded: Boolean, preparedWrite: Boolean, offset: Int, admitted: Boolean): Decision = when {
        !isRx -> Decision(if (responseNeeded) ATT_REQUEST_NOT_SUPPORTED else null, Action.NONE)
        // A write command is never answered; only the admitted candidate's bytes reach a session.
        !responseNeeded -> Decision(null, if (admitted) Action.DELIVER else Action.NONE)
        // Write requests to RX (prepared or not) are not part of stage 4.
        else -> Decision(if (offset != 0) ATT_INVALID_OFFSET else ATT_REQUEST_NOT_SUPPORTED, endAfterIf(admitted))
    }

    /**
     * A descriptor write. [isCccd]: it targets TX's CCCD. [coolingDown]: the global failure
     * cooldown is running. [ready]: the pipe can start a session right now. [mtu]: the link's
     * negotiated ATT MTU. Admission happens here, on the first valid enable, never on connection.
     */
    fun cccdWrite(
        isCccd: Boolean, responseNeeded: Boolean, preparedWrite: Boolean, offset: Int, value: ByteArray?, admitted: Boolean,
        otherAdmitted: Boolean, coolingDown: Boolean, ready: Boolean, mtu: Int,
    ): Decision = when {
        !isCccd -> Decision(if (responseNeeded) ATT_REQUEST_NOT_SUPPORTED else null, Action.NONE)
        // A CCCD write command cannot be refused: from the admitted peer it ends the session.
        !responseNeeded -> Decision(null, if (admitted) Action.END else Action.NONE)
        offset != 0 -> Decision(ATT_INVALID_OFFSET, endAfterIf(admitted))
        preparedWrite -> Decision(ATT_REQUEST_NOT_SUPPORTED, endAfterIf(admitted))
        value == null || value.size != 2 -> Decision(ATT_INVALID_ATTRIBUTE_LENGTH, endAfterIf(admitted))
        value[0].toInt() == 1 && value[1].toInt() == 0 -> when {
            // The handshake starts once per admission: a second enable is a protocol error.
            admitted -> Decision(ATT_UNLIKELY_ERROR, Action.END_AFTER)
            otherAdmitted || coolingDown || !ready -> Decision(ATT_INSUFFICIENT_RESOURCES, Action.NONE)
            mtu < MIN_MTU -> Decision(ATT_INVALID_ATTRIBUTE_LENGTH, Action.NONE)
            else -> Decision(ATT_SUCCESS, Action.ADMIT)
        }
        // Unsubscribing ends the session; nothing may be sent afterwards.
        value[0].toInt() == 0 && value[1].toInt() == 0 -> Decision(ATT_SUCCESS, endAfterIf(admitted))
        // Indications (02 00) and anything else are not part of stage 4.
        else -> Decision(ATT_REQUEST_NOT_SUPPORTED, endAfterIf(admitted))
    }

    /** Reads: stage 4 has none. */
    fun read(): Int = ATT_REQUEST_NOT_SUPPORTED

    /** Execute Write: stage 4 has no long writes. */
    fun executeWrite(): Int = ATT_REQUEST_NOT_SUPPORTED

    /**
     * onNotificationSent: 0, or 0x8f (GATT_CONNECTION_CONGESTED) = queued locally. Neither proves
     * transmission or receipt; any other status is terminal for the session.
     */
    fun notificationAccepted(status: Int): Boolean = status == 0 || status == 0x8f

    private fun endAfterIf(admitted: Boolean) = if (admitted) Action.END_AFTER else Action.NONE
}
