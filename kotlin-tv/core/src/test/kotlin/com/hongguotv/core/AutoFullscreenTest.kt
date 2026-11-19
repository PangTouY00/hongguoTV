package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class AutoFullscreenTest {
    private val dwell = 30_000L

    @Test fun `fires once the dwell is complete and never fires twice`() {
        val guard = AutoFullscreenGuard(dwell)
        guard.arm("a", 1_000)
        assertNull(guard.elapsed(1_000 + dwell - 1))
        assertEquals("a", guard.elapsed(1_000 + dwell))
        assertNull(guard.elapsed(1_000 + dwell + 10_000))
    }

    @Test fun `re-arming the same work does not restart the clock`() {
        val guard = AutoFullscreenGuard(dwell)
        guard.arm("a", 1_000)
        guard.arm("a", 1_000 + dwell - 5_000)
        assertNull(guard.elapsed(1_000 + dwell - 1))
        assertEquals("a", guard.elapsed(1_000 + dwell))
    }

    @Test fun `moving to another work starts a fresh dwell for that work only`() {
        val guard = AutoFullscreenGuard(dwell)
        guard.arm("a", 1_000)
        guard.arm("b", 1_000 + dwell - 1)
        assertNull(guard.elapsed(1_000 + dwell))
        assertEquals("b", guard.elapsed(1_000 + dwell - 1 + dwell))
    }

    @Test fun `cancelling then arming again counts from the new moment`() {
        val guard = AutoFullscreenGuard(dwell)
        guard.arm("a", 1_000)
        guard.cancel()
        assertFalse(guard.armed)
        guard.arm("a", 1_000 + dwell)
        assertNull(guard.elapsed(1_000 + dwell + dwell - 1))
        assertEquals("a", guard.elapsed(1_000 + dwell + dwell))
    }

    @Test fun `a work the user left on purpose stays suppressed until another one is chosen`() {
        val guard = AutoFullscreenGuard(dwell)
        guard.arm("a", 1_000)
        guard.suppress("a")
        assertFalse(guard.armed)
        assertTrue(guard.suppressed("a"))
        guard.arm("a", 2_000)
        assertNull(guard.elapsed(2_000 + dwell))
        guard.arm("b", 3_000)
        assertEquals("b", guard.elapsed(3_000 + dwell))
    }

    @Test fun `suppressing only clears the timer for that work, not for the one being watched now`() {
        val guard = AutoFullscreenGuard(dwell)
        guard.arm("a", 1_000)
        guard.arm("b", 2_000)
        guard.suppress("a")
        assertEquals("b", guard.current)
        assertEquals("b", guard.elapsed(2_000 + dwell))
    }

    @Test fun `remaining reports how long is left and stays at zero once due`() {
        val guard = AutoFullscreenGuard(dwell)
        assertNull(guard.remaining(0))
        guard.arm("a", 1_000)
        assertEquals(dwell, guard.remaining(1_000))
        assertEquals(5_000L, guard.remaining(1_000 + dwell - 5_000))
        assertEquals(0L, guard.remaining(1_000 + dwell + 1))
    }

    @Test fun `the suppression list stays bounded`() {
        val guard = AutoFullscreenGuard(dwell)
        (1..60).forEach { guard.suppress("id$it") }
        assertFalse(guard.suppressed("id1"))
        assertTrue(guard.suppressed("id60"))
    }
}
