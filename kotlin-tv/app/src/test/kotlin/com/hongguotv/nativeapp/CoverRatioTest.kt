// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CoverRatioTest {
    private fun cover(width: Int,height: Int)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
    @Test fun learnedRatioReplacesTheAssumedOneAndIgnoresOneOddCrop() {
        try {
            CoverRatio.reset()
            assertEquals(TvStyle.POSTER_ASPECT,CoverRatio.current,0.001f)
            CoverRatio.observe("a",cover(300,200))
            CoverRatio.observe("b",cover(100,70))
            // Two samples are not enough to move every poster frame; a single odd crop must not.
            assertFalse(CoverRatio.differsFrom(TvStyle.POSTER_ASPECT))
            repeat(3) { index -> CoverRatio.observe("tall$index",cover(300,200)) }
            assertEquals(1.5f,CoverRatio.current,0.01f)
            assertTrue(CoverRatio.differsFrom(TvStyle.POSTER_ASPECT))
            assertFalse(CoverRatio.differsFrom(1.5f))
            CoverRatio.observe("thin",cover(10,900))
            CoverRatio.observe("wide",cover(900,10))
            assertEquals(1.5f,CoverRatio.current,0.01f)
        } finally { CoverRatio.reset() }
    }
    /**
     * 一张封面会因为它的卡片被重测而反复重解码。旧写法把这些重复都算成票，
     * 于是焦点所在的那张卡能把自己海报的比例变成整页的比例，别的卡跟着缩。
     */
    @Test fun oneCoverReDecodedAtSeveralSizesNeverMovesTheMedian() {
        try {
            CoverRatio.reset()
            repeat(4) { index -> CoverRatio.observe("grid$index",cover(180,300)) }
            assertEquals(.6f,CoverRatio.current,.01f)
            repeat(6) { CoverRatio.observe("focused-cover",cover(300,200)) }
            assertEquals(.6f,CoverRatio.current,.01f)
            assertFalse(CoverRatio.differsFrom(.6f))
            // 另一张不同比例的封面仍然可以正常参与学习，去重只针对同一张。
            repeat(4) { index -> CoverRatio.observe("other$index",cover(300,200)) }
            assertEquals(1.5f,CoverRatio.current,.01f)
        } finally { CoverRatio.reset() }
    }
}
