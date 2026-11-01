// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * 已缓存的一集。
 *
 * 文件里存的就是播放器要消费的字节（改写过的索引加解密后的样本），所以这里不需要密钥、
 * 不需要解密，也不需要网络：读出来就是能解码的那串字节。
 *
 * 每次读取各自打开、各自关闭文件句柄。播放线程、预取线程与缓存写入可能同时碰这个文件，
 * 不共享句柄就没有需要协调的状态；本地文件的打开成本可以忽略。
 */
class CachedVideo(private val file: File, val record: CachedEpisode) : VideoSource {
    override val info = StreamInfo("local://${file.name}", null, if (record.quality > 0) "${record.quality}P" else "自动")
    override val total: Long = file.length()
    @Volatile private var header: ByteArray? = null
    @Volatile private var closed = false

    private fun open(): RandomAccessFile {
        if (closed) throw IOException("缓存已关闭")
        if (!file.isFile) throw IOException("缓存文件已不存在")
        return RandomAccessFile(file, "r")
    }
    private fun readRange(start: Long, end: Long): ByteArray {
        val length = (end - start + 1).toInt()
        val bytes = ByteArray(length)
        open().use { access -> access.seek(start); access.readFully(bytes) }
        return bytes
    }

    @Synchronized override fun prepare(initialBytes: Int): CachedVideo {
        if (header != null) return this
        require(initialBytes in 16 * 1024..256 * 1024)
        if (total <= 0) throw IOException("缓存文件为空")
        val probe = minOf(total, maxOf(initialBytes.toLong(), 256 * 1024L)).toInt()
        val first = readRange(0, probe.toLong() - 1)
        val end = MediaCrypto.headerEnd(first, total)
        val bytes = if (end > first.size) first + readRange(first.size.toLong(), (end - 1).toLong()) else first
        // 索引在写入时已经改写、样本已经解密：这里只做结构校验，不再需要密钥。
        header = MediaCrypto.prepare(bytes, null).header
        return this
    }

    /**
     * 本地文件没有「先吃已缓存的部分、再要新的分段」这一层：整集都在本地。
     * 返回 0 让播放器走流式分段那条路，一次 512 KiB，而不是每次 32 KiB 的随机读。
     */
    override fun cachedBytes(start: Long): Int = 0

    override fun read(start: Long, length: Int): ByteArray {
        require(length in 1..1024 * 1024 && start >= 0)
        if (start >= total) return byteArrayOf()
        return readRange(start, minOf(total - 1, start + length - 1))
    }

    override fun openRead(start: Long, length: Int): InputStream {
        require(start in 0 until total && length in 1..1024 * 1024)
        val end = minOf(total - 1, start + length - 1)
        val access = open()
        try {
            access.seek(start)
            return object : InputStream() {
                private var remaining = (end - start + 1).toInt()
                private val scratch = ByteArray(32 * 1024)
                private var readerClosed = false
                override fun read(): Int {
                    val single = ByteArray(1)
                    return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
                }
                override fun read(target: ByteArray, offset: Int, count: Int): Int {
                    require(offset >= 0 && count >= 0 && offset <= target.size - count)
                    if (count == 0) return 0
                    if (readerClosed) throw IOException("缓存读取已结束")
                    if (remaining == 0) return -1
                    val n = access.read(scratch, 0, minOf(count, remaining, scratch.size))
                    if (n < 0) throw EOFException("缓存文件意外结束")
                    System.arraycopy(scratch, 0, target, offset, n)
                    remaining -= n
                    return n
                }
                override fun close() { if (readerClosed) return; readerClosed = true; access.close() }
            }
        } catch (error: Exception) { runCatching { access.close() }; throw error }
    }

    override fun trackInfo(): VideoTracks.Track? {
        val index = header ?: return null
        return VideoTracks.videoTrack(index)
    }

    override fun close() { closed = true }
}
