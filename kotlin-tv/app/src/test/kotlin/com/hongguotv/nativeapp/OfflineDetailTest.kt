package com.hongguotv.nativeapp

import com.hongguotv.core.CachedEpisode
import com.hongguotv.core.Series
import org.junit.Assert.*
import org.junit.Test

/** 断网时的剧集列表由缓存记录拼出来：这些用例锁住「只列本机有的集」和「集号不冒充第 1 集」两件事。 */
class OfflineDetailTest {
    private fun row(id: String, index: Int, seriesId: String = "s-1") =
        CachedEpisode(id, 720, "avc1", 1024, 0, seriesId, "凛冬末世", "https://cover/1.jpg", index)

    @Test fun listsOnlyTheEpisodesActuallyOnThisDevice() {
        val detail = offlineDetailOf(Series("s-1", "凛冬末世"), listOf(row("145", 144), row("200", 199), row("999", 998, "s-2")))!!
        // 别的剧的缓存不能混进来：混进来就是「点开一部剧，列表里全是别的剧的集」。
        assertEquals(listOf("145", "200"), detail.episodes)
        // 集号来自缓存元数据：缓存了第 145 集，第一项就是第 145 集，不是第 1 集。
        assertEquals(listOf(145, 200), detail.episodeNumbers)
    }

    @Test fun episodesAreOrderedByTheirRealNumberNotByDiskOrder() {
        val detail = offlineDetailOf(Series("s-1", "凛冬末世"), listOf(row("300", 299), row("145", 144)))!!
        assertEquals(listOf("145", "300"), detail.episodes)
        assertEquals(listOf(145, 300), detail.episodeNumbers)
    }

    @Test fun recordsWithoutANumberGoLastAndAreMarkedUnknown() {
        // 旧版本的缓存没记集号：不能冒充第 1 集，也不能挤在已知集号中间。
        val detail = offlineDetailOf(Series("s-1", "凛冬末世"), listOf(row("910", -1), row("145", 144)))!!
        assertEquals(listOf("145", "910"), detail.episodes)
        assertEquals(listOf(145, 0), detail.episodeNumbers)
    }

    @Test fun recordsWithoutANumberFallBackToNumericIdOrderRatherThanTextOrder() {
        // 一集集号都没记下来时只能按分集编号排。编号是纯数字字符串，按字符串排会得到 10、100、2。
        val detail = offlineDetailOf(Series("s-1", "凛冬末世"), listOf(row("100", -1), row("2", -1), row("10", -1)))!!
        assertEquals(listOf("2", "10", "100"), detail.episodes)
        assertEquals(listOf(0, 0, 0), detail.episodeNumbers)
    }

    @Test fun aSeriesWithNothingCachedHasNoOfflineDetail() {
        // 一集都没缓存时必须返回 null，让调用方照旧报网络错误，不要凭空造出一个空列表。
        assertNull(offlineDetailOf(Series("s-1", "凛冬末世"), listOf(row("145", 144, "s-2"))))
        assertNull(offlineDetailOf(Series("s-1", "凛冬末世"), emptyList()))
    }

    @Test fun theOfflineListStillHasATitleAndCoverWhenTheSeriesObjectIsBare() {
        val detail = offlineDetailOf(Series("s-1", ""), listOf(row("145", 144)))!!
        assertEquals("凛冬末世", detail.series.title)
        assertEquals("https://cover/1.jpg", detail.series.cover)
    }
}
