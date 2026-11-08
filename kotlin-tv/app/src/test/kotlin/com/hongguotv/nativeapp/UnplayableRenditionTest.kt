package com.hongguotv.nativeapp

import com.hongguotv.core.Rendition
import org.junit.Assert.*
import org.junit.Test

/** 「解不了」要说清是哪一档：只留一句「没有可播放的清晰度」，用户没法判断是该换清晰度还是换一集。 */
class UnplayableRenditionTest {
    private fun rendition(quality: Int, codec: String, width: Int?, height: Int?) =
        Rendition(quality, codec, width, height, "https://example.com/$quality", null)

    @Test fun aSingleTierNamesThatTier() {
        val text = unplayableRenditionMessage(listOf(rendition(1080, "bytevc1", 1924, 1080)))
        assertTrue(text.contains("只有 1924×1080 的一档"))
        assertTrue(text.contains("这台设备解不了"))
    }

    @Test fun severalTiersListTheirSizes() {
        val text = unplayableRenditionMessage(listOf(rendition(1080, "bytevc1", 1924, 1080), rendition(720, "h264", 1280, 720)))
        assertTrue(text.contains("1924×1080"))
        assertTrue(text.contains("1280×720"))
        assertFalse(text.contains("只有"))
    }

    /** 同一尺寸在阶梯里可能出现多次（不同编码、不同签名地址），摘要里只写一次。 */
    @Test fun repeatedSizesAreNamedOnce() {
        val text = unplayableRenditionMessage(listOf(rendition(1080, "bytevc1", 1924, 1080), rendition(1080, "h264", 1924, 1080)))
        assertTrue(text.contains("只有 1924×1080 的一档"))
        assertFalse(text.contains("、"))
    }

    @Test fun sizesTheApiNeverReportedFallBackToAGeneralLine() {
        val text = unplayableRenditionMessage(listOf(rendition(1080, "bytevc1", null, null)))
        assertEquals("这一集没有这台设备能播放的清晰度，可以先看别的集。", text)
    }
}
