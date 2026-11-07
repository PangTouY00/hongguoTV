// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 线上现象：缓存了第 1、2、10、100 集之后断网打开这部剧，选集列表和自动连播的下一集都按
 * 1、10、100、2 走。原因是扫盘按分集编号的字符串排。这里锁住顺序由「这一集在剧里的序号」决定。
 */
class EpisodeCacheOrderTest {
    private val root: File = File.createTempFile("episode-cache-order", "").apply { delete(); mkdirs() }

    @After fun cleanup() { root.listFiles().orEmpty().forEach { it.delete() }; root.delete() }

    private fun cache() = EpisodeCache(root)

    @Test fun recordsFollowTheEpisodeNumberRatherThanTheIdAsText() {
        val cache = cache()
        // 故意按错乱的顺序写盘：读出来的顺序必须由集号决定，不由编号的字符串顺序决定。
        listOf("100" to 99, "2" to 1, "10" to 9, "1" to 0).forEach { (id, index) ->
            cache.store(id, 720, "avc1", 4L, read = { _, length -> ByteArray(length) }, episodeIndex = index)
        }
        assertEquals(listOf("1", "2", "10", "100"), cache.records().map { it.episodeId })
    }

    @Test fun recordsWithoutANumberGoLastInsteadOfJumpingIntoTheMiddle() {
        val cache = cache()
        // 旧版本写的元数据没有集号（读出来是 -1）：它们不能挤到已知集号中间。
        listOf("900" to -1, "10" to 9, "2" to 1).forEach { (id, index) ->
            cache.store(id, 720, "avc1", 4L, read = { _, length -> ByteArray(length) }, episodeIndex = index)
        }
        assertEquals(listOf("2", "10", "900"), cache.records().map { it.episodeId })
    }

    @Test fun theSameEpisodeStaysAdjacentAndItsLowestQualityIsTheOneLookedUpById() {
        val cache = cache()
        cache.store("100", 1080, "avc1", 4L, read = { _, length -> ByteArray(length) }, episodeIndex = 99)
        cache.store("100", 480, "avc1", 4L, read = { _, length -> ByteArray(length) }, episodeIndex = 99)
        cache.store("2", 720, "avc1", 4L, read = { _, length -> ByteArray(length) }, episodeIndex = 1)
        // 同一集的多档必须相邻且按清晰度升序：按编号取缓存时取的是最低那一档，这一点不能因为换排序键而变。
        assertEquals(480, cache.cached("100")?.quality)
        assertEquals(listOf("2", "100", "100"), cache.records().map { it.episodeId })
    }
}
