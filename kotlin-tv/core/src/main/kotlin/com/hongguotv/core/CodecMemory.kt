// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 这台设备**真播通过**了哪些私有编码。
 *
 * 它和 app 层那份「解败过哪些档」（`CodecCapabilities`）是两件事，方向相反：
 * 那边回答「这一档要不要往后排」，这里只回答一个问题——**要不要为私有编码多花一次兼容请求**。
 *
 * 为什么需要它：兼容请求（`codec_type=0&force_codec=h264`）走的是旧接口，2026-11-18 的实机日志里
 * 它对每一集都回 HTTP 200 + 0 字节，一次 0.9~1.8 秒，五次全失败；而最终播通的恰恰是 v5 给的
 * 1080P bytevc1。也就是说这一笔钱每次都付、每次都没有换回任何东西。
 *
 * 判断依据只用实测到的事实：某一集用某种私有编码真的渲染出了首帧，之后每一集都不必再问一次 h264。
 * 不预判设备能力（那是本仓库选档一贯的做法），只是不再重复问已经问过、并且已经答过的事。
 *
 * 默认实现只在进程内。调用方可以换成持久实现（跨重启保留），那样冷启动后的第一次取址也省掉这次往返。
 */
interface CodecMemory {
    fun isPlayable(codec: String): Boolean
    fun notePlayable(codec: String)

    companion object {
        /** 进程内记忆：首帧渲染成功后记下这一集实际用的那一种编码。 */
        fun inProcess(): CodecMemory = object : CodecMemory {
            private val known = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            override fun isPlayable(codec: String) = codec.isNotBlank() && codec.lowercase() in known
            override fun notePlayable(codec: String) { if (codec.isNotBlank()) known += codec.lowercase() }
        }
    }
}
