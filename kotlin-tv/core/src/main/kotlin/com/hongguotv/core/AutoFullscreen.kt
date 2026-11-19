// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 首页停留计时：焦点停在同一部作品的预览上超过 dwellMs，就自动进入全屏播放。
 *
 * 这里只回答两件事——「从什么时候开始停留」和「哪些作品不再自动进入」。时间由调用方给（界面用
 * elapsedRealtime，测试用固定值），所以这一层可以在 JVM 测试里完整跑过，不碰 Android。
 *
 * 抑制名单是这条链路上最容易做错的地方：用户从全屏里主动返回，说明他刚刚明确地不想看这一部；
 * 如果回到首页又立刻重新计时，三十秒后会被强行送回同一个地方。所以「主动离开」只压制这一部，
 * 换到别的作品仍然正常计时。
 */
class AutoFullscreenGuard(val dwellMs: Long = 30_000) {
    private var watching: String? = null
    private var since = 0L
    private val suppressed = linkedSetOf<String>()

    /** 还有没有正在计时的作品。 */
    val armed get() = watching != null
    /** 当前正在计时的作品（没有则为 null）。 */
    val current get() = watching
    /** 还剩多久触发；不在计时中返回 null。 */
    fun remaining(now: Long): Long? {
        val id = watching ?: return null
        return (since + dwellMs - now).coerceAtLeast(0)
    }

    /** 开始停留计时；同一部作品重复调用不会把计时清零（换剧才会重新开始）。 */
    fun arm(seriesId: String, now: Long) {
        if (seriesId == watching) return
        watching = seriesId
        since = now
    }

    /** 焦点离开、开始滚动、Activity 暂停：丢掉计时，但不压制这部作品。 */
    fun cancel() { watching = null }

    /**
     * 用户主动离开这一部：按了暂停、打开了操作面板、或从全屏里返回。
     * 这一部不再自动进入全屏，直到换到别的作品为止。
     */
    fun suppress(seriesId: String) {
        suppressed.add(seriesId)
        while (suppressed.size > 40) suppressed.remove(suppressed.first())
        if (seriesId == watching) watching = null
    }

    fun suppressed(seriesId: String) = seriesId in suppressed

    /** 到点返回这部作品并结束本轮计时（不会连续触发）；没到点或被压制则返回 null。 */
    fun elapsed(now: Long): String? {
        val id = watching ?: return null
        if (suppressed(id)) { watching = null; return null }
        if (now - since < dwellMs) return null
        watching = null
        return id
    }
}
