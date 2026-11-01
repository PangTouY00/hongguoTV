// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 离线播放这条路的端到端断言。
 *
 * 这里先按在线播放的方式取字节（同一段索引解析、同一次解密），把它交给落盘层，再从缓存
 * 打开、不带给任何密钥地读回来，逐段比对。缓存文件与在线播放时播放器消费的字节必须是
 * 同一串，否则「离线能播」只是推测。
 */
class CachedVideoTest {
    private val root: File = File.createTempFile("cached-video", "").apply { delete(); mkdirs() }
    private val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
    private val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    private val cipher = hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff")
    private val plain = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
    private val sampleAt = 4096L
    private val total = 4096 + 16 + 2048

    @After fun cleanup() { root.listFiles().orEmpty().forEach { it.delete() }; root.delete() }

    private fun box(type: String, body: ByteArray) = be32(body.size + 8) + type.toByteArray() + body
    /** VisualSampleEntry：编码宽高是 entry 起始后第 32/34 字节的两个 16 位字段，即 body 的第 24..27 字节。 */
    private fun visualEntry(width: Int, height: Int): ByteArray {
        val body = ByteArray(78)
        body[24] = (width shr 8).toByte(); body[25] = width.toByte()
        body[26] = (height shr 8).toByte(); body[27] = height.toByte()
        return box("encv", body + box("sinf", box("frma", "avc1".toByteArray())))
    }
    private fun moov(auxOffset: Int): ByteArray {
        val stsd = box("stsd", ByteArray(4) + be32(1) + visualEntry(720, 1280))
        val stsz = box("stsz", ByteArray(4) + be32(16) + be32(1))
        val stsc = box("stsc", ByteArray(4) + be32(1) + be32(1) + be32(1) + be32(1))
        val stco = box("stco", ByteArray(4) + be32(1) + be32(sampleAt.toInt()))
        val saiz = box("saiz", ByteArray(4) + byteArrayOf(8) + be32(1))
        val saio = box("saio", ByteArray(4) + be32(1) + be32(auxOffset))
        val stbl = box("stbl", stsd + stsz + stsc + stco + saiz + saio)
        val minf = box("minf", box("vmhd", ByteArray(12)) + stbl)
        val trak = box("trak", box("mdia", box("hdlr", ByteArray(24)) + minf))
        // 末尾这个 free 就是加密样本的辅助数据：两遍构造让 saio 正好指向它。
        return box("moov", trak + box("free", iv.copyOf(8)))
    }
    private fun fixture(): ByteArray {
        val ftyp = box("ftyp", "isom0000".toByteArray())
        val aux = (ftyp + moov(0)).size - 8
        val file = ByteArray(total) { (it % 97).toByte() }
        (ftyp + moov(aux)).copyInto(file, 0)
        cipher.copyOf(16).copyInto(file, sampleAt.toInt())
        return file
    }

    @Test fun cachedEpisodePlaysWithNoKeyNoNetworkAndNoSecondDecrypt() {
        val raw = fixture()
        val prepared = MediaCrypto.prepare(raw, key)
        assertEquals(1, prepared.samples.size); assertEquals(sampleAt, prepared.samples[0].start)
        fun online(at: Long, length: Int): ByteArray =
            MediaCrypto.decrypt(raw.copyOfRange(at.toInt(), (at + length).toInt()), at, key, prepared)

        val cache = EpisodeCache(root)
        val record = cache.store("100", 720, "avc1", total.toLong(), read = { at, length -> online(at, length) })
        assertEquals(total.toLong(), record.bytes)

        val stored = File(root, "100-720.mp4").readBytes()
        // 落盘的是播放器实际消费的那串字节：改写过的索引，加已经解开的样本。
        val headerEnd = MediaCrypto.headerEnd(raw, total.toLong())
        assertArrayEquals(prepared.header.copyOfRange(0, headerEnd), stored.copyOfRange(0, headerEnd))
        val sample = stored.copyOfRange(sampleAt.toInt(), sampleAt.toInt() + 16)
        assertArrayEquals(online(sampleAt, 16), sample)
        assertFalse("存下来的必须是解开的样本，不是原始密文", java.util.Arrays.equals(cipher.copyOf(16), sample))
        assertFalse(String(stored).contains("encv")); assertFalse(String(stored).contains("sinf"))
        assertTrue(String(stored).contains("avc1"))

        val cached = cache.open("100", 1080)!!
        assertNull("本地播放不需要密钥", cached.info.key)
        assertEquals(total.toLong(), cached.total)
        cached.prepare(16 * 1024)
        val track = cached.trackInfo()
        assertNotNull(track)
        assertEquals("avc1", track!!.fourcc); assertEquals(720, track.width); assertEquals(1280, track.height)

        for (at in listOf(0L, 8L, 100L, headerEnd - 4L, headerEnd - 1L, sampleAt - 2L, sampleAt, sampleAt + 7L, total - 17L, total - 1L)) {
            for (asked in listOf(1, 3, 16, 4096)) {
                val length = minOf(asked.toLong(), total - at).toInt()
                assertArrayEquals("at=$at length=$length", online(at, length), cached.read(at, length))
            }
        }
        // 整集都在本地：播放器不需要「先吃已缓存的部分」这一层。
        assertEquals(0, cached.cachedBytes(0)); assertEquals(0, cached.cachedBytes(2048))
        cached.close()
    }

    @Test fun cachedBytesMatchTheOnlineBytesForEveryRangeThePlayerAsksFor() {
        val raw = fixture()
        val prepared = MediaCrypto.prepare(raw, key)
        val cache = EpisodeCache(root)
        cache.store("101", 720, "avc1", total.toLong(), read = { at, length ->
            MediaCrypto.decrypt(raw.copyOfRange(at.toInt(), (at + length).toInt()), at, key, prepared)
        })
        val cached = cache.open("101", 720)!!
        val reader = cached.openRead(sampleAt - 64, 128)
        val delivered = reader.readBytes()
        reader.close()
        assertEquals(128, delivered.size)
        assertArrayEquals(
            MediaCrypto.decrypt(raw.copyOfRange((sampleAt - 64).toInt(), (sampleAt + 64).toInt()), sampleAt - 64, key, prepared),
            delivered)
        cached.close()
    }

    @Test fun boundedReaderStopsAtTheRequestedRangeAndCloseIsIdempotent() {
        val cache = EpisodeCache(root)
        cache.store("102", 720, "avc1", total.toLong(), read = { at, length -> ByteArray(length) { ((at + it) % 97).toByte() } })
        val cached = cache.open("102", 720)!!
        cached.openRead(0, 64).use { input ->
            assertEquals(64, input.read(ByteArray(64)))
            assertEquals(-1, input.read())
            input.close(); input.close()
        }
        assertThrows(IllegalArgumentException::class.java) { cached.openRead(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { cached.openRead(total.toLong(), 16) }
        assertThrows(IllegalArgumentException::class.java) { cached.read(0, 2 * 1024 * 1024) }
        assertEquals(0, cached.read(total.toLong(), 16).size)
        cached.close()
        assertThrows(IOException::class.java) { cached.read(0, 16) }
    }

    @Test fun damagedCacheFailsClosedInsteadOfPlayingSilently() {
        val cache = EpisodeCache(root)
        cache.store("103", 720, "avc1", total.toLong(), read = { at, length -> ByteArray(length) { ((at + it) % 97).toByte() } })
        val file = File(root, "103-720.mp4")
        // 把 ftyp 的类型字段写坏：长度没变，所以它还会被列出来，但索引解析必须当场失败。
        file.readBytes().also { it[5] = 'x'.code.toByte(); file.writeBytes(it) }
        val cached = cache.open("103", 720)!!
        assertThrows(IOException::class.java) { cached.prepare(16 * 1024) }
        assertNull(cached.trackInfo())
        cached.close()
        // 索引被删掉的缓存文件不会被列出来，也就不会被当成能播的缓存。
        File(root, "103-720.json").delete()
        assertTrue(cache.records().isEmpty()); assertNull(cache.open("103", 720))
    }

    @Test fun emptyCacheFileIsRejectedAndClosedSourceStopsOpeningHandles() {
        val empty = File(root, "104-720.mp4").apply { writeBytes(byteArrayOf()) }
        val cached = CachedVideo(empty, CachedEpisode("104", 720, "avc1", 0, 0))
        assertThrows(IOException::class.java) { cached.prepare(16 * 1024) }
        cached.close()
        assertThrows(IOException::class.java) { cached.prepare(16 * 1024) }
    }

    @Test fun cacheFileSurvivesBeingOpenedBySeveralReadersAtOnce() {
        val raw = fixture(); val prepared = MediaCrypto.prepare(raw, key)
        val cache = EpisodeCache(root)
        cache.store("105", 720, "avc1", total.toLong(), read = { at, length ->
            MediaCrypto.decrypt(raw.copyOfRange(at.toInt(), (at + length).toInt()), at, key, prepared)
        })
        val first = cache.open("105", 720)!!; val second = cache.open("105", 720)!!
        first.prepare(16 * 1024); second.prepare(16 * 1024)
        assertArrayEquals(first.read(1024, 512), second.read(1024, 512))
        first.close()
        // 各自开各自的句柄：一边关掉不影响另一边还在读。
        assertEquals(512, second.read(1024, 512).size)
        second.close()
    }
}
