// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable
import java.io.InputStream

/**
 * 一集视频的字节来源：在线分段读取，或已经落盘的缓存文件。
 *
 * 播放器只通过这几个方法取数据，所以缓存与在线走完全相同的读取路径、相同的索引解析和
 * 相同的解码能力校验；缓存不是另一条播放路，只是同一路上换了个数据源。
 */
interface VideoSource : Closeable {
    val info: StreamInfo
    /** 这一集的完整字节数，prepare 之前也必须可用（缓存文件直接就是文件大小）。 */
    val total: Long
    /** 读取容器索引并准备好后续分段；重复调用不做任何事。 */
    fun prepare(initialBytes: Int): VideoSource
    /** 已经在本地的字节数；播放器会先消费这些，再要求新的分段。 */
    fun cachedBytes(start: Long): Int
    fun read(start: Long, length: Int): ByteArray
    /** 一段有界的读取，按到达顺序交付，不等整段读完。 */
    fun openRead(start: Long, length: Int): InputStream
    /** prepare 之后的编码尺寸；未 prepare 或索引里没有视频轨时为 null。 */
    fun trackInfo(): VideoTracks.Track?
}
