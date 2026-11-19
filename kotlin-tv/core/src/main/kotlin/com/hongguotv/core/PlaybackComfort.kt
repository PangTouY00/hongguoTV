// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 画面放大倍数。只允许停在 1.0—2.0 之间 0.1 的整档上，内部一律按「十分之一」记：10 是 1.0 倍，20 是 2.0 倍。
 * 之所以不在浮点上直接加减：0.1 在二进制里不精确，1.4f+0.1f 会滑到 1.5000001，界面上的数字和存下来的值都会抖，
 * 遥控器按一次也可能跳出半格。整数相加再除回 10，档位才是准的，滑杆每一格也正好对应一次按键。
 */
object VideoZoom {
    const val MIN_TENTHS=10; const val MAX_TENTHS=20; const val STEP_TENTHS=1; const val DEFAULT_TENTHS=14
    const val MIN=1.0f; const val MAX=2.0f; val DEFAULT=scale(DEFAULT_TENTHS)

    fun scale(tenthsValue: Int)=(tenthsValue.coerceIn(MIN_TENTHS,MAX_TENTHS))/10f
    /** 只有说不清方向时才回落默认：NaN 没有档位可言，正负无穷仍然有，照它的方向贴到端点。 */
    fun tenths(value: Float)=when {
        value.isNaN() -> DEFAULT_TENTHS
        value >= MAX -> MAX_TENTHS
        value <= MIN -> MIN_TENTHS
        else -> Math.round(value*10f).toInt()
    }
    /** 越界收回区间，不在档位上的（旧版本存下的 1.36、滑杆连续拖动落下的 1.43）收到最近一档。 */
    fun normalize(value: Float)=scale(tenths(value))
    /** 整数档位上的加减：界面里一路用整数，落回浮点只在这一步。到端点就停在端点，不越界也不反弹。 */
    fun stepTenths(current: Int,direction: Int)=(current+direction*STEP_TENTHS).coerceIn(MIN_TENTHS,MAX_TENTHS)
    fun step(value: Float,direction: Int)=scale(stepTenths(tenths(value),direction))
    fun text(value: Float):String { val steps=tenths(value); return "${steps/10}.${steps%10}" }
    fun label(value: Float)="放大 ${text(value)} 倍"
}

/**
 * 画面模式。放大倍数不在这里：它是个可由遥控器逐档调的值，存在设置里，见 [VideoZoom]。
 * 每个模式在播放器那一侧怎么实现见 app 的 frameResizeMode。
 */
enum class VideoFrameMode(val label: String) {
    FIT("完整画面"), ZOOM("自定义放大"), CROP("裁剪铺满"), FILL("拉伸铺满");
    /** 界面上要显示的名字：只有放大模式带倍数，其余模式没有倍数可显示。 */
    fun labelWithZoom(zoom: Float)=if(this==ZOOM) VideoZoom.label(zoom) else label
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
