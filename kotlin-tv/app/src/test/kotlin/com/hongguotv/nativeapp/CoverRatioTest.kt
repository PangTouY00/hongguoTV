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
    @Test fun learnedRatioReplacesTheAssumedOneAndIgnoresOneOddCrop() {
        try {
            CoverRatio.reset()
            assertEquals(TvStyle.POSTER_ASPECT,CoverRatio.current,0.001f)
            CoverRatio.observe(Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888))
            CoverRatio.observe(Bitmap.createBitmap(100,70,Bitmap.Config.ARGB_8888))
            // Two samples are not enough to move every poster frame; a single odd crop must not.
            assertFalse(CoverRatio.differsFrom(TvStyle.POSTER_ASPECT))
            repeat(3) { CoverRatio.observe(Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888)) }
            assertEquals(1.5f,CoverRatio.current,0.01f)
            assertTrue(CoverRatio.differsFrom(TvStyle.POSTER_ASPECT))
            assertFalse(CoverRatio.differsFrom(1.5f))
            CoverRatio.observe(Bitmap.createBitmap(10,900,Bitmap.Config.ARGB_8888))
            CoverRatio.observe(Bitmap.createBitmap(900,10,Bitmap.Config.ARGB_8888))
            assertEquals(1.5f,CoverRatio.current,0.01f)
        } finally { CoverRatio.reset() }
    }
}
