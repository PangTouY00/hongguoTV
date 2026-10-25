// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 画面模式。[frameScale] 是播放画面相对完整显示时的固定放大倍数：1 表示不放大，
 * 放大模式只把画面放大 1.36 倍而不是撑满整个屏幕，竖屏视频的左右黑边明显变窄，
 * 裁掉的边缘又比完全铺满少得多。
 */
enum class VideoFrameMode(val label: String, val frameScale: Float=1f) {
    FIT("完整画面"), ZOOM("放大 1.36 倍", 1.36f), FILL("拉伸铺满");
    companion object { fun fromStored(value: String?)=entries.firstOrNull { it.name==value } ?: FIT }
}

/** Session-only timer driven by a monotonic clock, independent of playback speed. */
class SleepTimer(private val now: ()->Long) {
    private var deadline: Long?=null
    var episodesLeft=0; private set
    val active get()=deadline!=null || episodesLeft>0
    fun cancel() { deadline=null; episodesLeft=0 }
    fun afterMinutes(minutes: Int) { require(minutes in 1..180); cancel(); deadline=now()+minutes*60_000L }
    fun afterEpisodes(count: Int) { require(count in 1..10); cancel(); episodesLeft=count }
    fun remainingMillis()=deadline?.let { (it-now()).coerceAtLeast(0) }
    fun poll(): Boolean { if(deadline?.let { now()>=it }!=true) return false; cancel(); return true }
    fun episodeEnded(): Boolean {
        if(poll()) return true
        if(episodesLeft<=0) return false
        episodesLeft--
        return episodesLeft==0
    }
    fun label(): String = remainingMillis()?.let { "${(it+59_999)/60_000} 分钟后" }
        ?: if(episodesLeft>0) "再播完 $episodesLeft 集" else "关闭"
}
