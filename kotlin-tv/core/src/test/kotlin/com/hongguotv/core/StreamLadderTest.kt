package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class StreamLadderTest {
    private fun rendition(quality: Int, codec: String = "h264", width: Int? = null, height: Int? = null) =
        Rendition(quality, codec, width, height, "https://example.com/$quality-$codec", null)
    private fun ladder(vararg renditions: Rendition) = StreamLadder(renditions.toList())

    @Test fun highestTierWithinCapWins() {
        val pick=ladder(rendition(480), rendition(1080, "bytevc1"), rendition(720)).select(1080)!!
        assertEquals(1080, pick.quality); assertEquals("bytevc1", pick.codec)
    }
    @Test fun h264PreferredOverHevcAtEqualQuality() {
        val pick=ladder(rendition(720, "bytevc1"), rendition(720, "h264")).select(1080)!!
        assertEquals("h264", pick.codec)
    }
    @Test fun failedTiersAreTriedLastButNeverDropped() {
        val pick=ladder(rendition(1080, "bytevc1", 1080, 1920), rendition(720, "h264", 720, 1280), rendition(480))
            .select(1080) { it.codec == "h264" }!!
        // 只有 h264 那一档真解败过：先给没败过的最高档，谁都不被预先排除。
        assertEquals(1080, pick.quality)
    }
    @Test fun everyTierHavingFailedStillSelectsTheBestOne() {
        val pick=ladder(rendition(1080, "bytevc1", 1924, 1080), rendition(720, "h264", 720, 1280))
            .select(1080) { true }!!
        assertEquals(1080, pick.quality); assertEquals(1924, pick.width)
    }
    @Test fun allUnsupportedFallsBackToBestRendition() {
        val pick=ladder(rendition(1080, "bytevc1", 1080, 1920), rendition(720, "h264", 720, 1280))
            .select(1080) { false }!!
        assertEquals(1080, pick.quality)
    }
    @Test fun everythingAboveCapFallsBackToLowestRendition() {
        val pick=ladder(rendition(2160), rendition(1080)).select(720)!!
        assertEquals(1080, pick.quality)
    }
    @Test fun unknownQualityTierStaysEligible() {
        val pick=ladder(rendition(2160), rendition(0, "h264")).select(720)!!
        assertEquals(0, pick.quality)
    }
    @Test fun playbackOrderLeadsWithSelectionThenLowerTiersBestFirst() {
        val order=ladder(rendition(1080, "bytevc1"), rendition(720, "bytevc1"), rendition(720, "h264"), rendition(480))
            .playbackOrder(1080) { it.codec == "h264" }
        // 首选是没败过的最高档；败过的只往后挪，整条阶梯一个都不少。
        assertEquals(listOf(1080 to "bytevc1", 720 to "bytevc1", 720 to "h264", 480 to "h264"), order.map { it.quality to it.codec })
    }
    @Test fun playbackOrderKeepsSameQualityAlternatesRightAfterPick() {
        val order=ladder(rendition(720, "bytevc1"), rendition(720, "h264"), rendition(540, "bytevc1"))
            .playbackOrder(720) { false }
        assertEquals(listOf("h264", "bytevc1", "bytevc1"), order.map { it.codec })
    }
    @Test fun emptyLadderSelectsNothing() {
        assertNull(ladder().select(1080)); assertTrue(ladder().playbackOrder(1080).isEmpty())
    }
    /** 实测这部剧有二十来集源里只给 1924×1080 一档，比常见的 1920 上限宽 4 个像素。能力判定把它判否时
     *  它仍然要留在尝试顺序里，由真解码给出结论——连试都不试就报失败，是这一版要修掉的行为。 */
    @Test fun aSingleTierJustAboveTheReportedCeilingStillStaysInTheOrder() {
        val order=ladder(rendition(1080, "bytevc1", 1924, 1080)).playbackOrder(1080) { false }
        assertEquals(1, order.size)
        assertEquals(1924, order.first().width); assertEquals(1080, order.first().height)
    }
}
