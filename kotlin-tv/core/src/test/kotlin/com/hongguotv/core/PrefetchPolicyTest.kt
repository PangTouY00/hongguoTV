// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PrefetchPolicyTest {
    @Test fun cellularKeepsWarmingWithASmallerFootprint() {
        assertEquals(2*1024*1024,PrefetchPolicy.warmBytes(false))
        assertEquals(1024*1024,PrefetchPolicy.warmBytes(true))
        // Both budgets stay inside the bounded warm-up range RemoteVideo accepts.
        assertTrue(PrefetchPolicy.warmBytes(true)>=256*1024 && PrefetchPolicy.warmBytes(false)<=4*1024*1024)
    }
    @Test fun cellularStartsEarlierAndAcceptsALowerBufferLead() {
        // 45 seconds before the end: unmetered still waits for its 30 second window, cellular warms.
        assertFalse(PrefetchPolicy.canStart(45_000,90_000,1f,2_000,6_000,false))
        assertTrue(PrefetchPolicy.canStart(45_000,90_000,1f,2_000,6_000,true))
        // Cellular buffers unevenly; waiting for a 5 second lead would miss the window entirely.
        assertTrue(PrefetchPolicy.canStart(80_000,90_000,1f,2_000,3_000,true))
        assertFalse(PrefetchPolicy.canStart(80_000,90_000,1f,2_000,3_000,false))
    }
    @Test fun shortEpisodesWarmTheNextOneFromTheStart() {
        // A 40 second episode is already inside the cellular lead the moment it begins playing.
        assertTrue(PrefetchPolicy.canStart(0,40_000,1f,1_600,2_600,true))
    }
    @Test fun playbackSpeedCountsAgainstTheLead() {
        // 50 seconds of media left at 2x is 25 seconds of wall clock, so unmetered starts.
        assertTrue(PrefetchPolicy.canStart(40_000,90_000,2f,2_000,6_000,false))
        // 55 seconds of wall clock left is outside the 30 second window but inside the 60 second
        // one cellular allows itself, because a phone needs more time to pull the same bytes.
        assertFalse(PrefetchPolicy.nearEnd(35_000,90_000,1f,false))
        assertTrue(PrefetchPolicy.nearEnd(35_000,90_000,1f,true))
        assertFalse(PrefetchPolicy.nearEnd(10_000,90_000,1f,false))
        assertFalse(PrefetchPolicy.nearEnd(10_000,90_000,1f,true))
    }
    @Test fun unstableOrFinishedPlaybackNeverWarms() {
        assertFalse(PrefetchPolicy.canStart(80_000,90_000,1f,1_400,9_000,true))
        assertFalse(PrefetchPolicy.canStart(90_000,90_000,1f,2_000,9_000,true))
        assertFalse(PrefetchPolicy.canStart(0,0,1f,2_000,9_000,true))
        assertFalse(PrefetchPolicy.canStart(0,90_000,0f,2_000,9_000,true))
        assertFalse(PrefetchPolicy.canStart(-1,90_000,1f,2_000,9_000,true))
    }
    @Test fun cellularKeepsTryingMoreTimesWithAShorterCooldown() {
        assertEquals(3,PrefetchPolicy.maxAttempts(false))
        assertEquals(4,PrefetchPolicy.maxAttempts(true))
        assertEquals(15_000L,PrefetchPolicy.retryCooldownMs(false))
        assertEquals(8_000L,PrefetchPolicy.retryCooldownMs(true))
    }
}
