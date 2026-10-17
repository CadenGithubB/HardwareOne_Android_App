package com.hardwareone.console.pipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single global authentication cooldown (R14 / Q3 / Q4): 30 s from the charge, active while
 * `now < charge + 30 s`. Times are an injected elapsedRealtime-style clock in milliseconds.
 */
class PipeCooldownTest {

    @Test
    fun defaultIsThirtySeconds() {
        assertEquals(30_000L, PipeCooldown.DEFAULT_MS)
        val c = PipeCooldown()
        c.charge(T0)
        assertEquals(30_000L, c.remainingMs(T0))
    }

    @Test
    fun inactiveInitially() {
        val c = PipeCooldown()
        for (now in listOf(0L, 1L, T0, Long.MAX_VALUE, -1L)) {
            assertFalse("now=$now", c.active(now))
            assertEquals("now=$now", 0L, c.remainingMs(now))
        }
    }

    @Test
    fun activeWithinTheWindow() {
        val c = PipeCooldown()
        c.charge(T0)
        assertTrue(c.active(T0))
        assertTrue(c.active(T0 + 1))
        assertTrue(c.active(T0 + 15_000))
        assertTrue(c.active(T0 + 29_999))
        assertEquals(30_000L, c.remainingMs(T0))
        assertEquals(15_000L, c.remainingMs(T0 + 15_000))
        assertEquals(1L, c.remainingMs(T0 + 29_999))
    }

    @Test
    fun inactiveAtExactlyTheEnd() {
        val c = PipeCooldown()
        c.charge(T0)
        assertFalse(c.active(T0 + 30_000))
        assertEquals(0L, c.remainingMs(T0 + 30_000))
        assertFalse(c.active(T0 + 3_600_000))
        assertEquals(0L, c.remainingMs(T0 + 3_600_000))
    }

    @Test
    fun rechargeExtendsFromTheNewFailure() {
        val c = PipeCooldown()
        c.charge(T0)
        c.charge(T0 + 10_000)
        assertTrue(c.active(T0 + 30_000))
        assertEquals(10_000L, c.remainingMs(T0 + 30_000))
        assertTrue(c.active(T0 + 39_999))
        assertFalse(c.active(T0 + 40_000))
        // A charge after expiry starts a fresh window.
        c.charge(T0 + 100_000)
        assertTrue(c.active(T0 + 100_000))
        assertEquals(30_000L, c.remainingMs(T0 + 100_000))
        assertFalse(c.active(T0 + 130_000))
    }

    /** A late-delivered, earlier timestamp never shortens a running window. */
    @Test
    fun rechargeNeverShortens() {
        val c = PipeCooldown()
        c.charge(T0 + 10_000)
        c.charge(T0)
        assertTrue(c.active(T0 + 39_999))
        assertEquals(1L, c.remainingMs(T0 + 39_999))
        assertFalse(c.active(T0 + 40_000))
    }

    @Test
    fun clearEndsTheWindow() {
        val c = PipeCooldown()
        c.charge(T0)
        c.clear()
        assertFalse(c.active(T0))
        assertEquals(0L, c.remainingMs(T0))
        assertFalse(c.active(T0 + 1))
        // Clearing an idle cooldown is harmless, and it can be charged again.
        c.clear()
        assertFalse(c.active(T0))
        c.charge(T0 + 5)
        assertTrue(c.active(T0 + 5))
        assertEquals(30_000L, c.remainingMs(T0 + 5))
    }

    @Test
    fun customDurationIsHonoured() {
        val c = PipeCooldown(durationMs = 1_000L)
        c.charge(T0)
        assertTrue(c.active(T0 + 999))
        assertFalse(c.active(T0 + 1_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveDurationIsRejected() {
        PipeCooldown(durationMs = 0L)
    }

    private companion object {
        /** An arbitrary elapsedRealtime reading well after boot. */
        const val T0 = 5_000_000L
    }
}
