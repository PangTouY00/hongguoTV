// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 「预览卡片 → 全屏播放」的过渡。
 *
 * 只做一件事：把这部作品的封面从它此刻在屏幕上的位置放大铺满，旧界面盖在这层暗色之下。
 * 播放器完全不参与动画——SurfaceView 的视频内容不跟随普通 View 的缩放与透明度，Media3 在
 * Android 6/7 设备上因为直接缩放 PlayerView 出过现网问题（androidx/media #1664、#3070），
 * 而这台机器最低要跑到 Android 6。所以画面是等封面到位、播放器首帧真的出来了，才把封面淡出交出去。
 *
 * 全程只用 translation / scale / alpha，不改任何 LayoutParams：中途改尺寸会触发第二次 layout，
 * 把正在跑的转场打断。
 */
internal class PlaybackTransition(private val activity: Activity) {
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val decor get()=activity.window.decorView as ViewGroup
    private val ease=AccelerateDecelerateInterpolator()
    private var layer:FrameLayout?=null
    private var cover:ImageView?=null
    private var settle:Runnable?=null

    val running get()=layer!=null

    /**
     * @param from 预览卡片此刻在窗口里的位置（left, top, width, height）
     * @param width/height 全屏尺寸
     * @param loadCover 由界面负责取封面（本项目的封面加载是异步的，动画不等它）
     */
    fun start(from:IntArray,width:Int,height:Int,coverUrl:String,loadCover:(ImageView,String)->Unit) {
        cancel()
        if(width<=0||height<=0) return
        val host=FrameLayout(activity).apply { id=View.generateViewId();setBackgroundColor(Color.BLACK);alpha=0f }
        val art=ImageView(activity).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER;alpha=0f
            scaleX=maxOf(0.02f,from[2].toFloat()/width);scaleY=maxOf(0.02f,from[3].toFloat()/height)
            translationX=from[0]+from[2]/2f-width/2f;translationY=from[1]+from[3]/2f-height/2f
        }
        // 挂在窗口内容层的顶层，不是挂在 root 上：切页与建播放器都会 root.removeAllViews()，
        // 挂在 root 上的过渡层会在播放器建好那一刻被一起清掉。
        activity.addContentView(host,ViewGroup.LayoutParams(-1,-1))
        host.addView(art,FrameLayout.LayoutParams(-1,-1))
        layer=host;cover=art
        loadCover(art,coverUrl)
        host.animate().alpha(1f).setDuration(120).start()
        art.animate().alpha(1f).setDuration(90).withEndAction{
            art.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(260).setInterpolator(ease).start()
        }.start()
        // 详情或取址比动画慢得多时不能把界面一直扣在过渡层里：到点自己交出去。
        val done=Runnable { finish() };settle=done;main.postDelayed(done,2_500)
    }

    /** 播放器首帧已经出来了：封面淡出，过渡层拆掉。 */
    fun finish() {
        val host=layer ?: return;val art=cover ?: return
        layer=null;cover=null;settle?.let { main.removeCallbacks(it) };settle=null
        art.animate().alpha(0f).setDuration(180).withEndAction{ decor.removeView(host) }.start()
    }

    /** 用户中途返回、或这条路径失败了：立刻把过渡层撤掉，别把错误页也盖住。 */
    fun cancel() {
        val host=layer ?: return
        layer=null;cover=null;settle?.let { main.removeCallbacks(it) };settle=null
        host.animate().alpha(0f).setDuration(120).withEndAction{ decor.removeView(host) }.start()
    }
}
