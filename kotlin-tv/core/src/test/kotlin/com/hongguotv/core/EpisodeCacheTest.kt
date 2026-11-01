// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** 落盘层只看字节：这里用合成内容，能不能解码由 CachedVideoTest 那条路验证。 */
class EpisodeCacheTest {
    private val root: File = File.createTempFile("episode-cache", "").apply { delete(); mkdirs() }
    private val chunk = 1024 * 1024

    @After fun cleanup() { root.listFiles().orEmpty().forEach { it.delete() }; root.delete() }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }
    /** 按绝对偏移生成字节：续传拼出来的文件必须和一次写完的完全一样。 */
    private fun bytesAt(at: Long, length: Int) = ByteArray(length) { ((at + it) % 251).toByte() }
    private fun store(cache: EpisodeCache, id: String, quality: Int, total: Int,
                      progress: (Long, Long) -> Unit = { _, _ -> },
                      canceled: () -> Boolean = { false },
                      read: (Long, Int) -> ByteArray = { at, length -> bytesAt(at, length) }) =
        cache.store(id, quality, "avc1", total.toLong(), read, progress, canceled)

    @Test fun storesOneEpisodeInBlocksAndListsItWithTheBytesActuallyOnDisk() {
        val cache = EpisodeCache(root); val total = 3 * chunk + 5; val asked = mutableListOf<Long>()
        val record = store(cache, "123", 720, total) { at, length -> asked += at; bytesAt(at, length) }
        assertEquals(total.toLong(), record.bytes); assertEquals(1, cache.records().size)
        assertEquals(listOf(0L, chunk.toLong(), 2 * chunk.toLong(), 3 * chunk.toLong()), asked)
        val file = File(root, "123-720.mp4")
        assertEquals(total.toLong(), file.length()); assertArrayEquals(bytes(total), file.readBytes())
        assertEquals(total.toLong(), cache.bytesUsed())
        assertFalse(".part 在写完之后不该留下", File(root, "123-720.part").exists())
    }

    @Test fun interruptedStoreResumesFromTheBytesAlreadyWritten() {
        val cache = EpisodeCache(root); val total = 3 * chunk; val firstRun = mutableListOf<Long>()
        assertThrows(IOException::class.java) {
            store(cache, "77", 480, total) { at, length ->
                firstRun += at; if (at >= 2 * chunk) throw IOException("连接断了"); bytesAt(at, length)
            }
        }
        assertTrue(File(root, "77-480.part").exists()); assertFalse(File(root, "77-480.mp4").exists())
        assertTrue(cache.records().isEmpty())

        val secondRun = mutableListOf<Long>()
        val record = store(cache, "77", 480, total) { at, length -> secondRun += at; bytesAt(at, length) }
        assertEquals(total.toLong(), record.bytes)
        // 已写过的两兆不再要第二遍：续传是从断点接着要，不是从头再来。
        assertEquals(listOf(2 * chunk.toLong()), secondRun)
        // 续传拼出来的文件和一次写完的必须完全一样。
        assertArrayEquals(bytes(total), File(root, "77-480.mp4").readBytes())
        assertFalse(File(root, "77-480.part").exists())
        assertEquals(1, cache.records().size)
    }

    @Test fun cancelKeepsThePartialBytesAndWritesNoRecord() {
        val cache = EpisodeCache(root); var written = 0L
        assertThrows(IOException::class.java) {
            store(cache, "88", 720, 4 * chunk, progress = { done, _ -> written = done }, canceled = { written >= 2 * chunk }) { at, length -> bytesAt(at, length) }
        }
        assertEquals(2 * chunk.toLong(), File(root, "88-720.part").length())
        assertFalse(File(root, "88-720.mp4").exists()); assertTrue(cache.records().isEmpty())
        // 取消不是失败：下次点缓存会从这两兆继续。
        val record = store(cache, "88", 720, 4 * chunk)
        assertEquals((4 * chunk).toLong(), record.bytes); assertEquals(1, cache.records().size)
        assertArrayEquals(bytes(4 * chunk), File(root, "88-720.mp4").readBytes())
    }

    @Test fun partialLongerThanTheEpisodeIsRewrittenInsteadOfAppendedTo() {
        val cache = EpisodeCache(root)
        File(root, "99-720.part").writeBytes(bytes(3 * chunk))
        val asked = mutableListOf<Long>()
        val record = store(cache, "99", 720, 2 * chunk) { at, length -> asked += at; bytesAt(at, length) }
        assertEquals(listOf(0L, chunk.toLong()), asked)
        assertEquals((2 * chunk).toLong(), record.bytes)
        assertEquals((2 * chunk).toLong(), File(root, "99-720.mp4").length())
    }

    @Test fun reStoringACompleteEpisodeAsksForNoBytesAtAll() {
        val cache = EpisodeCache(root); store(cache, "55", 720, chunk)
        val asked = mutableListOf<Long>()
        val record = store(cache, "55", 720, chunk) { at, length -> asked += at; bytesAt(at, length) }
        assertEquals(chunk.toLong(), record.bytes); assertTrue(asked.isEmpty())
        assertEquals(1, cache.records().size)
    }

    @Test fun recordsDropIndexWithoutVideoVideoWithoutIndexAndSizeThatDoesNotMatch() {
        val cache = EpisodeCache(root); store(cache, "10", 720, 1024)
        File(root, "11-720.json").writeText("""{"episodeId":"11","quality":720,"codec":"avc1","bytes":2048,"savedAt":1}""")
        File(root, "12-720.mp4").writeBytes(bytes(1024))
        File(root, "13-480.json").writeText("""{"episodeId":"13","quality":480,"codec":"avc1","bytes":4096,"savedAt":1}""")
        File(root, "13-480.mp4").writeBytes(bytes(1024))
        File(root, "14-720.json").writeText("not json")

        val kept = cache.records()
        assertEquals(listOf("10"), kept.map { it.episodeId })
        assertFalse(File(root, "11-720.json").exists()); assertFalse(File(root, "12-720.mp4").exists())
        assertFalse(File(root, "13-480.json").exists()); assertFalse(File(root, "13-480.mp4").exists())
        assertFalse(File(root, "14-720.json").exists())
        // 半截文件是留给续传的，列缓存不会把它当成孤儿清掉。
        var written = 0L
        assertThrows(IOException::class.java) {
            store(cache, "15", 720, 2 * chunk, progress = { done, _ -> written = done }, canceled = { written >= chunk })
        }
        cache.records()
        assertTrue(File(root, "15-720.part").exists())
    }

    @Test fun playableTakesTheHighestTierWithinTheLimitAndOtherwiseTheOnlyOne() {
        val cache = EpisodeCache(root)
        store(cache, "21", 480, 1024); store(cache, "21", 720, 2048); store(cache, "21", 1080, 4096)
        assertEquals(1080, cache.playable("21", 1080)!!.quality)
        assertEquals(720, cache.playable("21", 720)!!.quality)
        assertEquals(480, cache.playable("21", 500)!!.quality)
        // 本地文件没有流量成本：一档都不够低时照样播，取其中最低的那一档，解码能力由 trackInfo 判。
        assertEquals(480, cache.playable("21", 100)!!.quality)
        assertNull(cache.playable("22", 1080))
    }

    @Test fun openReturnsNullWhenTheFileNoLongerMatchesItsIndex() {
        val cache = EpisodeCache(root); store(cache, "31", 720, 4096)
        assertNotNull(cache.open("31", 1080))
        File(root, "31-720.mp4").writeBytes(bytes(2048))
        assertNull(cache.open("31", 1080)); assertTrue(cache.records().isEmpty())
    }

    @Test fun removeClearsEveryTierOfOneEpisodeAndClearEmptiesTheDirectory() {
        val cache = EpisodeCache(root)
        store(cache, "41", 480, 1024); store(cache, "41", 720, 2048); store(cache, "42", 720, 1024)
        // 一集的所有清晰度档一起清：每档一个视频加一个索引。
        assertEquals(4, cache.remove("41"))
        assertTrue(cache.cached("41") == null)
        assertNotNull(cache.cached("42"))
        assertEquals(1024L, cache.bytesUsed())
        assertEquals(2, cache.clear()); assertTrue(cache.records().isEmpty()); assertEquals(0L, cache.bytesUsed())
    }

    @Test fun roomIsMeasuredAgainstTheVolumeHoldingTheCache() {
        val cache = EpisodeCache(root)
        assertTrue(cache.freeBytes() >= 0)
        assertFalse(cache.hasRoom(cache.freeBytes() + 1))
        if (cache.freeBytes() > 0) assertTrue(cache.hasRoom(1))
        // 一集比剩余空间还长时当场拒绝，不留下永远续不完的半截文件。
        val asked = mutableListOf<Long>()
        assertThrows(IOException::class.java) {
            cache.store("43", 720, "avc1", Long.MAX_VALUE / 2, read = { at, length -> asked += at; bytesAt(at, length) })
        }
        assertTrue(asked.isEmpty()); assertFalse(File(root, "43-720.part").exists())
    }

    @Test fun episodeIdsAndQualitiesAreCheckedBeforeTouchingTheDirectory() {
        val cache = EpisodeCache(root)
        for (bad in listOf("abc", "1-2", "", "1".repeat(31))) assertThrows(IllegalArgumentException::class.java) { cache.remove(bad) }
        assertThrows(IllegalArgumentException::class.java) { cache.store("12", -1, "avc1", 1024L, read = { _, length -> bytes(length) }) }
        assertThrows(IllegalArgumentException::class.java) { cache.store("12", 720, "avc1", 0L, read = { _, length -> bytes(length) }) }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun progressReportsMonotonicBytesUpToTheTotal() {
        val cache = EpisodeCache(root); val seen = mutableListOf<Pair<Long, Long>>()
        store(cache, "61", 720, 2 * chunk + 100, progress = { done, total -> seen += done to total })
        assertEquals(3, seen.size)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first > a.first })
        assertEquals((2 * chunk + 100).toLong(), seen.last().first); assertEquals(seen.last().second, seen.last().first)
    }

    @Test fun storedRecordsCarryWhichSeriesTheEpisodeBelongsTo() {
        val cache = EpisodeCache(root)
        cache.store("71", 720, "avc1", 1024L, read = { _, length -> bytes(length) },
            seriesId = "s-7", seriesTitle = "暗潮", cover = "https://cover/7.jpg")
        val record = cache.cached("71")!!
        assertEquals("s-7", record.seriesId); assertEquals("暗潮", record.seriesTitle)
        assertEquals("https://cover/7.jpg", record.cover)
        // 「已下载」列表读的就是这几项：列出来的必须和写进去的一致。
        assertEquals(record, cache.records().first { it.episodeId == "71" })
    }

    @Test fun oldIndexWithoutSeriesOwnershipStillPlaysAndGetsClaimedOnTheNextStore() {
        val cache = EpisodeCache(root)
        File(root, "72-720.json").writeText("""{"episodeId":"72","quality":720,"codec":"avc1","bytes":1024,"savedAt":1}""")
        File(root, "72-720.mp4").writeBytes(bytes(1024))
        val legacy = cache.cached("72")
        assertNotNull(legacy); assertEquals("", legacy!!.seriesId)
        // 认不出归属不影响播放，只影响它能不能出现在按剧归类的列表里。
        assertNotNull(cache.open("72", 1080))
        // 再点一次缓存会补上归属，不会为此重下一遍。
        val asked = mutableListOf<Long>()
        val record = cache.store("72", 720, "avc1", 1024L, read = { at, length -> asked += at; bytesAt(at, length) },
            seriesId = "s-8", seriesTitle = "夜行")
        assertTrue(asked.isEmpty())
        assertEquals("s-8", record.seriesId); assertEquals("s-8", cache.cached("72")!!.seriesId)
    }

    @Test fun freeSpaceIsReadableBeforeTheCacheDirectoryExists() {
        // 真实故障：缓存目录还没建出来时 usableSpace 读不到那个卷、直接返回 0。缓存一集之前先要问
        // 「放得下吗」，于是第一次用这个功能时每一集都被判成「存储空间不足，还剩 0 MB 可用」，
        // 而设备其实还剩一百多 GB；目录也因此永远建不起来，每一集都失败。
        val directory = File(root, "not-created-yet/episodes-v1")
        val cache = EpisodeCache(directory)
        assertFalse(directory.exists())
        assertTrue("目录还没建时也必须问得到剩余空间", cache.freeBytes() > 0)
        assertTrue(cache.hasRoom(1024))
        assertNull(cache.unusable())
        assertTrue("问过空间之后目录已经建好", directory.isDirectory)
        // 走到落盘那一步同样不能因为目录不存在而失败。
        store(cache, "91", 720, 1024)
        assertNotNull(cache.cached("91"))
    }

    @Test fun unusableDirectoryIsReportedAsADirectoryProblemNotOutOfSpace() {
        // 用一个普通文件挡住目录的位置：这一处的目录必然建不出来。
        val blocker = File(root, "blocked"); blocker.writeText("占位")
        val directory = File(blocker, "episodes-v1")
        val cache = EpisodeCache(directory)
        val reason = cache.unusable()
        assertNotNull(reason)
        assertTrue(reason!!.contains("无法创建缓存目录"))
        try {
            store(cache, "92", 720, 1024)
            fail("目录建不出来时落盘必须失败")
        } catch (error: IOException) {
            // 原因要说在目录上。写成「存储空间不足」会把人引到完全相反的方向上去查。
            assertTrue(error.message.orEmpty().contains("无法创建缓存目录"))
        }
        assertTrue(EpisodeCache.isUsable(root))
        assertFalse(EpisodeCache.isUsable(directory))
    }
}
