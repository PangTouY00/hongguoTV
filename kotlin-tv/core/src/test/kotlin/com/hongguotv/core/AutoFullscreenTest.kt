package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class AutoFullscreenTest {
    private val idle = 20_000L

    @Test fun `the default idle window is twenty seconds`() {
        assertEquals(20_000L, AutoFullscreenGuard().idleMs)
    }

    @Test fun `fires once the idle window is complete and never fires twice`() {
        val guard = AutoFullscreenGuard(idle)
        guard.arm("a", 1_000)
        assertNull(guard.elapsed(1_000 + idle - 1))
        assertEquals("a", guard.elapsed(1_000 + idle))
        assertNull(guard.elapsed(1_000 + idle + 10_000))
    }

    @Test fun `every activity pushes the deadline out`() {
        val guard = AutoFullscreenGuard(idle)
        guard.arm("a", 1_000)
        assertNull(guard.elapsed(1_000 + idle - 1))
        // 快到的时候用户又动了这一部：计时重新开始，不该按更早那一刻触发。
        guard.arm("a", 1_000 + idle - 5_000)
        assertNull(guard.elapsed(1_000 + idle))
        assertEquals("a", guard.elapsed(1_000 + idle - 5_000 + idle))
    }

    @Test fun `switching to another work counts that one from that moment`() {
        val guard = AutoFullscreenGuard(idle)
        guard.arm("a", 1_000)
        guard.arm("b", 1_000 + idle - 1)
        assertNull(guard.elapsed(1_000 + idle))
        assertEquals("b", guard.elapsed(1_000 + idle - 1 + idle))
    }

    @Test fun `cancelling then arming again counts from the new moment`() {
        val guard = AutoFullscreenGuard(idle)
        guard.arm("a", 1_000)
        guard.cancel()
        assertFalse(guard.armed)
        guard.arm("a", 1_000 + idle)
        assertNull(guard.elapsed(1_000 + idle + idle - 1))
        assertEquals("a", guard.elapsed(1_000 + idle + idle))
    }

    @Test fun `a work the user left on purpose stays suppressed until another one is chosen`() {
        val guard = AutoFullscreenGuard(idle)
        guard.arm("a", 1_000)
        guard.suppress("a")
        assertFalse(guard.armed)
        assertTrue(guard.suppressed("a"))
        guard.arm("a", 2_000)
        assertNull(guard.elapsed(2_000 + idle))
        guard.arm("b", 3_000)
        assertEquals("b", guard.elapsed(3_000 + idle))
    }

    @Test fun `suppressing only clears the timer for that work, not for the one being watched now`() {
        val guard = AutoFullscreenGuard(idle)
        guard.arm("a", 1_000)
        guard.arm("b", 2_000)
        guard.suppress("a")
        assertEquals("b", guard.current)
        assertEquals("b", guard.elapsed(2_000 + idle))
    }

    @Test fun `remaining reports how long is left and stays at zero once due`() {
        val guard = AutoFullscreenGuard(idle)
        assertNull(guard.remaining(0))
        guard.arm("a", 1_000)
        assertEquals(idle, guard.remaining(1_000))
        assertEquals(5_000L, guard.remaining(1_000 + idle - 5_000))
        assertEquals(0L, guard.remaining(1_000 + idle + 1))
    }

    @Test fun `the suppression list stays bounded`() {
        val guard = AutoFullscreenGuard(idle)
        (1..60).forEach { guard.suppress("id$it") }
        assertFalse(guard.suppressed("id1"))
        assertTrue(guard.suppressed("id60"))
    }
}
