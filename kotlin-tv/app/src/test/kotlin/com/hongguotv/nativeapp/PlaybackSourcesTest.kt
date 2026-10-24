package com.hongguotv.nativeapp

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourcesTest {
    @Test fun manualRetryOnlyOffersLowerTierThanAttempted() {
        assertEquals(720,nextLowerQuality(1080))
        assertEquals(480,nextLowerQuality(720))
        assertNull(nextLowerQuality(480))
    }
}
