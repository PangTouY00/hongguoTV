// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放链路上的诊断日志。
 *
 * 每条既写 logcat（接 adb 的排查方式不变），也留一份在进程内的环形缓冲里：设置页的「诊断日志」
 * 一键复制到剪贴板。不接 adb、不截屏，也能把「这一集到底败在哪一步」交出去。
 *
 * 只写设备与流程的事实——档位、编码、取址每一轮的结果、错误类型与它的原因链。
 * Cookie、令牌、账号这类凭据不进这里，所以这份文本可以直接贴给别人看。
 */
internal object DiagLog {
    private const val TAG = "HongguoTV"
    /** 一次播放失败从取址、逐档尝试到落败大约几十行，这里留够完整的一轮再多几轮。 */
    private const val LIMIT = 800
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    fun info(message: String) { emit("I", message) }
    fun warn(message: String) { emit("W", message) }
    fun error(message: String) { emit("E", message) }
    /** 带异常的那一类统一写成「发生了什么 · 类型 错误码: 原因 ← 原因」，一次就能看全。 */
    fun error(message: String, problem: Throwable) { emit("E", "$message · ${describe(problem)}") }

    private fun emit(level: String, message: String) {
        synchronized(lines) {
            lines.addLast("${clock.format(Date())} $level $message")
            while (lines.size > LIMIT) lines.removeFirst()
        }
        when (level) {
            "W" -> Log.w(TAG, message)
            "E" -> Log.e(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    /**
     * 把异常连同它的 cause 链压成一行。
     *
     * Media3 的解码失败常常只在 cause 里才写清楚是哪一个解码器、哪一档；只打最外层
     * `PlaybackException` 的话，「解不了」和「取不到地址」在日志里长得一模一样。
     */
    fun describe(problem: Throwable): String {
        val chain = ArrayList<String>()
        var cursor: Throwable? = problem
        var depth = 0
        while (cursor != null && depth < 6) {
            val name = cursor.javaClass.simpleName.ifEmpty { cursor.javaClass.name }
            val code = if (cursor is androidx.media3.common.PlaybackException) " ${cursor.errorCodeName}(${cursor.errorCode})" else ""
            val detail = cursor.message?.replace('\n', ' ')?.take(240) ?: ""
            chain += if (detail.isEmpty()) "$name$code" else "$name$code: $detail"
            cursor = cursor.cause
            depth++
        }
        return chain.joinToString(" <- ")
    }

    /** 档位清单的一行写法：质量、编码、尺寸、有没有给解密密钥。 */
    fun ladderSummary(renditions: List<com.hongguotv.core.Rendition>): String =
        renditions.joinToString("; ") { rendition ->
            val size = if (rendition.width != null && rendition.height != null) "${rendition.width}x${rendition.height}" else "未知尺寸"
            val codec = rendition.codec.takeIf { it.isNotBlank() } ?: "未标注"
            val cipher = if (rendition.key != null) "带密钥" else "无密钥"
            "${rendition.quality}P $codec $size $cipher"
        }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }
    fun count(): Int = synchronized(lines) { lines.size }
    fun clear() { synchronized(lines) { lines.clear() } }
}
