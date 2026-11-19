// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 「进入全屏 → 画面真的出来」这段等待期的时间安排。
 *
 * 从按下到看见画面，中间必然有一段什么都没有：详情要读、播放地址要签名、解码器要起来，
 * 实测一到三秒。这段怎么表现，是这段路径上最容易做错、也最容易被看见的地方（做错了就是黑屏）。
 *
 * 这里只回答三件事，全部是时间点，不碰 Android：
 * 1. 那层过渡遮罩什么时候可以盖上去——封面先到就盖封面，封面迟迟不来就先把带提示的暗底给出去，
 *    但不能让人对着「正在加载剧集…」那种中间页等；
 * 2. 遮罩什么时候才能交出去——只有画面真的出来了才交。过去写成「固定 2.5 秒后自己交」，
 *    而取址常常比它慢，结果就是先把封面撤掉、再对着黑色的播放器等；
 * 3. 什么时候该放弃——取址失败要立刻撤，别把错误界面也盖在黑底下；一直没有结果时到上限也撤，
 *    把界面还给用户，让播放界面自己的进度与说明露出来。
 */
class FullscreenHandoff(
    /** 最多等封面这么久；到点先给出带提示的暗底。封面通常几百毫秒就到。 */
    val coverGraceMs: Long = 900,
    /** 屏幕上没有画面可看时，超过这个时间要把「还在准备这一集」写出来，不让人以为卡住了。 */
    val hintAfterMs: Long = 700,
    /** 已经看得见封面时可以多等一会儿再提示：这时画面在等的是取址，不是缺了一张图。 */
    val hintWithCoverAfterMs: Long = 3_000,
    /** 兜底上限：到这里仍未出画面就把遮罩撤掉，播放界面的说明必须能露出来。 */
    val giveUpAfterMs: Long = 20_000,
) {
    private var startedAt = -1L
    private var coverAt = -1L
    private var frameAt = -1L
    private var errorAt = -1L

    /** 这一轮还在等画面。 */
    val waiting get() = startedAt >= 0 && frameAt < 0 && errorAt < 0

    fun begin(now: Long) { startedAt = now; coverAt = -1L; frameAt = -1L; errorAt = -1L }

    /** 封面到了。晚到的封面仍然有价值：它比一块黑底更能说明「要看的是这一部」。 */
    fun coverArrived(now: Long) { if (waiting && coverAt < 0) coverAt = now }

    /** 播放器第一帧真的画出来了：这一刻起才该把遮罩交出去。 */
    fun firstFrame(now: Long) { if (startedAt >= 0 && frameAt < 0) frameAt = now }

    /** 取址或解码失败：界面已经有话要说，遮罩必须让路。 */
    fun failed(now: Long) { if (waiting && errorAt < 0) errorAt = now }

    /**
     * 该把遮罩淡上去的时刻：封面先到就用封面到达那一刻，否则等到 coverGraceMs。
     * 不在计时中（还没开始、已经出画面、或已经失败）返回 0，表示不该再有这一层。
     */
    fun shadeAt(): Long = when {
        !waiting -> 0L
        coverAt >= 0 -> coverAt
        else -> startedAt + coverGraceMs
    }

    /** 这一层上该铺封面（而不是只有暗底）。晚到的封面照样填上去。 */
    val coverReady get() = waiting && coverAt >= 0

    /**
     * 该写出那句提示的时刻：取不到封面时更早（屏幕上什么都还没有），
     * 有封面时可以晚一些（这时等着的是播放地址，不是一张图）。不在等待中返回 0。
     */
    fun hintAt(): Long = if (waiting) startedAt + (if (coverAt >= 0) hintWithCoverAfterMs else hintAfterMs) else 0L

    /** 这一刻要不要写出那句提示。 */
    fun showHint(now: Long) = hintAt() > 0 && now >= hintAt()

    /** 兜底撤掉这一层的时刻。 */
    fun giveUpAt(): Long = if (waiting) startedAt + giveUpAfterMs else 0L

    /** 画面出来了，把这一层淡出去、交回给播放器。 */
    fun handedOff(now: Long) = frameAt >= 0

    /** 该直接撤掉这一层：失败了，或者到上限仍未出画面。 */
    fun shouldGiveUp(now: Long) = errorAt >= 0 || (waiting && now >= giveUpAt())
}
