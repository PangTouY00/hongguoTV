// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 写进缓存元数据的集号必须是这一集在剧里的真实序号。
 *
 * 线上现象：在断网拼出来的选集页里再缓存几集，之后打开这部剧顺序就错开了——因为那一页把列表位置
 * 当成了集号写进元数据，错的集号被固化下去。
 */
class CacheEpisodeIndexTest {
    @Test fun anOnlineDetailUsesItsOwnPositionBecauseThatIsTheRealNumber() {
        val online = Detail(Series("s-1", "凛冬末世"), listOf("9001", "9002"))
        assertEquals(0, cacheEpisodeIndex(online, 0))
        assertEquals(1, cacheEpisodeIndex(online, 1))
    }

    @Test fun anOfflineDetailWritesTheNumberFromTheCacheNotThePositionInThatList() {
        // 缓存了第 145 集和第 300 集：它们在这一页里是第 0、1 项，写进元数据的必须是 144、299。
        val offline = Detail(Series("s-1", "凛冬末世"), listOf("145", "300"), listOf(145, 300))
        assertEquals(144, cacheEpisodeIndex(offline, 0))
        assertEquals(299, cacheEpisodeIndex(offline, 1))
    }

    @Test fun anOfflineEpisodeWhoseNumberWasNeverRecordedStaysUnknown() {
        // 这一集本来没记过集号（episodeNumbers 是 0）：宁可只标「已缓存」，也不能把位置 1 固化成「第 2 集」。
        val offline = Detail(Series("s-1", "凛冬末世"), listOf("145", "910"), listOf(145, 0))
        assertEquals(144, cacheEpisodeIndex(offline, 0))
        assertEquals(-1, cacheEpisodeIndex(offline, 1))
    }
}
