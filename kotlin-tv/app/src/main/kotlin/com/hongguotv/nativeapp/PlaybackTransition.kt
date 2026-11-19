// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.hongguotv.core.FullscreenHandoff

/**
 * 「预览卡片 → 全屏播放」的过渡，以及从进入全屏到画面真出现这段时间的表现。
 *
 * 动画只做一件事：把这部作品的封面从它此刻在屏幕上的位置放大铺满，旧界面盖在这层暗色之下。
 * 播放器完全不参与动画——SurfaceView 的视频内容不跟随普通 View 的缩放与透明度，Media3 在
 * Android 6/7 设备上因为直接缩放 PlayerView 出过现网问题（androidx/media #1664、#3070），
 * 而这台机器最低要跑到 Android 6。所以画面是等封面到位、播放器首帧真的出来了，才把封面淡出交出去。
 *
 * 这段时间不应该是黑的。读详情、签名播放地址、起解码器实测要一到三秒，这一段里：
 * 封面按全屏尺寸单独取，且不受换页清理（见 ArtworkLoader.requestBitmap）——原先它挂在「换页就清」
 * 的那套取图上，进全屏那一步把它清掉了，剩下的整段就只剩一层黑；
 * 封面迟迟不来也照样把这一层给出去，并写明还在准备这一集，而不是把人扣在「正在加载剧集」那种中间页上；
 * 画面没出来就不撤这一层——原先写死「2.5 秒后自己交出去」，而取址常常比它更慢，结果就是先撤掉封面、
 * 再对着还没有画面的播放器等；
 * 取址失败、或者到了上限仍没有画面，立刻撤掉，让播放界面自己的说明露出来。
 * 这些时刻本身在 core 的 [FullscreenHandoff] 里，那里有测试。
 *
 * 全程只用 translation / scale / alpha，不改任何 LayoutParams：中途改尺寸会触发第二次 layout，
 * 把正在跑的转场打断。
 */
internal class PlaybackTransition(
    private val activity: Activity,
    /** 这段等待期的时间安排，默认就是界面按它写的那套值。 */
    private val handoff: FullscreenHandoff = FullscreenHandoff(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val ease = AccelerateDecelerateInterpolator()
    private var layer: FrameLayout? = null
    private var cover: ImageView? = null
    private var note: TextView? = null
    private var coverShown = false
    private var revealed = false
    private var shadeTask: Runnable? = null
    private var noteTask: Runnable? = null
    private var giveUpTask: Runnable? = null

    val running get() = layer != null

    /**
     * @param from 预览卡片此刻在窗口里的位置（left, top, width, height）
     * @param width/height 全屏尺寸
     * @param preparing 画面迟迟不来时写在海报下的那句话
     * @param requestCover 按全屏尺寸取这张封面，取到直接交给我们：这条取图不受换页影响
     */
    fun start(
        from: IntArray, width: Int, height: Int, coverUrl: String, preparing: String,
        requestCover: (Int, Int, (Bitmap?) -> Unit) -> Unit,
    ) {
        cancel()
        if (width <= 0 || height <= 0) return
        handoff.begin(SystemClock.elapsedRealtime())
        val host = FrameLayout(activity).apply { id = View.generateViewId(); setBackgroundColor(Color.BLACK); alpha = 0f }
        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f
            scaleX = maxOf(0.02f, from[2].toFloat() / width); scaleY = maxOf(0.02f, from[3].toFloat() / height)
            translationX = from[0] + from[2] / 2f - width / 2f; translationY = from[1] + from[3] / 2f - height / 2f
        }
        val label = TextView(activity).apply {
            text = preparing; textSize = 15f; setTextColor(TvStyle.muted); alpha = 0f
            setPadding(0, 0, 0, (height * 0.06f).toInt())
        }
        // 挂在窗口内容层的顶层，不是挂在 root 上：切页与建播放器都会 root.removeAllViews()，
        // 挂在 root 上的过渡层会在播放器建好那一刻被一起清掉。
        activity.addContentView(host, ViewGroup.LayoutParams(-1, -1))
        host.addView(art, FrameLayout.LayoutParams(-1, -1))
        host.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        layer = host; cover = art; note = label; coverShown = false; revealed = false
        armTasks()
        requestCover(width, height) { bitmap -> main.post { handedCover(bitmap) } }
    }

    /** 封面到了就立刻铺上并把这一层淡上去。取不到就先等着：到点这一层照样会给出去，带那句提示。 */
    private fun handedCover(bitmap: Bitmap?) {
        if (!running) return
        if (bitmap == null) {
            DiagLog.warn("Fullscreen handoff cover unavailable")
            return
        }
        cover?.setImageBitmap(bitmap)
        coverShown = true
        handoff.coverArrived(SystemClock.elapsedRealtime())
        reveal()
        // 海报已经在屏幕上，提示推到更晚那一个时间：这时等着的是播放地址，不是一张图。
        armTasks()
    }

    /** 按时间安排好这三件事：把这一层淡上去、必要时写出提示、到上限撤掉。 */
    private fun armTasks() {
        val now = SystemClock.elapsedRealtime()
        clearTasks()
        if (!running || !handoff.waiting) return
        val shade = Runnable { if (running) reveal() }
        val hint = Runnable { if (running && handoff.showHint(SystemClock.elapsedRealtime())) showNote() }
        val giveUp = Runnable {
            if (!running) return@Runnable
            DiagLog.warn("Fullscreen handoff gave up without a first frame")
            cancel()
        }
        shadeTask = shade; noteTask = hint; giveUpTask = giveUp
        main.postDelayed(shade, (handoff.shadeAt() - now).coerceAtLeast(0L))
        main.postDelayed(hint, (handoff.hintAt() - now).coerceAtLeast(0L))
        main.postDelayed(giveUp, (handoff.giveUpAt() - now).coerceAtLeast(0L))
    }

    private fun clearTasks() {
        listOfNotNull(shadeTask, noteTask, giveUpTask).forEach { main.removeCallbacks(it) }
        shadeTask = null; noteTask = null; giveUpTask = null
    }

    /**
     * 这一层淡上去：有封面就同时做「从卡片位置放大铺满」，
     * 没有封面就只给暗底——那也是这一部正在等着播这件事的暗底，不是黑屏。
     */
    private fun reveal() {
        val host = layer ?: return
        if (revealed) return
        revealed = true
        if (!coverShown) DiagLog.warn("Fullscreen handoff shade without cover")
        host.animate().alpha(1f).setDuration(120).start()
        val art = cover ?: return
        if (!coverShown) {
            if (handoff.showHint(SystemClock.elapsedRealtime())) showNote()
            return
        }
        art.animate().alpha(1f).setDuration(90).withEndAction {
            art.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(260).setInterpolator(ease).start()
        }.start()
    }

    private fun showNote() {
        val label = note ?: return
        if (label.alpha > 0f) return
        label.animate().alpha(1f).setDuration(140).start()
    }

    /** 播放器首帧已经出来了：整层淡出去，让画面从封面底下露出来。 */
    fun finish() {
        val host = layer ?: return
        handoff.firstFrame(SystemClock.elapsedRealtime())
        layer = null; cover = null; note = null; coverShown = false; revealed = false
        clearTasks()
        // 淡的是整层，不是只淡封面：只淡封面等于把这层的黑底留在原地，那 180 毫秒必然是一段纯黑。
        host.animate().alpha(0f).setDuration(180).withEndAction { detach(host) }.start()
    }

    /** 用户中途返回、或这条路径失败了（取不到地址、解不了）：立刻把这一层撤掉，别把错误页也盖住。 */
    fun cancel() {
        val host = layer ?: return
        layer = null; cover = null; note = null; coverShown = false; revealed = false
        clearTasks()
        host.animate().alpha(0f).setDuration(120).withEndAction { detach(host) }.start()
    }

    /**
     * 把这一层从窗口里摘掉。
     *
     * addContentView 加的位置不是 decorView 本身，而是它内部那个 @android:id/content 容器；
     * ViewGroup.removeView 只认自己的直接子 view，所以拿 decorView 去 removeView 什么都摘不掉——
     * 这一层带着不透明的黑底，会一直留在屏幕上，把视频连同播放界面的标题、进度一起盖成黑的。
     * 摘除只认这个 view 自己的 parent，不去猜它挂在哪一层。
     */
    private fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }
}
